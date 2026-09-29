package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.OperationState
import io.github.tuthan.steamoscompanion.core.protocol.OperationSummary
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationJournalTest {
    @Test
    fun secondMutationCannotPassTheAtomicLaneWhileFirstRequestIsPrepared() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        assertTrue(repository.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
        var secondSent = false
        val first = journal.dispatchForHost(host, testPowerRequest("first-action")) { _, _, _ ->
            val prepared = (repository.load() as StateLoadResult.Ready).state.journal.single()
            assertEquals(JournalPhase.PREPARED, prepared.phase)
            val second = journal.dispatchForHost(host, testPowerRequest("second-action")) { _, _, _ ->
                secondSent = true
                RequestOutcome.NotSent(TransportFailure.IO)
            }
            assertEquals(JournalDispatchResult.NotDispatched(StateInvariantCode.OPERATION_UNRESOLVED), second)
            RequestOutcome.NotSent(TransportFailure.IO)
        }
        assertTrue(first is JournalDispatchResult.Finished)
        assertFalse(secondSent)
        assertEquals(1, (repository.load() as StateLoadResult.Ready).state.journal.size)
    }

    @Test
    fun oneOwnedPreviewAllowsItsMatchingKeepButBlocksUnrelatedMutation() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        val previewRequest = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-1"),
                "output_id" to JsonValue.StringValue("1"), "mode_id" to JsonValue.StringValue("2"),
                "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val preview = JournalEntry.create(1, host, previewRequest, BoundedJson.encode(previewRequest.body!!), 100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = "op-preview-1")
        assertTrue(repository.transact { it.copy(activeHost = host, journal = listOf(preview)) } is StateCommitResult.Saved)
        val unrelated = journal.dispatchForHost(host, testPowerRequest("power-during-preview")) { _, _, _ ->
            error("must not send")
        }
        assertEquals(JournalDispatchResult.NotDispatched(StateInvariantCode.OPERATION_UNRESOLVED), unrelated)
        val keep = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_CONFIRM.path,
            jsonObject("request_id" to JsonValue.StringValue("keep-1"),
                "preview_id" to JsonValue.StringValue("op-preview-1"),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        val wrongKeep = keep.copy(body = jsonObject("request_id" to JsonValue.StringValue("keep-wrong"),
            "preview_id" to JsonValue.StringValue("op-other-preview"),
            "visible" to JsonValue.BooleanValue(true)))
        assertEquals(JournalDispatchResult.NotDispatched(StateInvariantCode.OPERATION_UNRESOLVED),
            journal.dispatchForHost(host, wrongKeep) { _, _, _ -> error("must not send") })
        val kept = journal.dispatchForHost(host, keep) { _, _, _ -> RequestOutcome.NotSent(TransportFailure.IO) }
        assertTrue(kept is JournalDispatchResult.Finished)
        assertEquals(ApiRoute.DISPLAY_CONFIRM.path, (kept as JournalDispatchResult.Finished).entry.path)
        val revert = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_RESTORE.path,
            jsonObject("request_id" to JsonValue.StringValue("revert-1"),
                "source" to JsonValue.StringValue("preview")), host.bearerToken)
        val reverted = journal.dispatchForHost(host, revert) { _, _, _ -> RequestOutcome.NotSent(TransportFailure.IO) }
        assertTrue(reverted is JournalDispatchResult.Finished)
    }

    @Test
    fun startupRecoveryWithNoPreparedEntryDoesNotCreateOrRewriteEncryptedState() {
        val (store, _, repository, journal) = fixture()
        assertEquals(JournalUpdateResult.Saved, journal.recoverPreparedEntries())
        assertNull(store.rawBytes())

        assertTrue(repository.transact { it.copy(activeHost = testActiveHost()) } is StateCommitResult.Saved)
        val saved = requireNotNull(store.rawBytes())
        assertEquals(JournalUpdateResult.Saved, journal.recoverPreparedEntries())
        assertArrayEquals(saved, store.rawBytes())
    }

    @Test
    fun staleMutationBearerCannotBePreparedForRefreshedPairing() {
        val (_, _, repository, journal) = fixture()
        val refreshed = testActiveHost().copy(bearerToken = "refreshed-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        assertTrue(repository.transact { it.copy(activeHost = refreshed) } is StateCommitResult.Saved)
        val requestFromOldCredential = testPowerRequest("stale-bearer")
        var sends = 0
        val sender = JournalMutationSender { _, _, _ ->
            sends++
            RequestOutcome.NotSent(TransportFailure.IO)
        }

        for (result in listOf(
            journal.dispatchForHost(refreshed, requestFromOldCredential, sender),
            journal.dispatch(requestFromOldCredential, sender),
        )) {
            assertEquals(StateInvariantCode.INVALID_IDENTITY,
                (result as JournalDispatchResult.NotDispatched).code)
        }
        assertEquals(0, sends)
        assertTrue((repository.load() as StateLoadResult.Ready).state.journal.isEmpty())
    }

    @Test
    fun mismatchedPowerOperationKindRemainsUnresolved() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        repository.transact { it.copy(activeHost = host) }
        val result = journal.dispatchForHost(host, testPowerRequest("power-kind")) { _, _, _ ->
            RequestOutcome.Reply(
                ApiReply.Versioned(1, VersionedPayload.Mutation(
                    OperationSummary("op-wrong-kind", "power-kind", WireValue.Known(OperationState.SUCCEEDED),
                        "power.shutdown", "created", "updated", "shutdown", null),
                    jsonObject("protocol_version" to JsonValue.NumberValue("1")),
                )), 202, null,
            )
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.UNKNOWN, result.entry.phase)
        assertEquals(host, (repository.load() as StateLoadResult.Ready).state.activeHost)
    }

    @Test
    fun mismatchedRevokeOperationCannotClearThePairedCredential() {
        for ((returnedRequestId, returnedKind) in listOf(
            "some-other-request" to "pair.revoke-self",
            "revoke-exact" to "power.suspend",
        )) {
            val (_, _, repository, journal) = fixture()
            val host = testActiveHost()
            assertTrue(repository.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
            val request = io.github.tuthan.steamoscompanion.core.protocol.ApiRequest(
                io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST,
                "/v1/pair/revoke-self",
                jsonObject("request_id" to JsonValue.StringValue("revoke-exact")),
                host.bearerToken,
            )
            val result = journal.dispatch(request) { _, _, _ ->
                RequestOutcome.Reply(
                    ApiReply.Versioned(1, VersionedPayload.Mutation(
                        OperationSummary("op-mismatched", returnedRequestId, WireValue.Known(OperationState.SUCCEEDED),
                            returnedKind, "created", "updated", "revoked", null),
                        jsonObject("protocol_version" to JsonValue.NumberValue("1")),
                    )), 202, null,
                )
            } as JournalDispatchResult.Finished
            assertEquals(JournalPhase.UNKNOWN, result.entry.phase)
            assertEquals(host, (repository.load() as StateLoadResult.Ready).state.activeHost)
        }
    }

    @Test
    fun commitsPreparedExactBodyBeforeSenderAndPersistsReturnedOperationId() {
        val (store, _, repository, journal, clock) = fixture()
        assertTrue(repository.transact { it.copy(activeHost = testActiveHost()) } is StateCommitResult.Saved)
        val request = testPowerRequest("power-prepared")
        val expectedBody = io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(request.body!!)

        val result = journal.dispatch(request) { host, sentRequest, exactBody ->
            assertEquals(testActiveHost(), host)
            assertEquals(request, sentRequest)
            assertArrayEquals(expectedBody, exactBody)
            val saved = (repository.load() as StateLoadResult.Ready).state.journal.single()
            assertEquals(JournalPhase.PREPARED, saved.phase)
            assertArrayEquals(expectedBody, saved.bodyBytes())
            accepted("op-accepted", requestId = "power-prepared")
        }

        val finished = result as JournalDispatchResult.Finished
        assertEquals(JournalPhase.ACCEPTED, finished.entry.phase)
        assertEquals("op-accepted", finished.entry.operationId)
        val durable = (repository.load() as StateLoadResult.Ready).state.journal.single()
        assertEquals(finished.entry.bodySha256, durable.bodySha256)
        assertArrayEquals(expectedBody, durable.bodyBytes())
        assertEquals(clock.wallTimeMillis(), durable.updatedAtMillis)
        assertTrue(store.rawBytes()!!.isNotEmpty()) // durable ciphertext is present
    }

    @Test
    fun lastObservedBootIdIsPersistedAndCopiedIntoThePreparedJournalEntry() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        assertTrue(repository.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
        val observed = repository.recordObservedBootId(host, "00000000-0000-4000-8000-000000000001") as StateCommitResult.Saved
        assertEquals("00000000-0000-4000-8000-000000000001", observed.state.activeHost!!.lastObservedBootId)

        val dispatched = journal.dispatch(testPowerRequest("boot-id-capture")) { _, _, _ ->
            val prepared = (repository.load() as StateLoadResult.Ready).state.journal.single()
            assertEquals("00000000-0000-4000-8000-000000000001", prepared.hostBootId)
            RequestOutcome.NotSent(TransportFailure.IO)
        } as JournalDispatchResult.Finished
        assertEquals("00000000-0000-4000-8000-000000000001", dispatched.entry.hostBootId)
    }

    @Test
    fun aSizeOverflowWithOnlyUnresolvedEntriesIsRejectedAsHistoryFullBeforeDispatch() {
        val (_, _, repository, journal, clock) = fixture()
        val host = testActiveHost()
        val entries = (1..PersistentStateLimits.MAX_JOURNAL_ENTRIES).map { index ->
            val request = largeDisplayOrderRequest(host, "large-$index")
            JournalEntry.create(
                localGeneration = index.toLong(),
                host = host,
                request = request,
                exactBodyBytes = io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(request.body!!),
                nowMillis = clock.wallTimeMillis(),
            ).copy(phase = JournalPhase.UNKNOWN, resultSummary = "x".repeat(1_024))
        }
        val newRequest = testPowerRequest("size-boundary-new")
        fun newEntry(generation: Long) = JournalEntry.create(
            localGeneration = generation,
            host = host,
            request = newRequest,
            exactBodyBytes = io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(newRequest.body!!),
            nowMillis = clock.wallTimeMillis(),
        )
        val fittingEntries = (1 until entries.size).firstNotNullOfOrNull { count ->
            val base = entries.take(count).map { it.copy(resultSummary = "") }
            val current = PersistentState(activeHost = host, journal = base)
            val currentSize = runCatching { PersistentStateCodec.encode(current).size }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            val nextSize = runCatching {
                PersistentStateCodec.encode(current.copy(journal = base + newEntry(count.toLong() + 1))).size
            }.getOrNull() ?: PersistentStateLimits.MAX_PLAINTEXT_BYTES + 1
            val padding = (PersistentStateLimits.MAX_PLAINTEXT_BYTES - nextSize + 1).coerceAtLeast(0)
            if (padding > count * 1_024 || currentSize + padding > PersistentStateLimits.MAX_PLAINTEXT_BYTES) {
                return@firstNotNullOfOrNull null
            }
            var remaining = padding
            val adjusted = base.map { entry ->
                val size = minOf(remaining, 1_024)
                remaining -= size
                entry.copy(resultSummary = "x".repeat(size))
            }
            val candidate = PersistentState(activeHost = host, journal = adjusted)
            if (runCatching { PersistentStateCodec.encode(candidate) }.isSuccess &&
                runCatching { PersistentStateCodec.encode(candidate.copy(journal = adjusted + newEntry(count.toLong() + 1))) }.isFailure
            ) adjusted else null
        }
        assertTrue("fixture needs a state below 64 KiB whose next entry crosses the cap", fittingEntries != null)
        val initial = PersistentState(activeHost = host, journal = requireNotNull(fittingEntries))
        assertTrue(repository.transact { initial } is StateCommitResult.Saved)

        var sent = false
        val result = journal.dispatch(newRequest) { _, _, _ ->
            sent = true
            RequestOutcome.NotSent(TransportFailure.IO)
        }
        assertEquals(StateInvariantCode.HISTORY_FULL, (result as JournalDispatchResult.NotDispatched).code)
        assertFalse(sent)
    }

    @Test
    fun preDispatchStorageFailureNeverCallsSender() {
        val (store, _, repository, journal) = fixture()
        repository.transact { it.copy(activeHost = testActiveHost()) }
        store.failNextWrite = true
        var called = false

        val result = journal.dispatch(testPowerRequest("disk-full")) { _, _, _ ->
            called = true
            RequestOutcome.NotSent(TransportFailure.IO)
        }

        assertEquals(JournalDispatchResult.PersistenceFailed(StatePersistenceFailure.IO), result)
        assertFalse(called)
        assertTrue((repository.load() as StateLoadResult.Ready).state.journal.isEmpty())
    }

    @Test
    fun failedOperationIdCommitStaysUnconfirmedAndPreparedRecoveryNeverResends() {
        val (store, _, repository, journal) = fixture()
        repository.transact { it.copy(activeHost = testActiveHost()) }
        val request = testPowerRequest("op-id-write-failed")
        var calls = 0
        val result = journal.dispatch(request) { _, _, _ ->
            calls++
            store.failNextWrite = true // the PREPARED write succeeded; saving the response will fail.
            accepted("op-lost-locally", requestId = "op-id-write-failed")
        }

        assertTrue(result is JournalDispatchResult.ResultNotConfirmed)
        assertEquals("op-lost-locally", (result as JournalDispatchResult.ResultNotConfirmed).operationId)
        val persisted = (repository.load() as StateLoadResult.Ready).state.journal.single()
        assertEquals(JournalPhase.PREPARED, persisted.phase)
        assertNull(persisted.operationId)
        assertEquals(JournalUpdateResult.Saved, journal.recoverPreparedEntries())
        assertEquals(JournalPhase.UNKNOWN, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)

        val duplicate = journal.dispatch(request) { _, _, _ ->
            calls++
            RequestOutcome.NotSent(TransportFailure.IO)
        }
        assertEquals(StateInvariantCode.DUPLICATE_REQUEST_ID, (duplicate as JournalDispatchResult.NotDispatched).code)
        assertEquals(1, calls)
    }

    @Test
    fun failedReplyCommitDoesNotExposeMismatchedOperationId() {
        for ((returnedRequestId, returnedKind) in listOf(
            "another-request" to "power.suspend",
            "power-id-mismatch" to "power.shutdown",
        )) {
            val (store, _, repository, journal) = fixture()
            assertTrue(repository.transact { it.copy(activeHost = testActiveHost()) } is StateCommitResult.Saved)
            val result = journal.dispatch(testPowerRequest("power-id-mismatch")) { _, _, _ ->
                store.failNextWrite = true
                RequestOutcome.Reply(
                    ApiReply.Versioned(1, VersionedPayload.Mutation(
                        OperationSummary("op-untrusted", returnedRequestId,
                            WireValue.Known(OperationState.ACCEPTED), returnedKind,
                            "created", "updated", null, null),
                        jsonObject("protocol_version" to JsonValue.NumberValue("1")),
                    )), 202, null,
                )
            } as JournalDispatchResult.ResultNotConfirmed
            assertNull(result.operationId)
            assertNull((repository.load() as StateLoadResult.Ready).state.journal.single().operationId)
        }
    }

    @Test
    fun knownRejectionAndDefinitelyNotSentBecomeTerminalFailures() {
        val (_, _, repository, journal) = fixture()
        repository.transact { it.copy(activeHost = testActiveHost()) }
        val rejected = journal.dispatch(testPowerRequest("known-rejection")) { _, _, _ ->
            RequestOutcome.Rejected(ApiReply.Rejected(409, "mutation_conflict", "busy"), null)
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.FAILED, rejected.entry.phase)
        assertTrue(rejected.entry.resultSummary!!.contains("409"))
        assertEquals(JournalRejectionCode.MUTATION_CONFLICT, rejected.entry.rejectionCode)

        val notSent = journal.dispatch(testPowerRequest("known-not-sent")) { _, _, _ ->
            RequestOutcome.NotSent(TransportFailure.PIN)
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.FAILED, notSent.entry.phase)
        assertTrue(notSent.entry.resultSummary!!.startsWith("Not sent:"))
    }

    @Test
    fun displayRejectionReasonsSurviveReloadAndRemainBoundToThePairing() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        repository.transact { it.copy(activeHost = host) }
        val replies = listOf(
            Triple(404, "not_found", JournalRejectionCode.SAVE_CURRENT_UNSUPPORTED),
            Triple(409, "stale_target", JournalRejectionCode.STALE_TARGET),
            Triple(503, "bridge_unavailable", JournalRejectionCode.BRIDGE_UNAVAILABLE),
            Triple(409, "readback_not_confirmed", JournalRejectionCode.READBACK_NOT_CONFIRMED),
        )
        replies.forEachIndexed { index, (status, error, expected) ->
            val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_SAVE_CURRENT.path,
                jsonObject("request_id" to JsonValue.StringValue("save-rejected-$index"),
                    "output_id" to JsonValue.StringValue("HDMI-A-1"),
                    "generation" to JsonValue.NumberValue("4"),
                    "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
            val result = journal.dispatchForHost(host, request) { _, _, _ ->
                RequestOutcome.Rejected(ApiReply.Rejected(status, error, "host detail"), null)
            } as JournalDispatchResult.Finished
            assertEquals(expected, result.entry.rejectionCode)
            val restored = (repository.load() as StateLoadResult.Ready).state
            val failure = DisplayRecoveryPresentation.latestFailure(host, restored)!!
            assertEquals(expected, failure.reason)
            assertEquals(ApiRoute.DISPLAY_SAVE_CURRENT, failure.route)
            assertNull(DisplayRecoveryPresentation.latestFailure(host.copy(bearerToken = "other-token"), restored))
        }
    }

    @Test
    fun displayOrderHostRefusalsMapToTypedReasonsThatSurviveReload() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        repository.transact { it.copy(activeHost = host) }
        val replies = listOf(
            "stale_display_inventory" to JournalRejectionCode.STALE_TARGET,
            "stale_display_target" to JournalRejectionCode.STALE_TARGET,
            "ambiguous_display_identity" to JournalRejectionCode.AMBIGUOUS_DISPLAY,
            "gamescope_output_unsupported" to JournalRejectionCode.ORDER_UNSUPPORTED,
            "gamescope_output_update_failed" to JournalRejectionCode.ORDER_UPDATE_FAILED,
            "mutation_conflict" to JournalRejectionCode.MUTATION_CONFLICT,
        )
        replies.forEachIndexed { index, (error, expected) ->
            val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_ORDER_SET.path,
                jsonObject("request_id" to JsonValue.StringValue("order-rejected-$index"),
                    "output_keys" to JsonValue.ArrayValue(listOf(JsonValue.StringValue("drm:card0:DP-1"))),
                    "generation" to JsonValue.NumberValue("1"),
                    "restart" to JsonValue.BooleanValue(false)), host.bearerToken)
            val result = journal.dispatchForHost(host, request) { _, _, _ ->
                RequestOutcome.Rejected(ApiReply.Rejected(409, error, "host detail"), null)
            } as JournalDispatchResult.Finished
            assertEquals(expected, result.entry.rejectionCode)
            val reloaded = (repository.load() as StateLoadResult.Ready).state.journal
                .first { it.localGeneration == result.entry.localGeneration }
            assertEquals(expected, reloaded.rejectionCode)
        }
    }

    @Test
    fun delayedDisplayOperationFailureRetainsTypedReadbackReason() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        repository.transact { it.copy(activeHost = host) }
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_SAVE_CURRENT.path,
            jsonObject("request_id" to JsonValue.StringValue("save-delayed-failure"),
                "output_id" to JsonValue.StringValue("HDMI-A-1"),
                "generation" to JsonValue.NumberValue("4"),
                "visible" to JsonValue.BooleanValue(true)), host.bearerToken)
        val accepted = journal.dispatchForHost(host, request) { _, _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
                OperationSummary("op-delayed-failure", "save-delayed-failure",
                    WireValue.Known(OperationState.ACCEPTED), "display.save-current", "created", "updated", null, null),
                jsonObject(),
            )), 202, null)
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.ACCEPTED, accepted.entry.phase)
        assertEquals(JournalUpdateResult.Saved, journal.applyOperationSnapshot(
            host, accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint,
            host.certificatePin, "op-delayed-failure", "save-delayed-failure", "display.save-current",
            WireValue.Known(OperationState.FAILED), "readback_not_confirmed",
        ))

        val restored = (repository.load() as StateLoadResult.Ready).state
        val failure = DisplayRecoveryPresentation.latestFailure(host, restored)!!
        assertEquals(JournalRejectionCode.READBACK_NOT_CONFIRMED, failure.reason)
        assertEquals(JournalPhase.FAILED, restored.journal.single().phase)
        assertEquals("op-delayed-failure", restored.journal.single().operationId)
    }

    @Test
    fun responseWithoutAnOperationIdRemainsUnresolvedAndIsNeverResent() {
        val (_, _, repository, journal) = fixture()
        repository.transact { it.copy(activeHost = testActiveHost()) }
        var calls = 0
        val result = journal.dispatch(testPowerRequest("missing-operation-id")) { _, _, _ ->
            calls++
            RequestOutcome.Reply(
                ApiReply.Versioned(
                    1,
                    VersionedPayload.Mutation(null, jsonObject("protocol_version" to JsonValue.NumberValue("1"))),
                ),
                202,
                null,
            )
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.UNKNOWN, result.entry.phase)
        assertNull(result.entry.operationId)
        assertEquals(1, calls)

        val duplicate = journal.dispatch(testPowerRequest("missing-operation-id")) { _, _, _ ->
            calls++
            RequestOutcome.NotSent(TransportFailure.IO)
        }
        assertEquals(StateInvariantCode.DUPLICATE_REQUEST_ID, (duplicate as JournalDispatchResult.NotDispatched).code)
        assertEquals(1, calls)
    }

    @Test
    fun stagedCredentialDiscardJournalsRevocationBeforeSendAndPreservesOldHost() {
        val (store, _, repository, journal, _) = fixture()
        val oldHost = testActiveHost()
        repository.transact { it.copy(activeHost = oldHost) }
        val pairing = PairingStateTransactions(repository)
        val candidate = pairing.beginCandidate(testCandidate()) as StateCommitResult.Saved
        val generation = candidate.state.candidate!!.generation
        val credential = StagedCredential(
            "client-new", "host-new", "staged-token-0001", listOf("status.read"),
            StoredWakeTarget(false, "", null, null, null), 30,
        )
        pairing.updateCandidate(generation) {
            it.copy(hostId = credential.hostId, pairingId = "pair-new", phase = CandidatePhase.APPROVED_STAGED, stagedCredential = credential)
        }
        val expectedBody = io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(
            jsonObject("request_id" to JsonValue.StringValue("discard-approved")),
        )
        var called = false

        store.failNextWrite = true
        val failed = journal.revokeAndDiscardApprovedCandidate(generation, "discard-approved") { _, _, _ ->
            called = true
            RequestOutcome.NotSent(TransportFailure.IO)
        }
        assertEquals(JournalDispatchResult.PersistenceFailed(StatePersistenceFailure.IO), failed)
        assertFalse(called)
        assertEquals(CandidatePhase.APPROVED_STAGED, (repository.load() as StateLoadResult.Ready).state.candidate!!.phase)

        val result = journal.revokeAndDiscardApprovedCandidate(generation, "discard-approved") { host, request, exactBody ->
            called = true
            assertEquals("host-new", host.hostId)
            assertEquals(credential.bearerToken, request.bearerToken)
            assertEquals("/v1/pair/revoke-self", request.path)
            assertArrayEquals(expectedBody, exactBody)
            val prepared = (repository.load() as StateLoadResult.Ready).state
            assertNull(prepared.candidate)
            assertEquals(oldHost, prepared.activeHost)
            assertEquals(2L, prepared.pairingGeneration)
            assertEquals(JournalPhase.PREPARED, prepared.journal.single().phase)
            revokeSucceeded("revoke-discard-op", "discard-approved")
        } as JournalDispatchResult.Finished

        assertTrue(called)
        assertEquals(JournalPhase.SUCCEEDED, result.entry.phase)
        val finished = (repository.load() as StateLoadResult.Ready).state
        assertEquals(oldHost, finished.activeHost)
        assertNull(finished.candidate)
        assertEquals("host-new", finished.journal.single().hostId)
        assertArrayEquals(expectedBody, finished.journal.single().bodyBytes())
    }

    @Test
    fun revokingActiveHostDoesNotSilentlyDiscardSeparateStagedCandidate() {
        val (_, _, repository, journal) = fixture()
        val oldHost = testActiveHost()
        repository.transact { it.copy(activeHost = oldHost) }
        val pairing = PairingStateTransactions(repository)
        val begun = pairing.beginCandidate(testCandidate()) as StateCommitResult.Saved
        val generation = begun.state.candidate!!.generation
        val staged = StagedCredential(
            "client-new", "host-new", "staged-token", listOf("status.read"),
            StoredWakeTarget(false, "", null, null, null), 30,
        )
        pairing.updateCandidate(generation) {
            it.copy(hostId = staged.hostId, pairingId = "pair-new", phase = CandidatePhase.APPROVED_STAGED, stagedCredential = staged)
        }
        val candidateBefore = (repository.load() as StateLoadResult.Ready).state.candidate
        val request = io.github.tuthan.steamoscompanion.core.protocol.ApiRequest(
            io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST,
            "/v1/pair/revoke-self",
            jsonObject("request_id" to JsonValue.StringValue("revoke-old-host")),
            oldHost.bearerToken,
        )

        val result = journal.dispatch(request) { _, _, _ -> revokeSucceeded("revoke-old-op", "revoke-old-host") }

        assertTrue(result is JournalDispatchResult.Finished)
        val after = (repository.load() as StateLoadResult.Ready).state
        assertNull(after.activeHost)
        assertEquals(candidateBefore, after.candidate)
        assertEquals(generation, after.pairingGeneration)
    }

    @Test
    fun fullHistoryRefusesDispatchUntilHistoricalUnresolvedEntryIsAcknowledged() {
        val (_, _, repository, journal, clock) = fixture()
        val host = testActiveHost()
        val historical = host.copy(clientId = "historical-client")
        val entries = (1..PersistentStateLimits.MAX_JOURNAL_ENTRIES).map { index ->
            JournalEntry.create(
                localGeneration = index.toLong(),
                host = historical,
                request = testPowerRequest("power-$index"),
                exactBodyBytes = io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(testPowerRequest("power-$index").body!!),
                nowMillis = clock.wallTimeMillis(),
            ).copy(phase = JournalPhase.UNKNOWN, resultSummary = "may have run")
        }
        repository.transact { it.copy(activeHost = host, journal = entries) }
        var sent = false
        val full = journal.dispatch(testPowerRequest("power-new")) { _, _, _ ->
            sent = true
            RequestOutcome.NotSent(TransportFailure.IO)
        }
        assertEquals(StateInvariantCode.HISTORY_FULL, (full as JournalDispatchResult.NotDispatched).code)
        assertFalse(sent)

        assertEquals(JournalUpdateResult.Saved, journal.acknowledge(1))
        val afterAck = journal.dispatch(testPowerRequest("power-new")) { _, _, _ ->
            sent = true
            RequestOutcome.NotSent(TransportFailure.IO)
        } as JournalDispatchResult.Finished
        assertTrue(sent)
        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(PersistentStateLimits.MAX_JOURNAL_ENTRIES, state.journal.size)
        assertFalse(state.journal.any { it.localGeneration == 1L })
        assertEquals(JournalPhase.FAILED, afterAck.entry.phase)
        assertEquals(
            JournalUpdateResult.Rejected(StateInvariantCode.INVALID_JOURNAL),
            journal.archive(2),
        )
    }

    @Test
    fun oldKnownTerminalEntriesArePrunedForCapacity() {
        val (_, _, repository, journal, clock) = fixture()
        val host = testActiveHost()
        val historical = host.copy(clientId = "historical-client")
        val old = JournalEntry.create(1, host, testPowerRequest("old-terminal"), io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(testPowerRequest("old-terminal").body!!), 1)
            .copy(phase = JournalPhase.FAILED, updatedAtMillis = 1, resultSummary = "rejected")
        val unresolved = (2..PersistentStateLimits.MAX_JOURNAL_ENTRIES).map { index ->
            val request = testPowerRequest("unresolved-$index")
            JournalEntry.create(index.toLong(), historical, request, io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(request.body!!), clock.wallTimeMillis())
                .copy(phase = JournalPhase.UNKNOWN)
        }
        repository.transact { it.copy(activeHost = host, journal = listOf(old) + unresolved) }
        val result = journal.dispatch(testPowerRequest("new-action")) { _, _, _ -> RequestOutcome.NotSent(TransportFailure.IO) }
        assertTrue(result is JournalDispatchResult.Finished)
        val state = (repository.load() as StateLoadResult.Ready).state
        assertFalse(state.journal.any { it.requestId == "old-terminal" })
        assertEquals(PersistentStateLimits.MAX_JOURNAL_ENTRIES, state.journal.size)
    }

    @Test
    fun reconciliationRequiresExactHostClientEndpointPinAndOperationIdentity() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        repository.transact { it.copy(activeHost = host) }
        val accepted = journal.dispatch(testPowerRequest("reconcile-me")) { _, _, _ -> accepted("op-reconcile", "reconcile-me") } as JournalDispatchResult.Finished

        val wrong = journal.applyOperationSnapshot(
            host, accepted.entry.localGeneration, "other-host", host.clientId, host.endpoint, host.certificatePin,
            "op-reconcile", "reconcile-me", "power.suspend", WireValue.Known(OperationState.SUCCEEDED), "suspended",
        )
        assertEquals(StateInvariantCode.INVALID_IDENTITY, (wrong as JournalUpdateResult.Rejected).code)

        val wrongKind = journal.applyOperationSnapshot(
            host, accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint, host.certificatePin,
            "op-reconcile", "reconcile-me", "power.shutdown", WireValue.Known(OperationState.SUCCEEDED), "shutdown",
        )
        assertEquals(StateInvariantCode.INVALID_IDENTITY, (wrongKind as JournalUpdateResult.Rejected).code)

        val right = journal.applyOperationSnapshot(
            host, accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint, host.certificatePin,
            "op-reconcile", "reconcile-me", "power.suspend", WireValue.Known(OperationState.UNKNOWN), "not_found",
        )
        assertEquals(JournalUpdateResult.Saved, right)
        assertEquals(JournalPhase.UNKNOWN, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
        assertEquals(
            JournalUpdateResult.Rejected(StateInvariantCode.INVALID_IDENTITY),
            journal.markOperationNotFound(
                host, accepted.entry.localGeneration, "other-host", host.clientId, host.endpoint, host.certificatePin,
                "op-reconcile", "reconcile-me",
            ),
        )
        assertEquals(
            JournalUpdateResult.Saved,
            journal.markOperationNotFound(
                host, accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint, host.certificatePin,
                "op-reconcile", "reconcile-me",
            ),
        )
        assertEquals(JournalPhase.UNKNOWN, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
    }

    @Test
    fun successfulSelfRevocationDropsCredentialInSameCommitButKeepsResultRecord() {
        val (_, _, repository, journal) = fixture()
        val host = testActiveHost()
        repository.transact { it.copy(activeHost = host) }
        val body = jsonObject("request_id" to JsonValue.StringValue("revoke-1"))
        val request = io.github.tuthan.steamoscompanion.core.protocol.ApiRequest(
            io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST,
            "/v1/pair/revoke-self",
            body,
            host.bearerToken,
        )
        val result = journal.dispatch(request) { _, _, _ ->
            RequestOutcome.Reply(
                ApiReply.Versioned(
                    1,
                    VersionedPayload.Mutation(
                        OperationSummary("revoke-op", "revoke-1", WireValue.Known(OperationState.SUCCEEDED), "pair.revoke-self", "created", "updated", "revoked", null),
                        jsonObject("protocol_version" to JsonValue.NumberValue("1")),
                    ),
                ),
                202,
                null,
            )
        } as JournalDispatchResult.Finished
        val state = (repository.load() as StateLoadResult.Ready).state
        assertNull(state.activeHost)
        assertEquals(1, state.journal.size)
        assertEquals(JournalPhase.SUCCEEDED, result.entry.phase)
    }

    @Test
    fun delayedSelfRevokeReplyCannotEraseNewPairingWithSameHostAndClientIds() {
        val (_, _, repository, journal) = fixture()
        val original = testActiveHost()
        val replacement = original.copy(
            bearerToken = "new-pairing-token",
        )
        repository.transact { it.copy(activeHost = original) }
        val request = io.github.tuthan.steamoscompanion.core.protocol.ApiRequest(
            io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST,
            "/v1/pair/revoke-self",
            jsonObject("request_id" to JsonValue.StringValue("revoke-before-repair")),
            original.bearerToken,
        )
        val result = journal.dispatchForHost(original, request) { _, _, _ ->
            assertTrue(repository.transact { it.copy(activeHost = replacement) } is StateCommitResult.Saved)
            revokeSucceeded("revoke-old-operation", "revoke-before-repair")
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.SUCCEEDED, result.entry.phase)
        assertEquals(replacement, (repository.load() as StateLoadResult.Ready).state.activeHost)
    }

    private fun accepted(operationId: String, requestId: String) = RequestOutcome.Reply(
        ApiReply.Versioned(
            1,
            VersionedPayload.Mutation(
                OperationSummary(operationId, requestId, WireValue.Known(OperationState.ACCEPTED), "power.suspend", "created", "updated", null, null),
                jsonObject("protocol_version" to JsonValue.NumberValue("1")),
            ),
        ),
        202,
        null,
    )

    private fun largeDisplayOrderRequest(host: ActiveHost, requestId: String) = io.github.tuthan.steamoscompanion.core.protocol.ApiRequest(
        io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST,
        "/v1/display/order",
        jsonObject(
            "request_id" to JsonValue.StringValue(requestId),
            "output_keys" to JsonValue.ArrayValue((1..8).map { index ->
                JsonValue.StringValue("output-key-$index-" + "x".repeat(115))
            }),
            "generation" to JsonValue.NumberValue("7"),
            "restart" to JsonValue.BooleanValue(false),
        ),
        host.bearerToken,
    )

    private fun revokeSucceeded(operationId: String, requestId: String) = RequestOutcome.Reply(
        ApiReply.Versioned(
            1,
            VersionedPayload.Mutation(
                OperationSummary(operationId, requestId, WireValue.Known(OperationState.SUCCEEDED), "pair.revoke-self", "created", "updated", "revoked", null),
                jsonObject("protocol_version" to JsonValue.NumberValue("1")),
            ),
        ),
        202,
        null,
    )

    private data class TestClock(var wall: Long = 1_790_000_000_000) : Clock {
        override fun wallTimeMillis() = wall
        override fun monotonicNanos() = wall * 1_000_000
    }

    private fun fixture(): Fixture {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        val clock = TestClock()
        return Fixture(store, cipher, repository, OperationJournal(repository, clock), clock)
    }

    private data class Fixture(
        val store: MemoryStateStore,
        val cipher: JvmAesGcmStateCipher,
        val repository: EncryptedStateRepository,
        val journal: OperationJournal,
        val clock: TestClock,
    )
}
