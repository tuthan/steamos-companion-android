package io.github.tuthan.steamoscompanion.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.android.data.AndroidSocketFactory
import io.github.tuthan.steamoscompanion.android.fakehost.FakeHost
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.PersistentStateLimits
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.pairing.PairingCandidateFactory
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.pairing.PairingCoordinator
import io.github.tuthan.steamoscompanion.core.pairing.PinnedPairingGateway
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.Ciphertext
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.StateCipher
import io.github.tuthan.steamoscompanion.core.ports.StateStore
import io.github.tuthan.steamoscompanion.core.pairing.PairingRequestBuilder
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.tls.AlertDescription

/** A0.1 instrumented pairing checks through the production coordinator, TLS transport, and storage. */
@RunWith(AndroidJUnit4::class)
class Phase02PairingGateTest {
    @Test
    fun productionBootstrapPinMismatchSendsBadCertificateAlertBeforeApplicationBytes() {
        FakeHost(InstrumentationRegistry.getInstrumentation().context.assets).use { host ->
            val clock = MutableClock()
            val hostAddress = host.address.address
            val path = LanPath("phase02-pin-test", null, listOf(hostAddress), emptyList())
            val discovered = discovery(host, path)
            val candidate = PairingCandidateFactory().fromDiscovery(
                discovered,
                "Android Companion",
                listOf("status.read", "power.control", "display.control"),
                clock.wallTimeMillis(),
            )
            val target = discovered.target
            val network = object : NetworkAccess {
                override fun activeLanPaths() = listOf(target.network)
                override fun resolve(host: String, path: LanPath) = listOf(hostAddress)
                override fun bind(socket: Socket, path: LanPath) = Unit
                override fun bind(socket: DatagramSocket, path: LanPath) = Unit
            }
            val request = ApiRequest(HttpMethod.POST, "/v1/pair/request", PairingRequestBuilder.request(candidate))
            val badPin = "sha256:" + "00".repeat(32)

            val result = PinnedHttpTransport(clock, network, AndroidSocketFactory())
                .execute(target, badPin, request)

            assertEquals(RequestOutcome.NotSent(TransportFailure.PIN), result)
            assertTrue("pin mismatch sent application bytes", host.requests.isEmpty())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (host.tlsAlerts.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, host.tlsAlerts.size)
            assertEquals(AlertDescription.bad_certificate.toInt(), host.tlsAlerts.single())
            host.assertHealthy()
        }
    }

    @Test
    fun noncePairingShowsTheHostDerivedCodeAndStoresTheReturnedCredential() {
        FakeHost(InstrumentationRegistry.getInstrumentation().context.assets).use { host ->
            host.script("POST", "/v1/pair/request", FakeHost.Reply.PairingPending, FakeHost.Reply.PairingApproved)
            val clock = MutableClock()
            val rig = pairingRig(host, clock)
            try {
                val startedAt = System.nanoTime()
                val first = rig.coordinator.startNonce(discovery(host, rig.path)).get(20, TimeUnit.SECONDS)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                // The fake host runs its own scrypt on this same emulator. This measures both
                // processes plus worker scheduling, not the transport's six-second deadline.
                Log.i("PairingGate", "nonce_initial_total_ms=$elapsedMillis")
                assertTrue("initial request did not become pending: $first", first is PairingAction.Pending)
                val pending = (first as PairingAction.Pending).candidate
                assertNotNull(pending.verificationCode)
                val firstRequest = host.requests.single()
                assertEquals(pending.verificationCode, firstRequest.hostDerivedVerificationCode)
                assertPairingBinding(firstRequest)
                assertEquals(org.bouncycastle.tls.ProtocolVersion.TLSv12, firstRequest.tlsVersion)
                assertTrue(firstRequest.extendedMasterSecret)
                assertFalse(firstRequest.resumedSession)
                assertFalse(firstRequest.body.contains("verification_code"))

                clock.advance(requireNotNull(pending.pollNotBeforeMillis) - clock.wallTimeMillis())
                val approved = rig.coordinator.poll(pending.generation).get(20, TimeUnit.SECONDS)
                host.assertHealthy()
                assertEquals("approval poll was not sent: ${host.requests.map { it.body }}", 2, host.requests.size)
                assertTrue("host approval did not store a credential: $approved", approved is PairingAction.Approved)
                val active = (approved as PairingAction.Approved).activeHost
                assertEquals(TEST_HOST_ID, active.hostId)
                assertEquals(TEST_BEARER_TOKEN, active.bearerToken)
                assertEquals(2, host.requests.size)
                assertPairingBinding(host.requests.first())
                assertFalse("approval poll does not repeat the bootstrap binding", host.requests.last().headers.containsKey(TLS_BINDING_HEADER))
                val persisted = rig.repository.load() as StateLoadResult.Ready
                assertEquals(active, persisted.state.activeHost)
                assertTrue(rig.repository.load().let { (it as StateLoadResult.Ready).state.candidate == null })
                host.assertHealthy()
            } finally {
                rig.clear()
            }
        }
    }

