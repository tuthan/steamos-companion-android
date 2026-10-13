package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryProbe
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.ports.Ipv4Subnet
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.net.DatagramSocket
import java.net.InetAddress
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

    // Find device again: the real scanner over a /24, answering from fake probes.
    private val lan = InetAddress.getByName("192.0.2.200").let { local ->
        LanPath("wifi", "wlan0", listOf(local), emptyList(), listOf(Ipv4Subnet(local, 24)))
    }

    private fun lanScanner(answer: (String) -> UnverifiedDiscoveryCandidate?): DiscoveryScanner {
        val network = object : NetworkAccess {
            override fun activeLanPaths() = listOf(lan)
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        val probe = DiscoveryProbe { target, _ ->
            answer(target.endpoint.host)?.let { DiscoveryProbeResult.Candidate(it.copy(target = target)) }
                ?: DiscoveryProbeResult.Failed(TransportFailure.CONNECT)
        }
        return DiscoveryScanner(network, probe)
    }

    private fun answer(hostId: String, pin: String) =
        UnverifiedDiscoveryCandidate(TransportTarget(HostEndpoint.parse("192.0.2.1:18443"), lan), hostId, "Host", pin)

    @Test fun findAgainMovesTheAddressOnlyToAnAnswerWithTheSavedHostIdAndPin() {
        val repository = repository()
        // A decoy with another host ID but the saved pin answers first; only the real host may win.
        lanScanner { ip ->
            when (ip) {
                "192.0.2.7" -> answer("host-2", pin)
                "192.0.2.8" -> answer("host-1", pin)
                else -> null
            }
        }.use { scanner ->
            val result = ConnectionRepair(repository, scanner).findAgain(host)
            assertEquals(ConnectionRepairResult.Saved(host.copy(endpoint = "192.0.2.8:18443")), result)
        }
        val saved = (repository.load() as StateLoadResult.Ready).state.activeHost!!
        assertEquals(host.copy(endpoint = "192.0.2.8:18443"), saved)
        assertEquals("only the address changes", host.bearerToken, saved.bearerToken)
    }

    @Test fun findAgainReportsAChangedIdentityAndNeverSavesIt() {
        val repository = repository()
        val otherPin = "sha256:" + "b".repeat(64)
        lanScanner { ip -> if (ip == "192.0.2.9") answer("host-1", otherPin) else null }.use { scanner ->
            val result = ConnectionRepair(repository, scanner).findAgain(host)
            assertEquals(ConnectionRepairResult.IdentityChanged("host-1", otherPin), result)
        }
        assertEquals(host, (repository.load() as StateLoadResult.Ready).state.activeHost)
    }

    @Test fun findAgainWithNoAnswerFromTheSavedHostIsNotFoundAndChangesNothing() {
        val repository = repository()
        // A different host with the saved pin is not the saved host.
        lanScanner { ip -> if (ip == "192.0.2.7") answer("host-2", pin) else null }.use { scanner ->
            val result = ConnectionRepair(repository, scanner).findAgain(host)
            assertEquals(ConnectionRepairResult.NotFound(partial = false, qualifiedPermission = false), result)
        }
        assertEquals(host, (repository.load() as StateLoadResult.Ready).state.activeHost)
    }

    @Test fun editAddressTriesTheNetworkHoldingTheAddressThenATunnelAndStopsAtTheFirstRealAnswer() {
        val repository = repository()
        val tunnel = LanPath("wg", "wg0", emptyList(), emptyList(), emptyList(), tunnel = true)
        val seen = mutableListOf<String>()
        val network = object : NetworkAccess {
            override fun activeLanPaths() = listOf(tunnel, lan)
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        // The LAN path fails to connect; the tunnel answers with the saved identity.
        val probe = DiscoveryProbe { target, _ ->
            seen += target.network.id
            if (target.network.tunnel) DiscoveryProbeResult.Candidate(UnverifiedDiscoveryCandidate(target, "host-1", "Host", pin))
            else DiscoveryProbeResult.Failed(TransportFailure.CONNECT)
        }
        DiscoveryScanner(network, probe).use { scanner ->
            val result = ConnectionRepair(repository, scanner)
                .verifyAndUpdateAddress(host, "192.0.2.8:18443", network.activeLanPaths())
            assertTrue(result is ConnectionRepairResult.Saved)
        }
        assertEquals("the LAN holding the address is tried before the tunnel", listOf("wifi", "wg"), seen)
        assertEquals("192.0.2.8:18443", (repository.load() as StateLoadResult.Ready).state.activeHost!!.endpoint)

        // A changed identity on the first network is final: it is not retried elsewhere.
        val otherRepository = repository()
        seen.clear()
        val changed = DiscoveryProbe { target, _ ->
            seen += target.network.id
            DiscoveryProbeResult.Candidate(UnverifiedDiscoveryCandidate(target, "host-1", "Host", "sha256:" + "c".repeat(64)))
        }
        DiscoveryScanner(network, changed).use { scanner ->
            val result = ConnectionRepair(otherRepository, scanner)
                .verifyAndUpdateAddress(host, "192.0.2.8:18443", network.activeLanPaths())
            assertTrue(result is ConnectionRepairResult.IdentityChanged)
        }
        assertEquals(listOf("wifi"), seen)
        assertEquals(host, (otherRepository.load() as StateLoadResult.Ready).state.activeHost)
        assertEquals(ConnectionRepairResult.ProbeFailed("No local network"),
            DiscoveryScanner(network, changed).use { ConnectionRepair(otherRepository, it).verifyAndUpdateAddress(host, "192.0.2.8", emptyList<LanPath>()) })
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
