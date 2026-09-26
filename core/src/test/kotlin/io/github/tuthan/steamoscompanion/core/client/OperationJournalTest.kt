package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
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
        ).copy(phase = JournalPhase.UNKNOWN, resultSummary = "x")
        val fittingCount = (1 until entries.size).firstOrNull { count ->
            val current = PersistentState(activeHost = host, journal = entries.take(count))
            val next = current.copy(journal = current.journal + newEntry(count.toLong() + 1))
            runCatching { PersistentStateCodec.encode(current) }.isSuccess &&
                runCatching { PersistentStateCodec.encode(next) }.isFailure
        }
        assertTrue("fixture needs a state below 64 KiB whose next entry crosses the cap", fittingCount != null)
        val initial = PersistentState(activeHost = host, journal = entries.take(requireNotNull(fittingCount)))
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
    fun knownRejectionAndDefinitelyNotSentBecomeTerminalFailures() {
        val (_, _, repository, journal) = fixture()
        repository.transact { it.copy(activeHost = testActiveHost()) }
        val rejected = journal.dispatch(testPowerRequest("known-rejection")) { _, _, _ ->
            RequestOutcome.Rejected(ApiReply.Rejected(409, "mutation_conflict", "busy"), null)
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.FAILED, rejected.entry.phase)
        assertTrue(rejected.entry.resultSummary!!.contains("409"))

        val notSent = journal.dispatch(testPowerRequest("known-not-sent")) { _, _, _ ->
            RequestOutcome.NotSent(TransportFailure.PIN)
        } as JournalDispatchResult.Finished
        assertEquals(JournalPhase.FAILED, notSent.entry.phase)
        assertTrue(notSent.entry.resultSummary!!.startsWith("Not sent:"))
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
    fun fullHistoryRefusesDispatchUntilUnresolvedEntryIsAcknowledged() {
        val (_, _, repository, journal, clock) = fixture()
        val host = testActiveHost()
        val entries = (1..PersistentStateLimits.MAX_JOURNAL_ENTRIES).map { index ->
            JournalEntry.create(
                localGeneration = index.toLong(),
                host = host,
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
        val old = JournalEntry.create(1, host, testPowerRequest("old-terminal"), io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(testPowerRequest("old-terminal").body!!), 1)
            .copy(phase = JournalPhase.FAILED, updatedAtMillis = 1, resultSummary = "rejected")
        val unresolved = (2..PersistentStateLimits.MAX_JOURNAL_ENTRIES).map { index ->
            val request = testPowerRequest("unresolved-$index")
            JournalEntry.create(index.toLong(), host, request, io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(request.body!!), clock.wallTimeMillis())
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
            accepted.entry.localGeneration, "other-host", host.clientId, host.endpoint, host.certificatePin,
            "op-reconcile", "reconcile-me", WireValue.Known(OperationState.SUCCEEDED), "suspended",
        )
        assertEquals(StateInvariantCode.INVALID_IDENTITY, (wrong as JournalUpdateResult.Rejected).code)

        val right = journal.applyOperationSnapshot(
            accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint, host.certificatePin,
            "op-reconcile", "reconcile-me", WireValue.Known(OperationState.UNKNOWN), "not_found",
        )
        assertEquals(JournalUpdateResult.Saved, right)
        assertEquals(JournalPhase.UNKNOWN, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
        assertEquals(
            JournalUpdateResult.Rejected(StateInvariantCode.INVALID_IDENTITY),
            journal.markOperationNotFound(
                accepted.entry.localGeneration, "other-host", host.clientId, host.endpoint, host.certificatePin,
                "op-reconcile", "reconcile-me",
            ),
        )
        assertEquals(
            JournalUpdateResult.Saved,
            journal.markOperationNotFound(
                accepted.entry.localGeneration, host.hostId, host.clientId, host.endpoint, host.certificatePin,
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
