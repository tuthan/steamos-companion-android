package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryProbe
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.pairing.PairingCoordinator
import io.github.tuthan.steamoscompanion.core.pairing.PairingGateway
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.PairingState
import io.github.tuthan.steamoscompanion.core.protocol.SteamBridgeState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCoreTest {
    @Test
    fun facadeStartsPairingAndPersistsAuthenticatedBootIdForJournalEntries() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        val activeHost = testActiveHost()
        assertTrue(repository.transact { it.copy(activeHost = activeHost) } is StateCommitResult.Saved)
        val clock = object : Clock {
            override fun wallTimeMillis() = 1_790_000_000_000L
            override fun monotonicNanos() = wallTimeMillis() * 1_000_000L
        }
        val gateway = object : PairingGateway {
            override fun execute(
                candidate: PairingCandidate,
                request: io.github.tuthan.steamoscompanion.core.protocol.ApiRequest,
                cancellation: io.github.tuthan.steamoscompanion.core.transport.CancellationSignal,
            ): RequestOutcome {
                assertEquals(ApiRoute.PAIR_REQUEST.path, request.path)
                return RequestOutcome.Reply(
                    ApiReply.Versioned(
                        1,
                        VersionedPayload.Pairing(
                            state = WireValue.Known(PairingState.PENDING),
                            pairingId = "pair-new-host",
                            hostId = candidate.hostId,
                            expiresAt = JsonValue.NumberValue("1790000090"),
                            pairingSession = "fixture-session-0001",
                            credential = null,
                            wakeTarget = null,
                            body = jsonObject(),
                        ),
                    ),
                    200,
                    null,
                )
            }
        }
        val pairing = PairingCoordinator(
            repository,
            PairingStateTransactions(repository),
            clock,
            gateway,
            executor = Executor { it.run() },
        )
        val network = object : NetworkAccess {
            override fun activeLanPaths(): List<LanPath> = emptyList()
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        val scanner = DiscoveryScanner(network, DiscoveryProbe { _, _ -> DiscoveryProbeResult.Failed(TransportFailure.CONNECT) })
        val core = ClientCore(repository, scanner, pairing)
        try {
            val discovery = UnverifiedDiscoveryCandidate(
                TransportTarget(HostEndpoint.parse("new-deck.local:18443"), LanPath("wifi-1", "wlan0", emptyList(), emptyList())),
                "host-new-fixture",
                "SteamOS device",
                "sha256:" + "cd".repeat(32),
            )
            assertTrue(core.startNoncePairing(discovery).join() is PairingAction.Pending)

            val status = VersionedPayload.Status(
                hostId = activeHost.hostId,
                bootId = "00000000-0000-4000-8000-000000000001",
                steamBridge = WireValue.Known(SteamBridgeState.READY),
                capabilities = emptyMap(),
                sunshineState = WireValue.Known(SunshineState.RUNNING),
                body = jsonObject(),
            )
            assertTrue(core.recordAuthenticatedStatus(activeHost, status) is StateCommitResult.Saved)
            val mismatch = core.recordAuthenticatedStatus(activeHost, status.copy(hostId = "different-host"))
            assertEquals(StateCommitResult.Rejected(StateInvariantCode.INVALID_IDENTITY), mismatch)

            val dispatch = OperationJournal(repository, clock).dispatch(testPowerRequest("core-boot-id")) { _, _, _ ->
                val prepared = (repository.load() as StateLoadResult.Ready).state.journal.single()
                assertEquals(status.bootId, prepared.hostBootId)
                RequestOutcome.NotSent(TransportFailure.CONNECT)
            }
            assertEquals(status.bootId, (dispatch as JournalDispatchResult.Finished).entry.hostBootId)
        } finally {
            core.close()
        }
    }
}