    @Test
    fun qrPayloadPairingUsesTheSameTlsBindingAndStoresTheReturnedCredential() {
        FakeHost(InstrumentationRegistry.getInstrumentation().context.assets).use { host ->
            host.script("POST", "/v1/pair/request", FakeHost.Reply.PayloadPairingApproved)
            val clock = MutableClock()
            val rig = pairingRig(host, clock)
            try {
                val startedAt = System.nanoTime()
                val result = rig.coordinator.startPayload(payload(host, rig.path, clock), networkId = rig.path.id)
                    .get(20, TimeUnit.SECONDS)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                Log.i("PairingGate", "payload_initial_total_ms=$elapsedMillis")
                assertTrue("payload pairing did not store a credential: $result", result is PairingAction.Approved)
                val active = (result as PairingAction.Approved).activeHost
                assertEquals(TEST_HOST_ID, active.hostId)
                assertEquals(TEST_BEARER_TOKEN, active.bearerToken)
                val request = host.requests.single()
                assertPairingBinding(request)
                assertTrue(request.body.contains("\"pairing_id\""))
                assertTrue(request.body.contains("\"secret\""))
                assertFalse(request.body.contains("verification_nonce"))
                assertEquals(active, (rig.repository.load() as StateLoadResult.Ready).state.activeHost)
                host.assertHealthy()
            } finally {
                rig.clear()
            }
        }
    }

    private fun pairingRig(host: FakeHost, clock: MutableClock): PairingRig {
        val hostAddress = host.address.address
        val path = LanPath("phase02-loopback", null, listOf(hostAddress), emptyList())
        val network = object : NetworkAccess {
            override fun activeLanPaths() = listOf(path)
            override fun resolve(host: String, path: LanPath) = listOf(hostAddress)
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        val repository = EncryptedStateRepository(MemoryStateStore(), MemoryStateCipher())
        val transport = PinnedHttpTransport(clock, network, AndroidSocketFactory())
        val coordinator = PairingCoordinator(
            repository = repository,
            transactions = PairingStateTransactions(repository),
            clock = clock,
            gateway = PinnedPairingGateway(transport, network),
        )
        return PairingRig(path, repository, coordinator)
    }

    private fun discovery(host: FakeHost, path: LanPath): UnverifiedDiscoveryCandidate {
        val address = host.address.address
        val hostText = requireNotNull(address.hostAddress).substringBefore('%')
        val input = if (hostText.contains(':')) "[$hostText]:${host.address.port}" else "$hostText:${host.address.port}"
        return UnverifiedDiscoveryCandidate(
            target = TransportTarget(HostEndpoint.parse(input), path),
            hostId = TEST_HOST_ID,
            name = "Fake SteamOS host",
            certificateFingerprint = "sha256:${host.certificateSha256}",
        )
    }

    private fun payload(host: FakeHost, path: LanPath, clock: MutableClock): String {
        val address = host.address.address
        val hostText = requireNotNull(address.hostAddress).substringBefore('%')
        val authority = if (hostText.contains(':')) "[$hostText]:${host.address.port}" else "$hostText:${host.address.port}"
        val expiry = (clock.wallTimeMillis() + 120_000L) / 1_000.0
        val document = jsonObject(
            "protocol_version" to JsonValue.NumberValue("1"),
            "endpoint" to JsonValue.StringValue("https://$authority"),
            "host_id" to JsonValue.StringValue(TEST_HOST_ID),
            "certificate_fingerprint" to JsonValue.StringValue("sha256:${host.certificateSha256}"),
            "pairing_id" to JsonValue.StringValue(QR_PAIRING_ID),
            "secret" to JsonValue.StringValue(QR_SECRET),
            "expires_at" to JsonValue.NumberValue(expiry.toString()),
        )
        assertEquals("phase02-loopback", path.id)
        return "steamos-companion:v1:" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(BoundedJson.encode(document))
    }

    private fun assertPairingBinding(request: FakeHost.Recorded) {
        val value = request.headers[TLS_BINDING_HEADER].orEmpty()
        assertTrue("pairing TLS binding header was missing or malformed", value.matches(Regex("^[A-Za-z0-9_-]{16,510}={0,2}$")))
        assertTrue(Base64.getUrlDecoder().decode(value).contentEquals(request.tlsUnique))
    }

    private data class PairingRig(
        val path: LanPath,
        val repository: EncryptedStateRepository,
        val coordinator: PairingCoordinator,
    ) {
        fun clear() {
            repository.reset()
        }
    }

    private class MutableClock : Clock {
        @Volatile private var wall = System.currentTimeMillis()
        override fun wallTimeMillis() = wall
        override fun monotonicNanos() = System.nanoTime()
        fun advance(milliseconds: Long) { wall += milliseconds }
    }

    private class MemoryStateStore : StateStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun writeAtomically(value: ByteArray) { bytes = value.copyOf() }
        override fun clear() { bytes = null }
    }

    private class MemoryStateCipher : StateCipher {
        override val keyAlias = PersistentStateLimits.STATE_KEY_ALIAS
        private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Ciphertext {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, requireNotNull(key), random)
            cipher.updateAAD(associatedData)
            return Ciphertext(cipher.iv.copyOf(), cipher.doFinal(plaintext))
        }

        override fun decrypt(iv: ByteArray, ciphertextAndTag: ByteArray, associatedData: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, requireNotNull(key), GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertextAndTag)
        }

        override fun deleteKey() { key = null }
    }

    private companion object {
        const val TEST_HOST_ID = "host-fixture0001xxxxxxxxxxxxx"
        const val TEST_BEARER_TOKEN = "phase02-long-lived-test-token-0001"
        const val TLS_BINDING_HEADER = "x-steamos-companion-tls-binding"
        const val QR_PAIRING_ID = "pair-qr-phase02-0001"
        const val QR_SECRET = "phase02-one-time-qr-secret-0001"
    }
}
