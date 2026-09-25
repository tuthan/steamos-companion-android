package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStateTransactionsTest {
    @Test
    fun replacementKeepsOldCredentialUntilApprovalAndCommitsNewHostWithJournalContext() {
        val store = MemoryStateStore()
        val repository = EncryptedStateRepository(store, JvmAesGcmStateCipher())
        val transactions = PairingStateTransactions(repository)
        val oldHost = testActiveHost()
        val oldRequest = testPowerRequest("before-replacement")
        val oldEntry = JournalEntry.create(1, oldHost, oldRequest, BoundedJson.encode(oldRequest.body!!), 10)
        repository.transact { it.copy(activeHost = oldHost, journal = listOf(oldEntry)) }

        val begun = transactions.beginCandidate(testCandidate()) as StateCommitResult.Saved
        assertEquals(1L, begun.state.pairingGeneration)
        assertEquals(oldHost, begun.state.activeHost)
        val readyCandidate = begun.state.candidate ?: error("candidate did not persist")
        assertEquals(CandidatePhase.READY, readyCandidate.phase)
        val generation = readyCandidate.generation
        val staged = StagedCredential(
            clientId = "client-new",
            hostId = oldHost.hostId,
            bearerToken = "staged-token-0001",
            grantedScopes = listOf("status.read", "power.control"),
            wakeTarget = StoredWakeTarget(false, "", null, null, null),
            pairedAtMillis = 20,
        )
        val candidateUpdate = transactions.updateCandidate(generation) {
            it.copy(
                hostId = staged.hostId,
                pairingId = "pairing-new-1",
                phase = CandidatePhase.APPROVED_STAGED,
                stagedCredential = staged,
            )
        }
        assertTrue(candidateUpdate is StateCommitResult.Saved)

        store.failNextWrite = true
        assertEquals(
            StateCommitResult.Failed(StatePersistenceFailure.IO),
            transactions.acceptApprovedCandidate(generation),
        )
        val afterFailure = (repository.load() as StateLoadResult.Ready).state
        assertEquals(oldHost, afterFailure.activeHost)
        assertEquals(staged, afterFailure.candidate!!.stagedCredential)
        assertEquals(listOf(oldEntry), afterFailure.journal)

        val accepted = transactions.acceptApprovedCandidate(generation) as StateCommitResult.Saved
        assertEquals(oldHost.hostId, accepted.state.activeHost!!.hostId)
        assertEquals("client-new", accepted.state.activeHost.clientId)
        assertEquals(staged.bearerToken, accepted.state.activeHost.bearerToken)
        assertNull(accepted.state.candidate)
        assertEquals(2L, accepted.state.pairingGeneration)
        assertEquals(oldHost.hostId, accepted.state.journal.single().hostId)
        assertEquals("client-android", accepted.state.journal.single().clientId)
    }

    @Test
    fun replacementCancellationInvalidatesCallbacksAndWriteFailureDoesNotClaimCancellation() {
        val store = MemoryStateStore()
        val repository = EncryptedStateRepository(store, JvmAesGcmStateCipher())
        val transactions = PairingStateTransactions(repository)
        val first = transactions.beginCandidate(testCandidate()) as StateCommitResult.Saved
        val firstGeneration = first.state.candidate!!.generation
        val replacement = transactions.beginCandidate(testCandidate(clientId = "client-next")) as StateCommitResult.Saved
        val currentGeneration = replacement.state.candidate!!.generation
        assertEquals(2L, currentGeneration)

        assertEquals(
            StateCommitResult.Rejected(StateInvariantCode.STALE_GENERATION),
            transactions.updateCandidate(firstGeneration) { it.copy(clientName = "stale callback") },
        )

        store.failNextWrite = true
        assertEquals(
            StateCommitResult.Failed(StatePersistenceFailure.IO),
            transactions.cancelCandidate(currentGeneration),
        )
        val afterFailedCancel = (repository.load() as StateLoadResult.Ready).state
        assertEquals(currentGeneration, afterFailedCancel.pairingGeneration)
        assertEquals("client-next", afterFailedCancel.candidate!!.clientId)

        val cancelled = transactions.cancelCandidate(currentGeneration) as StateCommitResult.Saved
        assertEquals(3L, cancelled.state.pairingGeneration)
        assertNull(cancelled.state.candidate)
        assertEquals(
            StateCommitResult.Rejected(StateInvariantCode.STALE_GENERATION),
            transactions.updateCandidate(currentGeneration) { it.copy(clientName = "late callback") },
        )
        assertFalse((repository.load() as StateLoadResult.Ready).state.candidate?.clientName == "late callback")
    }

    @Test
    fun forgetIsAtomicAndRemovesOnlySelectedHostJournalAfterSuccessfulCommit() {
        val store = MemoryStateStore()
        val repository = EncryptedStateRepository(store, JvmAesGcmStateCipher())
        val transactions = PairingStateTransactions(repository)
        val active = testActiveHost()
        val activeRequest = testPowerRequest("forget-active")
        val activeEntry = JournalEntry.create(1, active, activeRequest, BoundedJson.encode(activeRequest.body!!), 10)
        val other = testActiveHost(hostId = "other-host", clientId = "client-other", token = "other-token-0001")
        val otherRequest = testPowerRequest("forget-other").copy(bearerToken = other.bearerToken)
        val otherEntry = JournalEntry.create(2, other, otherRequest, BoundedJson.encode(otherRequest.body!!), 10)
        repository.transact { it.copy(activeHost = active, journal = listOf(activeEntry, otherEntry)) }
        val candidate = transactions.beginCandidate(testCandidate()) as StateCommitResult.Saved
        val generation = candidate.state.pairingGeneration

        store.failNextWrite = true
        assertEquals(
            StateCommitResult.Failed(StatePersistenceFailure.IO),
            transactions.forgetActiveHost(active.hostId),
        )
        val afterFailure = (repository.load() as StateLoadResult.Ready).state
        assertEquals(active, afterFailure.activeHost)
        assertEquals(generation, afterFailure.pairingGeneration)
        assertEquals("client-new", afterFailure.candidate!!.clientId)
        assertEquals(listOf(activeEntry, otherEntry), afterFailure.journal)

        val forgotten = transactions.forgetActiveHost(active.hostId) as StateCommitResult.Saved
        assertNull(forgotten.state.activeHost)
        assertNull(forgotten.state.candidate)
        assertEquals(generation + 1, forgotten.state.pairingGeneration)
        assertEquals(listOf(otherEntry), forgotten.state.journal)
    }

    @Test
    fun stagedCandidateCannotBeSilentlyCancelledWithoutJournaledSelfRevocation() {
        val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
        val transactions = PairingStateTransactions(repository)
        val active = testActiveHost()
        repository.transact { it.copy(activeHost = active) }
        val begun = transactions.beginCandidate(testCandidate()) as StateCommitResult.Saved
        val generation = begun.state.candidate!!.generation
        val staged = StagedCredential(
            "client-new", "host-new", "staged-token", listOf("status.read"),
            StoredWakeTarget(false, "", null, null, null), 20,
        )
        transactions.updateCandidate(generation) {
            it.copy(hostId = staged.hostId, pairingId = "pairing-1", phase = CandidatePhase.APPROVED_STAGED, stagedCredential = staged)
        }
        assertEquals(
            StateCommitResult.Rejected(StateInvariantCode.INVALID_CANDIDATE),
            transactions.cancelCandidate(generation),
        )
        assertEquals(
            StateCommitResult.Rejected(StateInvariantCode.INVALID_CANDIDATE),
            transactions.beginCandidate(testCandidate(clientId = "client-replacement")),
        )
        assertEquals(
            StateCommitResult.Rejected(StateInvariantCode.INVALID_CANDIDATE),
            transactions.updateCandidate(generation) { it.copy(phase = CandidatePhase.READY, stagedCredential = null) },
        )
        assertEquals(
            StateCommitResult.Rejected(StateInvariantCode.INVALID_CANDIDATE),
            transactions.forgetActiveHost(active.hostId),
        )
        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(active, state.activeHost)
        assertEquals(CandidatePhase.APPROVED_STAGED, state.candidate!!.phase)
    }
}
