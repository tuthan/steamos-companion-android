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

    @Test fun orderSaveSendsOnlyHostIssuedKeysAndGenerationWithoutRestart() {
        repository.transact { it.copy(activeHost = host) }
        val observed = observation(order = order("saved-order"))
        val keys = listOf("drm:card0:DP-1", "drm:card0:eDP-1", "drm:card0:HDMI-A-1")
        var sent: ApiRequest? = null
        val result = dispatcher.saveOrder(host, observed, keys, 1, JournalMutationSender { _, request, _ ->
            sent = request
            RequestOutcome.NotSent(TransportFailure.IO)
        })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(ApiRoute.DISPLAY_ORDER_SET.path, sent!!.path)
        val body = requireNotNull(sent!!.body)
        assertEquals(JsonValue.BooleanValue(false), body["restart"])
        assertEquals(JsonValue.NumberValue("1"), body["generation"])
        assertEquals(keys, (body["output_keys"] as JsonValue.ArrayValue).values.map { (it as JsonValue.StringValue).value })
    }

    @Test fun orderSaveRefusesDisconnectedMissingDuplicateAndStaleInput() {
        repository.transact { it.copy(activeHost = host) }
        val observed = observation(order = order("saved-order"))
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        val connected = listOf("drm:card0:DP-1", "drm:card0:HDMI-A-1", "drm:card0:eDP-1")
        // HDMI-A-2 is disconnected; a subset, a duplicate and a stale generation are all refused locally.
        for (keys in listOf(connected + "drm:card0:HDMI-A-2", connected.drop(1), connected + connected.first(), emptyList())) {
            assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.STALE_SELECTION),
                dispatcher.saveOrder(host, observed, keys, 1, sender))
        }
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.STALE_SELECTION),
            dispatcher.saveOrder(host, observed, connected, 2, sender))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.ORDER_UNAVAILABLE),
            dispatcher.saveOrder(host, observation(), connected, 1, sender))
        assertTrue((repository.load() as StateLoadResult.Ready).state.journal.isEmpty())
    }

    @Test fun orderChangesAreBlockedWhenTheHostIsAmbiguousOrUnsupported() {
        repository.transact { it.copy(activeHost = host) }
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        for (name in listOf("ambiguous-connector", "unsupported-host", "no-physical-inventory")) {
            val observed = observation(order = order(name))
            assertEquals(name, DisplayDispatchResult.Blocked(DisplayBlock.ORDER_NOT_EDITABLE),
                dispatcher.saveOrder(host, observed, listOf("drm:card0:DP-1"), 1, sender))
        }
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.ORDER_NOT_EDITABLE),
            dispatcher.resetOrder(host, observation(order = order("unsupported-host")), sender))
    }

    @Test fun restartNeedsExplicitBoundConfirmationAndCarriesRestartFlag() {
        repository.transact { it.copy(activeHost = host) }
        val observed = observation(order = order("saved-order"))
        val keys = listOf("drm:card0:HDMI-A-1", "drm:card0:DP-1", "drm:card0:eDP-1")
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.RESTART_NOT_CONFIRMED),
            dispatcher.saveOrderAndRestart(host, observed, null, sender))
        val other = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.CONFIRMATION_MISMATCH),
            dispatcher.saveOrderAndRestart(host, observed,
                OrderRestartConfirmation(StatusObservationSource.from(other), keys, 1), sender))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.STALE_SELECTION),
            dispatcher.saveOrderAndRestart(host, observed,
                OrderRestartConfirmation(StatusObservationSource.from(host), keys, 9), sender))
        var sent: ApiRequest? = null
        val result = dispatcher.saveOrderAndRestart(host, observed,
            OrderRestartConfirmation(StatusObservationSource.from(host), keys, 1),
            JournalMutationSender { _, request, _ -> sent = request; RequestOutcome.NotSent(TransportFailure.IO) })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(JsonValue.BooleanValue(true), requireNotNull(sent!!.body)["restart"])
    }

    @Test fun anUnknownRestartResultIsNeverReplayedAndBlocksEveryOtherDisplayChange() {
        repository.transact { it.copy(activeHost = host) }
        val observed = observation(order = order("saved-order"))
        val keys = listOf("drm:card0:HDMI-A-1", "drm:card0:DP-1", "drm:card0:eDP-1")
        val confirmation = OrderRestartConfirmation(StatusObservationSource.from(host), keys, 1)
        val first = dispatcher.saveOrderAndRestart(host, observed, confirmation,
            JournalMutationSender { _, _, _ -> RequestOutcome.Unknown(TransportFailure.IO) }) as DisplayDispatchResult.Dispatched
        assertEquals(JournalPhase.UNKNOWN, (first.result as JournalDispatchResult.Finished).entry.phase)
        val sender = JournalMutationSender { _, _, _ -> error("must not resend") }
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.OPERATION_UNRESOLVED),
            dispatcher.saveOrderAndRestart(host, observed, confirmation, sender))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.OPERATION_UNRESOLVED),
            dispatcher.saveOrder(host, observed, keys, 1, sender))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.OPERATION_UNRESOLVED),
            dispatcher.resetOrder(host, observed, sender))
    }

    @Test fun orderChangesWaitForAnActivePreview() {
        repository.transact { it.copy(activeHost = host) }
        val observed = observation(outputs("with-active-preview"), order = order("saved-order"))
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_ACTIVE),
            dispatcher.saveOrder(host, observed, listOf("drm:card0:DP-1", "drm:card0:HDMI-A-1", "drm:card0:eDP-1"), 1, sender))
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.PREVIEW_ACTIVE), dispatcher.resetOrder(host, observed, sender))
    }

    @Test fun automaticResetIsAFixedRequestOnlyWhenSomethingIsSavedOrAvailable() {
        repository.transact { it.copy(activeHost = host) }
        var sent: ApiRequest? = null
        val result = dispatcher.resetOrder(host, observation(order = order("saved-order")),
            JournalMutationSender { _, request, _ -> sent = request; RequestOutcome.NotSent(TransportFailure.IO) })
        assertTrue(result is DisplayDispatchResult.Dispatched)
        assertEquals(ApiRoute.DISPLAY_ORDER_AUTOMATIC.path, sent!!.path)
        assertEquals(setOf("request_id"), requireNotNull(sent!!.body).fields.keys)
        assertEquals(DisplayDispatchResult.Blocked(DisplayBlock.ORDER_UNAVAILABLE), dispatcher.resetOrder(host, observation(), noReply))
    }

    @Test fun orderOperationsProduceCardsWithReadbackAndSavedRestartFailureCode() {
        repository.transact { it.copy(activeHost = host) }
        val saved = order("saved-order")
        val keys = listOf("drm:card0:HDMI-A-1", "drm:card0:DP-1", "drm:card0:eDP-1")
        val sender = JournalMutationSender { _, request, _ ->
            val body = requireNotNull(request.body)
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
                operation = io.github.tuthan.steamoscompanion.core.protocol.OperationSummary(
                    "op-order-1xxxxxxxxxxxxxxxxxxx", (body["request_id"] as JsonValue.StringValue).value,
                    io.github.tuthan.steamoscompanion.core.protocol.WireValue.Known(
                        io.github.tuthan.steamoscompanion.core.protocol.OperationState.FAILED),
                    "display.order", "2026-09-13T12:00:00Z", "2026-09-13T12:00:00Z",
                    "gamescope_output_configured_restart_failed", "saved but restart failed"),
                body = jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null)
        }
        val confirmation = OrderRestartConfirmation(StatusObservationSource.from(host), keys, 1)
        val dispatched = dispatcher.saveOrderAndRestart(host, observation(order = saved), confirmation, sender)
            as DisplayDispatchResult.Dispatched
        val entry = (dispatched.result as JournalDispatchResult.Finished).entry
        assertEquals(JournalPhase.FAILED, entry.phase)
        assertEquals(JournalRejectionCode.ORDER_SAVED_RESTART_FAILED, entry.rejectionCode)
        val state = (repository.load() as StateLoadResult.Ready).state
        val card = requireNotNull(DisplayOperationPresentation.latest(host, state, observation(order = saved), 1_500_000_000L))
        assertEquals(DisplayOperationKind.ORDER_SAVE_AND_RESTART, card.kind)
        assertEquals(keys, card.requestedKeys)
        // The host's saved order (HDMI-A-1, DP-1) is not the requested three-key order.
        assertEquals(DisplayObservation.ORDER_SAVED_DIFFERS_FROM_REQUEST, card.observed)
    }

    @Test fun aKeptPreviewIsNoLongerUnresolvedAndDoesNotBlockTheNextPreview() {
        repository.transact { it.copy(activeHost = host) }
        val previewId = "op-fixture0001xxxxxxxxxxxxx"
        seedOpenPreview(previewId)
        val confirm = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_CONFIRM.path,
            jsonObject("request_id" to JsonValue.StringValue("keep-1"), "preview_id" to JsonValue.StringValue(previewId),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        val kept = journal.dispatchForHost(host, confirm, operationSender("display.confirm", "succeeded", "visible_picture_confirmed"))
            as JournalDispatchResult.Finished
        assertEquals(JournalPhase.SUCCEEDED, kept.entry.phase)

        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(JournalPhase.SUCCEEDED, state.journal.first { it.path == ApiRoute.DISPLAY_PREVIEW.path }.phase)
        assertFalse(OperationLanePresentation.hasUnresolved(host, state))
        // The host preview is gone after Keep; nothing may claim a pending result.
        assertEquals(PreviewPresentation.None,
            DisplayRecoveryPresentation.project(host, state, observation(), 1_500_000_000L))
        var sends = 0
        val next = dispatcher.preview(host, observation(), DisplaySelection("1", 4, "2"), JournalMutationSender { _, _, _ ->
            sends++; RequestOutcome.NotSent(TransportFailure.IO)
        })
        assertTrue(next is DisplayDispatchResult.Dispatched)
        assertEquals(1, sends)
    }

    @Test fun aRevertedPreviewIsClosedOnceTheHostReportsTheRestoreSucceeded() {
        repository.transact { it.copy(activeHost = host) }
        seedOpenPreview("op-fixture0001xxxxxxxxxxxxx")
        val revert = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_RESTORE.path,
            jsonObject("request_id" to JsonValue.StringValue("revert-1"), "source" to JsonValue.StringValue("preview")),
            host.bearerToken)
        // 202 accepted first; the preview stays open until the restore itself is reconciled as succeeded.
        val accepted = journal.dispatchForHost(host, revert, operationSender("display.restore", "accepted", null))
            as JournalDispatchResult.Finished
        assertTrue((repository.load() as StateLoadResult.Ready).state.journal
            .first { it.path == ApiRoute.DISPLAY_PREVIEW.path }.phase.unresolved)
        val active = requireNotNull(accepted.entry.operationId)
        journal.applyOperationSnapshot(host, accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint,
            host.certificatePin, active, "revert-1", "display.restore",
            io.github.tuthan.steamoscompanion.core.protocol.WireValue.Known(
                io.github.tuthan.steamoscompanion.core.protocol.OperationState.SUCCEEDED), "restored_and_read_back")
        val state = (repository.load() as StateLoadResult.Ready).state
        assertFalse(OperationLanePresentation.hasUnresolved(host, state))
    }

    @Test fun onlyTheMatchingPreviewOfTheSameCredentialIsClosed() {
        repository.transact { it.copy(activeHost = host) }
        seedOpenPreview("op-fixture0001xxxxxxxxxxxxx")
        val other = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_CONFIRM.path,
            jsonObject("request_id" to JsonValue.StringValue("keep-other"),
                "preview_id" to JsonValue.StringValue("op-some-other-previewxxxxx"),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        journal.dispatchForHost(host, other, operationSender("display.confirm", "succeeded", "visible_picture_confirmed"))
        assertTrue(OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state))
    }

    @Test fun startupRecoveryClosesAPreviewThatOlderBuildsLeftOpenAfterAKeep() {
        repository.transact { it.copy(activeHost = host) }
        val previewId = "op-fixture0001xxxxxxxxxxxxx"
        seedOpenPreview(previewId)
        val confirm = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_CONFIRM.path,
            jsonObject("request_id" to JsonValue.StringValue("keep-legacy"), "preview_id" to JsonValue.StringValue(previewId),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        val closed = JournalEntry.create(2, host, confirm, BoundedJson.encode(requireNotNull(confirm.body)), 100)
            .copy(phase = JournalPhase.SUCCEEDED, operationId = "op-confirm-legacyxxxxxxxxxxx")
        repository.transact { state -> state.copy(journal = state.journal + closed) }
        assertTrue(OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state))
        assertEquals(JournalUpdateResult.Saved, journal.recoverPreparedEntries())
        assertFalse(OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state))
    }

    private fun seedOpenPreview(operationId: String) {
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-seed"), "output_id" to JsonValue.StringValue("1"),
                "mode_id" to JsonValue.StringValue("2"), "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = JournalPhase.OBSERVED_RETURN, operationId = operationId)
        repository.transact { it.copy(journal = listOf(entry)) }
    }

    private fun operationSender(kind: String, state: String, outcome: String?) = JournalMutationSender { _, request, _ ->
        val body = requireNotNull(request.body)
        RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
            operation = io.github.tuthan.steamoscompanion.core.protocol.OperationSummary(
                "op-$kind-fixturexxxxxxxxxxx", (body["request_id"] as JsonValue.StringValue).value,
                io.github.tuthan.steamoscompanion.core.protocol.WireValue.Known(
                    io.github.tuthan.steamoscompanion.core.protocol.OperationState.valueOf(state.uppercase())),
                kind, "2026-09-13T12:00:00Z", "2026-09-13T12:00:00Z", outcome, null),
            body = jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null)
    }

    private fun observation(
        inventory: VersionedPayload.DisplayOutputs = outputs("available"), timed: Boolean = false,
        order: VersionedPayload.DisplayOrder? = null,
    ): StatusPollState = StatusPollState(
        lastRead = PairedStatusRead(status(), inventory, order,
            outputsTiming = if (timed) ResponseTiming(1_000_000_000L, 1_200_000_000L, "Sun, 13 Sep 2026 12:00:00 GMT") else null),
        lastSuccessMonotonicNanos = 1_000_000_000L,
        source = StatusObservationSource.from(host),
    )

    private fun order(name: String) = fixture(ApiRoute.DISPLAY_ORDER, "display-order-get/$name") as VersionedPayload.DisplayOrder

    private fun requestBody(request: ApiRequest) = BoundedJson.encode(requireNotNull(request.body)).decodeToString()

    private fun status() = fixture(ApiRoute.STATUS, "status/sunshine-stopped") as VersionedPayload.Status
    private fun outputs(name: String) = fixture(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/$name") as VersionedPayload.DisplayOutputs

    private fun fixture(route: ApiRoute, name: String): VersionedPayload {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }
}
