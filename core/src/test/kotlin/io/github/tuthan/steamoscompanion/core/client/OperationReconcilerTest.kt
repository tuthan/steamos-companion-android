package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.OperationState
import io.github.tuthan.steamoscompanion.core.protocol.OperationSummary
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationReconcilerTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 1_000_000_000L
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val host = testActiveHost()

    @Test fun acceptedOperationIsLookedUpAndReconciledWithoutPostReplay() {
        repository.transact { it.copy(activeHost = host) }
        var posts = 0
        val entry = (journal.dispatchForHost(host, testPowerRequest("power-1")) { _, _, _ ->
            posts++
            mutation("op-1", "power-1", "power.suspend", OperationState.ACCEPTED)
        } as JournalDispatchResult.Finished).entry
        var gets = 0
        val reconciler = OperationReconciler(repository, journal) { selected, id ->
            gets++
            assertEquals(host, selected)
            assertEquals("op-1", id)
            operation("op-1", "power-1", "power.suspend", OperationState.SUCCEEDED)
        }
        assertEquals(ReconcileResult.Updated(JournalUpdateResult.Saved), reconciler.reconcile(host, entry.localGeneration))
        assertEquals(JournalPhase.SUCCEEDED, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
        assertEquals(1, posts)
        assertEquals(1, gets)
        assertEquals(ReconcileResult.Unchanged(ReconcileHold.ENTRY_TERMINAL), reconciler.reconcile(host, entry.localGeneration))
        assertEquals(1, gets)
    }

    @Test fun wrongOperationKindCannotCompleteAnEntryAnd404RemainsUnknown() {
        repository.transact { it.copy(activeHost = host) }
        val entry = (journal.dispatchForHost(host, testPowerRequest("power-2")) { _, _, _ ->
            mutation("op-2", "power-2", "power.suspend", OperationState.ACCEPTED)
        } as JournalDispatchResult.Finished).entry
        val mismatch = OperationReconciler(repository, journal) { _, _ ->
            operation("op-2", "power-2", "power.shutdown", OperationState.SUCCEEDED)
        }
        assertEquals(ReconcileResult.Unchanged(ReconcileHold.OPERATION_IDENTITY_MISMATCH), mismatch.reconcile(host, entry.localGeneration))
        assertEquals(JournalPhase.ACCEPTED, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
        val missing = OperationReconciler(repository, journal) { _, _ ->
            RequestOutcome.Rejected(ApiReply.Rejected(404, "not_found", "pruned"), null)
        }
        assertEquals(ReconcileResult.Updated(JournalUpdateResult.Saved), missing.reconcile(host, entry.localGeneration))
        assertEquals(JournalPhase.UNKNOWN, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
    }

    @Test fun lostPostReplyWithoutOperationIdIsNotLookedUpOrReplayed() {
        repository.transact { it.copy(activeHost = host) }
        val entry = (journal.dispatchForHost(host, testPowerRequest("power-3")) { _, _, _ ->
            RequestOutcome.Unknown(TransportFailure.IO)
        } as JournalDispatchResult.Finished).entry
        val reconciler = OperationReconciler(repository, journal) { _, _ -> error("lookup must not run without an operation ID") }
        assertEquals(ReconcileResult.Unchanged(ReconcileHold.OPERATION_ID_MISSING), reconciler.reconcile(host, entry.localGeneration))
        assertTrue((repository.load() as StateLoadResult.Ready).state.journal.single().phase.unresolved)
    }

    @Test fun oldCredentialOperationIsNotLookedUpUnderNewPairing() {
        repository.transact { it.copy(activeHost = host) }
        val entry = (journal.dispatchForHost(host, testPowerRequest("old-power")) { _, _, _ ->
            mutation("op-old", "old-power", "power.suspend", OperationState.ACCEPTED)
        } as JournalDispatchResult.Finished).entry
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        repository.transact { it.copy(activeHost = rotated) }
        val reconciler = OperationReconciler(repository, journal) { _, _ -> error("old operation must not be looked up") }
        assertEquals(ReconcileResult.Unchanged(ReconcileHold.PAIRING_CHANGED),
            reconciler.reconcile(rotated, entry.localGeneration))
    }

    @Test fun delayedLookupCannotUpdateOldJournalAfterSameHostCredentialRefresh() {
        for (notFound in listOf(false, true)) {
            val localRepository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
            val localJournal = OperationJournal(localRepository, clock)
            localRepository.transact { it.copy(activeHost = host) }
            val entry = (localJournal.dispatchForHost(host, testPowerRequest("delayed-$notFound")) { _, _, _ ->
                mutation("op-delayed", "delayed-$notFound", "power.suspend", OperationState.ACCEPTED)
            } as JournalDispatchResult.Finished).entry
            val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
            val reconciler = OperationReconciler(localRepository, localJournal) { _, _ ->
                assertTrue(localRepository.transact { it.copy(activeHost = rotated) } is StateCommitResult.Saved)
                if (notFound) RequestOutcome.Rejected(ApiReply.Rejected(404, "not_found", "pruned"), null)
                else operation("op-delayed", "delayed-$notFound", "power.suspend", OperationState.SUCCEEDED)
            }
            assertEquals(ReconcileResult.Unchanged(ReconcileHold.PAIRING_CHANGED),
                reconciler.reconcile(host, entry.localGeneration))
            val after = (localRepository.load() as StateLoadResult.Ready).state
            assertEquals(rotated, after.activeHost)
            assertEquals(JournalPhase.ACCEPTED, after.journal.single().phase)
        }
    }

    @Test fun cancelledLookupCannotWriteACompletedOperation() {
        repository.transact { it.copy(activeHost = host) }
        val entry = (journal.dispatchForHost(host, testPowerRequest("cancelled-lookup")) { _, _, _ ->
            mutation("op-cancelled", "cancelled-lookup", "power.suspend", OperationState.ACCEPTED)
        } as JournalDispatchResult.Finished).entry
        val cancellation = CancellationSignal()
        val reconciler = OperationReconciler(repository, journal) { _, _ ->
            cancellation.cancel()
            operation("op-cancelled", "cancelled-lookup", "power.suspend", OperationState.SUCCEEDED)
        }
        assertEquals(ReconcileResult.Unchanged(ReconcileHold.CANCELLED),
            reconciler.reconcile(host, entry.localGeneration, cancellation))
        assertEquals(JournalPhase.ACCEPTED, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
    }

    private fun summary(id: String, requestId: String, kind: String, state: OperationState) =
        OperationSummary(id, requestId, WireValue.Known(state), kind, "created", "updated", null, null)

    private fun mutation(id: String, requestId: String, kind: String, state: OperationState) = RequestOutcome.Reply(
        ApiReply.Versioned(1, VersionedPayload.Mutation(summary(id, requestId, kind, state),
            jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null,
    )

    private fun operation(id: String, requestId: String, kind: String, state: OperationState) = RequestOutcome.Reply(
        ApiReply.Versioned(1, VersionedPayload.Operation(summary(id, requestId, kind, state))), 200, null,
    )
}
