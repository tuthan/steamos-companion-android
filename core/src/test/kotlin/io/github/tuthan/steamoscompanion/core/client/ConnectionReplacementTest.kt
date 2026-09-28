package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionReplacementTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 1_790_000_000_100L
        override fun monotonicNanos() = 1_000_000_000L
    }

    @Test fun stagedApprovalAndAtomicSwapSurviveFreshRepositoryInstances() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val first = EncryptedStateRepository(store, cipher)
        val oldHost = testActiveHost()
        val oldRequest = testPowerRequest("old-operation")
        val oldEntry = JournalEntry.create(1, oldHost, oldRequest, BoundedJson.encode(requireNotNull(oldRequest.body)), 10)
        assertTrue(first.transact { it.copy(activeHost = oldHost, journal = listOf(oldEntry)) } is StateCommitResult.Saved)
        val begun = PairingStateTransactions(first).beginCandidate(testCandidate()) as StateCommitResult.Saved
        val generation = requireNotNull(begun.state.candidate).generation

        val afterStart = EncryptedStateRepository(store, cipher)
        assertEquals(oldHost, (afterStart.load() as StateLoadResult.Ready).state.activeHost)
        stage(afterStart, generation)

        val afterApproval = EncryptedStateRepository(store, cipher)
        val staged = (afterApproval.load() as StateLoadResult.Ready).state
        assertEquals(oldHost, staged.activeHost)
        assertEquals(CandidatePhase.APPROVED_STAGED, staged.candidate?.phase)
        assertEquals(listOf(oldEntry), staged.journal)
        val replacement = ConnectionReplacement(PairingStateTransactions(afterApproval), OperationJournal(afterApproval, clock))
        val swapped = replacement.confirm(oldHost, generation) as StateCommitResult.Saved
        assertEquals("host-new", swapped.state.activeHost?.hostId)
        assertNull(swapped.state.candidate)
        assertEquals(listOf(oldEntry), swapped.state.journal)

        val afterSwap = (EncryptedStateRepository(store, cipher).load() as StateLoadResult.Ready).state
        assertEquals(swapped.state.activeHost, afterSwap.activeHost)
        assertNull(afterSwap.candidate)
        assertEquals(listOf(oldEntry), afterSwap.journal)
        assertEquals(StateCommitResult.Rejected(StateInvariantCode.STALE_GENERATION), replacement.confirm(oldHost, generation))
    }

    @Test fun failedDiscardCommitKeepsCandidateAndLostCleanupKeepsOldHost() {
        val store = MemoryStateStore()
        val repository = EncryptedStateRepository(store, JvmAesGcmStateCipher())
        val oldHost = testActiveHost()
        repository.transact { it.copy(activeHost = oldHost) }
        val begun = PairingStateTransactions(repository).beginCandidate(testCandidate()) as StateCommitResult.Saved
        val generation = requireNotNull(begun.state.candidate).generation
        stage(repository, generation)
        val replacement = ConnectionReplacement(PairingStateTransactions(repository), OperationJournal(repository, clock))
        var sent = false

        store.failNextWrite = true
        val failed = replacement.discard(generation) { _, _, _ -> sent = true; RequestOutcome.Unknown(TransportFailure.IO) }
        assertEquals(ReplacementDiscardResult.NotDiscarded(JournalDispatchResult.PersistenceFailed(StatePersistenceFailure.IO)), failed)
        assertTrue(!sent)
        assertEquals(CandidatePhase.APPROVED_STAGED, (repository.load() as StateLoadResult.Ready).state.candidate?.phase)

        val uncertain = replacement.discard(generation) { host, _, _ ->
            sent = true
            assertEquals("host-new", host.hostId)
            RequestOutcome.Unknown(TransportFailure.IO)
        } as ReplacementDiscardResult.HostRemovalUnconfirmed
        assertTrue(sent)
        assertEquals(JournalPhase.UNKNOWN, uncertain.entry.phase)
        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(oldHost, state.activeHost)
        assertNull(state.candidate)
        assertEquals(JournalPhase.UNKNOWN, state.journal.single().phase)
    }

    private fun stage(repository: EncryptedStateRepository, generation: Long) {
        val credential = StagedCredential(
            "client-new", "host-new", "staged-token-0001", listOf("status.read"),
            StoredWakeTarget(false, "", null, null, null), 1_790_000_000_050L,
        )
        assertTrue(PairingStateTransactions(repository).updateCandidate(generation) {
            it.copy(hostId = credential.hostId, pairingId = "pair-new", phase = CandidatePhase.APPROVED_STAGED,
                verificationNonce = null,
                stagedCredential = credential)
        } is StateCommitResult.Saved)
    }
}
