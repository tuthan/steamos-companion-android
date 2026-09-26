package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryProbe
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.net.DatagramSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRepairTest {
    private val pin = "sha256:" + "a".repeat(64)
    private val host = ActiveHost(
        "host-1", "client-1", "192.0.2.5:18443", pin, "token", listOf("status.read"),
        StoredWakeTarget(false, null, null, null, null), 1,
    )
    private val path = LanPath("wifi", "wlan0", emptyList(), emptyList())

    @Test fun findAgainChangesOnlyAddressAfterMatchingIdentityAndPin() {
        val repository = repository()
        scanner("host-1", pin).use { scanner ->
            val result = ConnectionRepair(repository, scanner)
                .verifyAndUpdateAddress(host, "192.0.2.8:18443", path)
            assertTrue(result is ConnectionRepairResult.Saved)
        }
        val saved = (repository.load() as StateLoadResult.Ready).state.activeHost!!
        assertEquals(host.copy(endpoint = "192.0.2.8:18443"), saved)
    }

    @Test fun changedCertificateOrHostCannotReplaceSavedAddress() {
        for ((announcedId, announcedPin) in listOf(
            "host-2" to pin,
            "host-1" to ("sha256:" + "b".repeat(64)),
        )) {
            val repository = repository()
            scanner(announcedId, announcedPin).use { scanner ->
                val result = ConnectionRepair(repository, scanner)
                    .verifyAndUpdateAddress(host, "192.0.2.8", path)
                assertTrue(result is ConnectionRepairResult.IdentityChanged)
            }
            assertEquals(host, (repository.load() as StateLoadResult.Ready).state.activeHost)
        }
    }

    private fun repository() = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher()).also {
        assertTrue(it.transact { state -> state.copy(activeHost = host) } is StateCommitResult.Saved)
    }

    private fun scanner(announcedId: String, announcedPin: String): DiscoveryScanner {
        val network = object : NetworkAccess {
            override fun activeLanPaths() = listOf(path)
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        val probe = DiscoveryProbe { target, _ ->
            DiscoveryProbeResult.Candidate(
                UnverifiedDiscoveryCandidate(target, announcedId, "Host", announcedPin),
            )
        }
        return DiscoveryScanner(network, probe)
    }
}
