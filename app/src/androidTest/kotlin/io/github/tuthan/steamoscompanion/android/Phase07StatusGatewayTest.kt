package io.github.tuthan.steamoscompanion.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.android.data.AndroidAtomicStateStore
import io.github.tuthan.steamoscompanion.android.data.AndroidClock
import io.github.tuthan.steamoscompanion.android.data.AndroidSocketFactory
import io.github.tuthan.steamoscompanion.android.data.PinnedCertificateChanged
import io.github.tuthan.steamoscompanion.android.data.PinnedStatusGateway
import io.github.tuthan.steamoscompanion.android.fakehost.FakeHost
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.ConnectionRemoval
import io.github.tuthan.steamoscompanion.core.client.JournalDispatchResult
import io.github.tuthan.steamoscompanion.core.client.JournalMutationSender
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.OperationJournal
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import java.net.DatagramSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Phase 07 reader against pinned TLS and the production Android state store. */
@RunWith(AndroidJUnit4::class)
class Phase07StatusGatewayTest {
    @Test fun lostRevokeReplyKeepsCredentialUntilExplicitForget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val repository = AndroidAtomicStateStore.createRepository(context)
        repository.reset()
        FakeHost(assets).use { host ->
            try {
                host.default("POST", "/v1/pair/revoke-self", FakeHost.Reply.Lost)
                val paired = activeHost(host)
                assertTrue(repository.transact { it.copy(activeHost = paired) } is StateCommitResult.Saved)
                val network = loopbackNetwork(host)
                val transport = PinnedHttpTransport(AndroidClock(), network, AndroidSocketFactory())
                val removal = ConnectionRemoval(OperationJournal(repository, AndroidClock()), PairingStateTransactions(repository))
                val sent = removal.revoke(paired, JournalMutationSender { selected, request, _ ->
                    transport.execute(
                        TransportTarget(HostEndpoint.parse(selected.endpoint), network.activeLanPaths().single()),
                        selected.certificatePin, request,
                    )
                }) as JournalDispatchResult.Finished

                assertEquals(JournalPhase.UNKNOWN, sent.entry.phase)
                val uncertain = (repository.load() as StateLoadResult.Ready).state
                assertEquals(paired, uncertain.activeHost)
                assertEquals(JournalPhase.UNKNOWN, uncertain.journal.single().phase)
                assertTrue(removal.forgetOnThisDevice(paired) is StateCommitResult.Saved)
                val forgotten = (repository.load() as StateLoadResult.Ready).state
                assertEquals(null, forgotten.activeHost)
                assertTrue(forgotten.journal.isEmpty())
                host.assertHealthy()
            } finally { repository.reset() }
        }
    }

    @Test fun journaledRevokeClearsCredentialOnlyAfterMatchingHostOperation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val repository = AndroidAtomicStateStore.createRepository(context)
        repository.reset()
        FakeHost(assets).use { host ->
            try {
                host.default("POST", "/v1/pair/revoke-self", FakeHost.Reply.RevokeSucceeded)
                val paired = activeHost(host)
                assertTrue(repository.transact { it.copy(activeHost = paired) } is StateCommitResult.Saved)
                val network = loopbackNetwork(host)
                val transport = PinnedHttpTransport(AndroidClock(), network, AndroidSocketFactory())
                val removal = ConnectionRemoval(OperationJournal(repository, AndroidClock()), PairingStateTransactions(repository))

                val result = removal.revoke(paired, JournalMutationSender { selected, request, _ ->
                    transport.execute(
                        TransportTarget(HostEndpoint.parse(selected.endpoint), network.activeLanPaths().single()),
                        selected.certificatePin, request,
                    )
                }) as JournalDispatchResult.Finished

                assertEquals(JournalPhase.SUCCEEDED, result.entry.phase)
                assertEquals(null, (repository.load() as StateLoadResult.Ready).state.activeHost)
                assertEquals("/v1/pair/revoke-self", host.requests.single().path)
                assertEquals("Bearer fixture-status-token", host.requests.single().headers["authorization"])
                host.assertHealthy()
            } finally { repository.reset() }
        }
    }

    @Test fun successfulStatusReadsDependentRoutesAndPersistsBootIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val repository = AndroidAtomicStateStore.createRepository(context)
        repository.reset()
        FakeHost(assets).use { host ->
            try {
                host.default("GET", "/v1/status", FakeHost.Reply.Body(200, "fixtures/status-sunshine-running.json"))
                host.default("GET", "/v1/display/outputs", FakeHost.Reply.Body(200, "fixtures/outputs-working.json"))
                host.default("GET", "/v1/display/order", FakeHost.Reply.Fixture("fixtures/contract/display-order-get/available.json"))
                val paired = activeHost(host)
                assertTrue(repository.transact { it.copy(activeHost = paired) } is StateCommitResult.Saved)

                val read = gateway(host, repository).read(paired, CancellationSignal())

                assertEquals("fixture-host", read.status.hostId)
                assertEquals("fixture-boot", read.status.bootId)
                assertNotNull(read.outputs)
                assertNotNull(read.order)
                assertNotNull(read.timing)
                assertEquals(
                    listOf("/v1/status", "/v1/display/outputs", "/v1/display/order"),
                    host.requests.map { it.path },
                )
                assertTrue(host.requests.all { it.headers["authorization"] == "Bearer fixture-status-token" })
                assertEquals(
                    "fixture-boot",
                    (repository.load() as StateLoadResult.Ready).state.activeHost?.lastObservedBootId,
                )
                host.assertHealthy()
            } finally { repository.reset() }
        }
    }

    @Test fun changedCertificateRequiresRepairWithoutSendingStatusBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val repository = AndroidAtomicStateStore.createRepository(context)
        repository.reset()
        FakeHost(assets).use { host ->
            try {
                val wrongPin = activeHost(host).copy(certificatePin = "sha256:" + "0".repeat(64))
                assertTrue(repository.transact { it.copy(activeHost = wrongPin) } is StateCommitResult.Saved)
                assertThrows(PinnedCertificateChanged::class.java) {
                    gateway(host, repository).read(wrongPin, CancellationSignal())
                }
                assertTrue(host.requests.isEmpty())
            } finally { repository.reset() }
        }
    }

    private fun activeHost(host: FakeHost): ActiveHost {
        val address = host.address.address.hostAddress!!.substringBefore('%')
        val authority = if (address.contains(':')) "[$address]:${host.address.port}" else "$address:${host.address.port}"
        return ActiveHost(
            hostId = "fixture-host",
            clientId = "fixture-client",
            endpoint = authority,
            certificatePin = "sha256:${host.certificateSha256}",
            bearerToken = "fixture-status-token",
            grantedScopes = listOf("status.read"),
            wakeTarget = StoredWakeTarget(false, null, null, null, null),
            pairedAtMillis = 1L,
        )
    }

    private fun gateway(host: FakeHost, repository: io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository): PinnedStatusGateway {
        val network = loopbackNetwork(host)
        return PinnedStatusGateway(PinnedHttpTransport(AndroidClock(), network, AndroidSocketFactory()), network, repository)
    }

    private fun loopbackNetwork(host: FakeHost): NetworkAccess {
        val address = host.address.address
        val path = LanPath("loopback", null, listOf(address), emptyList())
        return object : NetworkAccess {
            override fun activeLanPaths() = listOf(path)
            override fun resolve(host: String, path: LanPath) = listOf(address)
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
    }
}
