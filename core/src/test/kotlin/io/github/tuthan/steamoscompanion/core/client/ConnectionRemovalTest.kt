package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRemovalTest {
    private val host = ActiveHost(
        "host-1", "client-1", "192.0.2.5:18443", "sha256:" + "a".repeat(64), "token",
        listOf("status.read"), StoredWakeTarget(false, null, null, null, null), 1,
    )
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 100_000_000L
    }

    @Test fun lostRevokeReplyKeepsCredentialUntilExplicitLocalForget() {
        val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
        assertTrue(repository.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
        val removal = ConnectionRemoval(OperationJournal(repository, clock), PairingStateTransactions(repository))
        val result = removal.revoke(host) { _, _, _ -> RequestOutcome.Unknown(TransportFailure.IO) }
        assertTrue(result is JournalDispatchResult.Finished)
        val beforeForget = (repository.load() as StateLoadResult.Ready).state
        assertEquals(host, beforeForget.activeHost)
        assertEquals(JournalPhase.UNKNOWN, beforeForget.journal.single().phase)
        assertTrue(removal.forgetOnThisDevice(host) is StateCommitResult.Saved)
        val afterForget = (repository.load() as StateLoadResult.Ready).state
        assertNull(afterForget.activeHost)
        assertTrue(afterForget.journal.isEmpty())
    }

    @Test fun staleSelectionCannotRevokeOrForgetReplacement() {
        val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
        val replacement = host.copy(clientId = "client-2", bearerToken = "other-token")
        assertTrue(repository.transact { it.copy(activeHost = replacement) } is StateCommitResult.Saved)
        val removal = ConnectionRemoval(OperationJournal(repository, clock), PairingStateTransactions(repository))
        val result = removal.revoke(host) { _, _, _ -> error("stale revoke was sent") }
        assertTrue(result is JournalDispatchResult.NotDispatched)
        assertTrue(removal.forgetOnThisDevice(host) is StateCommitResult.Rejected)
        assertEquals(replacement, (repository.load() as StateLoadResult.Ready).state.activeHost)
    }
}
