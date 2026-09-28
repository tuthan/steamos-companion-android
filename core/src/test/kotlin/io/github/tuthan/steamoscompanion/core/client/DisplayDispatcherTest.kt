package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayDispatcherTest {
    private val clock = object : Clock {
        var nanos = 2_000_000_000L
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = nanos
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val dispatcher = DisplayDispatcher(repository, journal, clock)
    private val host = testActiveHost()
    private val noReply = JournalMutationSender { _, _, _ -> RequestOutcome.NotSent(TransportFailure.IO) }

    @Test fun previewUsesStableModeIdentityAndRejectsCurrentMode() {
        repository.transact { it.copy(activeHost = host) }
        val selection = DisplaySelection("1", 4, "2")
        var sends = 0
        val result = dispatcher.preview(host, observation(), selection, JournalMutationSender { _, request, _ ->
            sends++
            assertEquals(ApiRoute.DISPLAY_PREVIEW.path, request.path)
            assertEquals("2", (request.body!!["mode_id"] as JsonValue.StringValue).value)
            RequestOutcome.NotSent(TransportFailure.IO)
        })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(1, sends)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.MODE_ALREADY_CURRENT),
            dispatcher.preview(host, observation(), selection.copy(modeId = "3352"), noReply))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.STALE_SELECTION),
            dispatcher.preview(host, observation(), selection.copy(outputGeneration = 3), noReply))
    }

    @Test fun oneModeSaveRequiresVisibleConfirmationAndPreservesOldHost404() {
        repository.transact { it.copy(activeHost = host) }
        val available = outputs("available")
        val only = available.outputs.single().copy(modes = available.outputs.single().modes.take(1), modeCount = 1)
        val observed = observation(available.copy(outputs = listOf(only)))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PICTURE_NOT_CONFIRMED),
            dispatcher.saveCurrent(host, observed, null, noReply))
        val confirmation = requireNotNull(SaveCurrentConfirmation.forOutput(host, only))
        val dispatched = dispatcher.saveCurrent(host, observed, confirmation, JournalMutationSender { _, request, _ ->
            assertEquals(ApiRoute.DISPLAY_SAVE_CURRENT.path, request.path)
            assertEquals(JsonValue.BooleanValue(true), request.body!!["visible"])
            RequestOutcome.Rejected(ApiReply.Rejected(404, "not_found", "old host"), null)
        }) as DisplayDispatchResult.Dispatched
        assertEquals(JournalPhase.FAILED, (dispatched.result as JournalDispatchResult.Finished).entry.phase)
    }

    @Test fun saveCurrentConfirmationRejectsReenumeratedOrChangedCurrentMode() {
        repository.transact { it.copy(activeHost = host) }
        val available = outputs("available")
        val original = available.outputs.single()
        assertEquals(null, SaveCurrentConfirmation.forOutput(host, original.copy(currentModeId = null)))
        val confirmation = requireNotNull(SaveCurrentConfirmation.forOutput(host, original))
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        val reenumerated = available.copy(outputs = listOf(original.copy(generation = original.generation + 1)))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.STALE_SELECTION),
            dispatcher.saveCurrent(host, observation(reenumerated), confirmation, sender))
        val changedMode = available.copy(outputs = listOf(original.copy(currentModeId = "2")))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.STALE_SELECTION),
            dispatcher.saveCurrent(host, observation(changedMode), confirmation, sender))
        assertTrue((repository.load() as StateLoadResult.Ready).state.journal.isEmpty())
    }

    @Test fun saveCurrentConfirmationFromOldCredentialCannotAuthorizeRefreshedPairing() {
        val original = outputs("available").outputs.single()
        val confirmation = requireNotNull(SaveCurrentConfirmation.forOutput(host, original))
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        repository.transact { it.copy(activeHost = rotated) }
        val fresh = observation().copy(source = StatusObservationSource.from(rotated))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.CONFIRMATION_MISMATCH),
            dispatcher.saveCurrent(rotated, fresh, confirmation,
                JournalMutationSender { _, _, _ -> error("must not send") }))
    }

    @Test fun exactSavedProfileCanBeRequestedWhileStatusIsUnreachable() {
        repository.transact { it.copy(activeHost = host) }
        val stale = observation(outputs("with-profile")).copy(unreachable = true, stale = true)
        val result = dispatcher.restoreSaved(host, stale, "profile-fixture0001xxxxxxxxxxxxx", JournalMutationSender { _, request, _ ->
            assertEquals("verified", (request.body!!["source"] as JsonValue.StringValue).value)
            RequestOutcome.NotSent(TransportFailure.CONNECT)
        })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PROFILE_MISSING),
            dispatcher.restoreSaved(host, stale, "wrong-profile", noReply))
    }

    @Test fun verifiedProfileCanRecoverOwnedActivePreviewButNotAnotherClientsPreview() {
        repository.transact { it.copy(activeHost = host) }
        val previewInventory = outputs("with-active-preview")
        val preview = requireNotNull(previewInventory.preview)
        val profile = outputs("with-profile").profiles.single()
        val observed = observation(previewInventory.copy(profiles = listOf(profile)))

        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_NOT_OWNED),
            dispatcher.restoreSaved(host, observed, profile.id, noReply))

        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-for-verified-restore"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        repository.transact { it.copy(journal = listOf(entry)) }

        var sends = 0
        val result = dispatcher.restoreSaved(host, observed, profile.id, JournalMutationSender { _, sent, _ ->
            sends++
            assertEquals(ApiRoute.DISPLAY_RESTORE.path, sent.path)
            val body = requireNotNull(sent.body)
            assertEquals("verified", (body["source"] as JsonValue.StringValue).value)
            assertEquals(profile.id, (body["profile_id"] as JsonValue.StringValue).value)
            RequestOutcome.NotSent(TransportFailure.IO)
        })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(1, sends)
    }

    @Test fun keepNeedsOwnedPreviewFreshHostDeadlineAndExplicitPictureCheck() {
        repository.transact { it.copy(activeHost = host) }
        val preview = requireNotNull(outputs("with-active-preview").preview)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-owned"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        repository.transact { it.copy(journal = listOf(entry)) }
        val observed = observation(outputs("with-active-preview"), timed = true)
        val read = requireNotNull(observed.lastRead)
        val confirmation = KeepPreviewConfirmation.forPreview(host, preview)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PICTURE_NOT_CONFIRMED),
            dispatcher.keepPreview(host, observed, null, noReply))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_DEADLINE_UNKNOWN),
            dispatcher.keepPreview(host, observed.copy(lastRead = read.copy(outputsTiming = null)), confirmation, noReply))
        val changedPreview = observed.copy(lastRead = read.copy(
            outputs = outputs("with-active-preview").copy(preview = preview.copy(deadlineSeconds = "9999999999"))))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.CONFIRMATION_MISMATCH),
            dispatcher.keepPreview(host, changedPreview, confirmation, noReply))
        val changedTarget = observed.copy(lastRead = read.copy(
            outputs = outputs("with-active-preview").copy(preview = preview.copy(
                targetMode = preview.targetMode.copy(id = "replacement-target")))))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.CONFIRMATION_MISMATCH),
            dispatcher.keepPreview(host, changedTarget, confirmation, noReply))
        val kept = dispatcher.keepPreview(host, observed, confirmation, JournalMutationSender { _, sent, _ ->
            assertEquals(ApiRoute.DISPLAY_CONFIRM.path, sent.path)
            assertEquals(preview.id, (sent.body!!["preview_id"] as JsonValue.StringValue).value)
            RequestOutcome.Unknown(TransportFailure.IO)
        }) as DisplayDispatchResult.Dispatched
        assertEquals(JournalPhase.UNKNOWN, (kept.result as JournalDispatchResult.Finished).entry.phase)
        // A lost Keep reply remains unresolved; the client must not issue an automatic Revert.
        assertEquals(2, (repository.load() as StateLoadResult.Ready).state.journal.size)
    }

    @Test fun recreatedRepositoryRequiresFreshHostTimingBeforeKeepButCanRevertOwnedPreview() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val beforeDeath = EncryptedStateRepository(store, cipher)
        val preview = requireNotNull(outputs("with-active-preview").preview)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-restored"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        assertTrue(beforeDeath.transact { it.copy(activeHost = host, journal = listOf(entry)) } is StateCommitResult.Saved)

        val restored = EncryptedStateRepository(store, cipher)
        val action = DisplayDispatcher(restored, OperationJournal(restored, clock), clock)
        val confirmation = KeepPreviewConfirmation.forPreview(host, preview)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.OUTPUTS_UNAVAILABLE),
            action.keepPreview(host, StatusPollState(), confirmation, noReply))
        val freshWithoutAnchor = observation(outputs("with-active-preview"), timed = false)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_DEADLINE_UNKNOWN),
            action.keepPreview(host, freshWithoutAnchor, confirmation, noReply))
        val reverted = action.revertPreview(host, freshWithoutAnchor, JournalMutationSender { _, sent, _ ->
            assertEquals("preview", (sent.body!!["source"] as JsonValue.StringValue).value)
            RequestOutcome.NotSent(TransportFailure.IO)
        })
        assertTrue(reverted is DisplayDispatchResult.Dispatched)
        assertEquals(host, (restored.load() as StateLoadResult.Ready).state.activeHost)
    }

    @Test fun oldCredentialObservationCannotRestoreSameHostAfterRePairing() {
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        repository.transact { it.copy(activeHost = rotated) }
        val stalePairing = observation(outputs("with-profile"))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.OBSERVATION_PAIRING_CHANGED),
            dispatcher.restoreSaved(rotated, stalePairing, "profile-fixture0001xxxxxxxxxxxxx", noReply))
    }

    @Test fun exactCachedProfileRemainsRestorableWhenObservationBelongsToOldCredential() {
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        val profile = outputs("with-profile").profiles.single()
        repository.transact { it.copy(activeHost = rotated) }
        assertTrue(repository.recordRecoveryProfiles(rotated, listOf(profile)) is StateCommitResult.Saved)
        val stalePairing = observation(outputs("with-active-preview"))
        var sends = 0
        val result = dispatcher.restoreSaved(rotated, stalePairing, profile.id, JournalMutationSender { _, sent, _ ->
            sends++
            assertEquals(profile.id, (sent.body!!["profile_id"] as JsonValue.StringValue).value)
            RequestOutcome.NotSent(TransportFailure.CONNECT)
        })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(1, sends)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_NOT_OWNED),
            dispatcher.revertPreview(rotated, stalePairing, noReply))
    }

    @Test fun oldCredentialPreviewCannotBeKeptOrRevertedUnderNewPairing() {
        val preview = requireNotNull(outputs("with-active-preview").preview)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("old-preview"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val old = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        repository.transact { it.copy(activeHost = rotated, journal = listOf(old)) }
        val read = observation(outputs("with-active-preview"), timed = true)
            .copy(source = StatusObservationSource.from(rotated))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.CONFIRMATION_MISMATCH),
            dispatcher.keepPreview(rotated, read, KeepPreviewConfirmation.forPreview(host, preview), noReply))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_NOT_OWNED),
            dispatcher.revertPreview(rotated, read, noReply))
    }

    @Test fun recreatedRepositoryCanRequestExactCachedProfileWithoutAStatusRead() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val first = EncryptedStateRepository(store, cipher)
        val profile = outputs("with-profile").profiles.single()
        assertTrue(first.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
        assertTrue(first.recordRecoveryProfiles(host, listOf(profile)) is StateCommitResult.Saved)

        val reopened = EncryptedStateRepository(store, cipher)
        val restored = DisplayDispatcher(reopened, OperationJournal(reopened, clock), clock)
        var sends = 0
        val request = restored.restoreSaved(host, StatusPollState(), profile.id, JournalMutationSender { _, sent, _ ->
            sends++
            val body = requireNotNull(sent.body)
            assertEquals("verified", (body["source"] as JsonValue.StringValue).value)
            assertEquals(profile.id, (body["profile_id"] as JsonValue.StringValue).value)
            RequestOutcome.NotSent(TransportFailure.CONNECT)
        })
        assertTrue(request is DisplayDispatchResult.Dispatched)
        assertEquals(1, sends)
        assertEquals(JournalPhase.FAILED,
            (reopened.load() as StateLoadResult.Ready).state.journal.single().phase)
    }

    @Test fun previewCardRebuildsFromRecreatedJournalAndHostPreviewWithoutModeListIdentity() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val first = EncryptedStateRepository(store, cipher)
        val preview = requireNotNull(outputs("with-active-preview").preview)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-rebuilt"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        first.transact { it.copy(activeHost = host, journal = listOf(entry)) }
        val reopened = EncryptedStateRepository(store, cipher)
        val state = (reopened.load() as StateLoadResult.Ready).state
        val observed = observation(outputs("with-active-preview").copy(outputs = emptyList()), timed = true)
        val card = DisplayRecoveryPresentation.project(host, state, observed, 2_000_000_000L) as PreviewPresentation.Active
        assertEquals(preview.targetMode, card.preview.targetMode)
        assertTrue(card.ownedByThisPairing)
        assertTrue(card.keepAvailable)
        assertTrue(card.revertAvailable)

        val lost = DisplayRecoveryPresentation.project(host, state, observed.copy(unreachable = true),
            3_000_000_000L) as PreviewPresentation.Active
        assertTrue(lost.connectionLost)
        assertFalse(lost.keepAvailable)
        val expired = DisplayRecoveryPresentation.project(host, state, observed.copy(unreachable = true),
            20_000_000_000L) as PreviewPresentation.Active
        assertTrue(expired.checkingRestoration)
        assertFalse(expired.keepAvailable)
    }

    @Test fun unresolvedKeepRemainsCheckingAfterPreviewDisappears() {
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_CONFIRM.path,
            jsonObject("request_id" to JsonValue.StringValue("keep-unknown"),
                "preview_id" to JsonValue.StringValue("op-fixture0001xxxxxxxxxxxxx"),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 100)
            .copy(phase = JournalPhase.UNKNOWN)
        val state = PersistentState(activeHost = host, journal = listOf(entry))
        assertEquals(PreviewPresentation.Pending(PendingDisplayResult.CHECKING_SAVE, 1),
            DisplayRecoveryPresentation.project(host, state, observation(), 2_000_000_000L))
    }

    @Test fun unresolvedSaveCurrentRemainsVisibleWithItsOwnCheckingState() {
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_SAVE_CURRENT.path,
            jsonObject("request_id" to JsonValue.StringValue("save-current-unknown"),
                "output_id" to JsonValue.StringValue("1"),
                "generation" to JsonValue.NumberValue("4"),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 100)
            .copy(phase = JournalPhase.UNKNOWN)
        val state = PersistentState(activeHost = host, journal = listOf(entry))
        assertEquals(PreviewPresentation.Pending(PendingDisplayResult.CHECKING_SAVE_CURRENT, 1),
            DisplayRecoveryPresentation.project(host, state, StatusPollState(), 2_000_000_000L))
        val legacy = entry.copy(originalPairedAtMillis = null, originalBearerSha256 = null)
        assertEquals(PreviewPresentation.Pending(PendingDisplayResult.REVIEW_LEGACY_OPERATION, 1),
            DisplayRecoveryPresentation.project(host, state.copy(journal = listOf(legacy)),
                StatusPollState(), 2_000_000_000L))
    }

    @Test fun migratedUnresolvedDisplayOperationStaysVisibleForReview() {
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("legacy-preview"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val legacy = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 100)
            .copy(originalPairedAtMillis = null, originalBearerSha256 = null, phase = JournalPhase.UNKNOWN)
        val state = PersistentState(activeHost = host, journal = listOf(legacy))
        assertEquals(PreviewPresentation.Pending(PendingDisplayResult.REVIEW_LEGACY_OPERATION, 1),
            DisplayRecoveryPresentation.project(host, state, StatusPollState(), 2_000_000_000L))

        val preview = requireNotNull(outputs("with-active-preview").preview)
        val newRequest = request.copy(body = jsonObject(
            "request_id" to JsonValue.StringValue("new-preview"),
            "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
            "generation" to JsonValue.NumberValue("4")))
        val owned = JournalEntry.create(2, host, newRequest, BoundedJson.encode(newRequest.body!!), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        val card = DisplayRecoveryPresentation.project(host, state.copy(journal = listOf(legacy, owned)),
            observation(outputs("with-active-preview"), timed = true), 2_000_000_000L) as PreviewPresentation.Active
        assertTrue(card.unprovenOperationNeedsReview)
        assertFalse(card.keepAvailable)
        assertFalse(card.revertAvailable)
    }

    private fun observation(
        inventory: VersionedPayload.DisplayOutputs = outputs("available"), timed: Boolean = false,
    ): StatusPollState = StatusPollState(
        lastRead = PairedStatusRead(status(), inventory, null,
            outputsTiming = if (timed) ResponseTiming(1_000_000_000L, 1_200_000_000L, "Sun, 13 Sep 2026 12:00:00 GMT") else null),
        lastSuccessMonotonicNanos = 1_000_000_000L,
        source = StatusObservationSource.from(host),
    )

    private fun status() = fixture(ApiRoute.STATUS, "status/sunshine-stopped") as VersionedPayload.Status
    private fun outputs(name: String) = fixture(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/$name") as VersionedPayload.DisplayOutputs

    private fun fixture(route: ApiRoute, name: String): VersionedPayload {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }
}
