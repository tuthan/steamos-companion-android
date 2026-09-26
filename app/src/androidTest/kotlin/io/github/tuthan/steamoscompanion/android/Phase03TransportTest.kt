package io.github.tuthan.steamoscompanion.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.android.data.AndroidClock
import io.github.tuthan.steamoscompanion.android.data.AndroidSocketFactory
import io.github.tuthan.steamoscompanion.android.fakehost.FakeHost
import io.github.tuthan.steamoscompanion.android.fakehost.FakeHost.Reply
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asArray
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.DiscoveryProbeResult
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production transport against the pinned-fixture fake host on a real Android TLS stack. */
@RunWith(AndroidJUnit4::class)
class Phase03TransportTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var host: FakeHost
    private lateinit var target: TransportTarget
    private lateinit var transport: PinnedHttpTransport

    @Before
    fun start() {
        host = FakeHost(assets)
        val loopback = host.address.address
        val path = LanPath("loopback-test", null, listOf(loopback), emptyList())
        val network = object : NetworkAccess {
            override fun activeLanPaths() = listOf(path)
            override fun resolve(host: String, path: LanPath) = listOf(InetAddress.getByName(host))
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        }
        target = TransportTarget(endpoint(loopback, host.address.port), path)
        transport = PinnedHttpTransport(AndroidClock(), network, AndroidSocketFactory())
    }

    @After
    fun stop() {
        host.close()
    }

    @Test
    fun pinnedPlatformTlsSendsOnlyThePairedBearerOnStatusRead() {
        host.default("GET", "/v1/status", Reply.Body(200, "fixtures/status-sunshine-running.json"))
        val result = transport.execute(target, pin(), statusRequest())

        assertTrue(result is RequestOutcome.Reply)
        val reply = (result as RequestOutcome.Reply).response as ApiReply.Versioned
        assertTrue(reply.payload is VersionedPayload.Status)
        assertEquals("Bearer fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx", host.requests.single().headers["authorization"])
        assertFalse(host.requests.single().headers.containsKey("cookie"))
        host.assertHealthy()
    }

    @Test
    fun firstPairingRequestUsesPinnedTlsUniqueBindingAndNeverAddsBearer() {
        host.default("POST", "/v1/pair/request", Reply.Fixture("fixtures/contract/pair-request/verification-pending.json"))
        val body = fixtureRequestBody("fixtures/contract/pair-request/verification-pending.json")
        val request = ApiRequest(HttpMethod.POST, "/v1/pair/request", body)

        val result = transport.execute(target, pin(), request)

        assertTrue(result is RequestOutcome.Reply)
        assertTrue(((result as RequestOutcome.Reply).response as ApiReply.Versioned).payload is VersionedPayload.Pairing)
        val recorded = host.requests.single()
        val bindingHeader = recorded.headers["x-steamos-companion-tls-binding"].orEmpty()
        assertTrue(bindingHeader.matches(Regex("^[A-Za-z0-9_-]{16,510}={0,2}$")))
        assertTrue(Base64.getUrlDecoder().decode(bindingHeader).contentEquals(recorded.tlsUnique))
        assertFalse(recorded.headers.containsKey("authorization"))
        assertFalse(recorded.body.contains("verification_code"))
        host.assertHealthy()
    }

    @Test
    fun pinMismatchIsNotSentAndLostPostRepliesStayUnresolved() {
        host.default("GET", "/v1/status", Reply.Body(200, "fixtures/status-sunshine-running.json"))
        val mismatch = transport.execute(target, "sha256:${"00".repeat(32)}", statusRequest())
        assertEquals(RequestOutcome.NotSent(TransportFailure.PIN), mismatch)
        assertTrue(host.requests.isEmpty())

        host.script("POST", "/v1/power", Reply.Lost)
        val powerBody = jsonObject(
            "request_id" to JsonValue.StringValue("power-1"),
            "action" to JsonValue.StringValue("suspend"),
        )
        val power = ApiRequest(HttpMethod.POST, "/v1/power", powerBody, "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx")
        val lostMutation = transport.execute(target, pin(), power)
        assertTrue(lostMutation is RequestOutcome.Unknown)
        assertEquals(1, host.requests.size)

        host.script("POST", "/v1/pair/request", Reply.Lost)
        val pair = ApiRequest(
            HttpMethod.POST,
            "/v1/pair/request",
            fixtureRequestBody("fixtures/contract/pair-request/verification-pending.json"),
        )
        val lostPairing = transport.execute(target, pin(), pair)
        assertTrue(lostPairing is RequestOutcome.PairingUnresolved)
        assertEquals(2, host.requests.size)
    }

    @Test
    fun unpinnedProbeIsIsolatedAndBindsFingerprintToSameTlsLeaf() {
        host.default("GET", "/v1/discovery", Reply.DiscoveryCandidate)
        val result = transport.probeDiscovery(target)
        assertTrue(result is DiscoveryProbeResult.Candidate)
        val candidate = (result as DiscoveryProbeResult.Candidate).value
        assertEquals(pin(), candidate.certificateFingerprint)
        val request = host.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/discovery", request.path)
        assertEquals("", request.body)
        assertTrue(request.headers.keys.none { it == "authorization" || it == "cookie" || it.contains("nonce") || it.contains("secret") })
        host.assertHealthy()
    }

    @Test
    fun rateLimitReplyCarriesParsedRetryAfter() {
        host.default("GET", "/v1/status", Reply.Fixture("fixtures/contract/status/rate-limited.json"))
        val result = transport.execute(target, pin(), statusRequest())
        assertTrue(result is RequestOutcome.Rejected)
        assertEquals("rate_limited", (result as RequestOutcome.Rejected).response.error)
        assertEquals(2_000L, result.retryAfterMillis)
        host.assertHealthy()
    }

    @Test
    fun cancellationDuringSlowResponseClosesTheRequestAndReturnsUnknown() {
        host.default(
            "GET",
            "/v1/status",
            Reply.DelayedFixture(2_000, "fixtures/contract/status/sunshine-running.json"),
        )
        val signal = CancellationSignal()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<RequestOutcome> { transport.execute(target, pin(), statusRequest(), signal) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (host.requests.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("fake host did not receive the request", host.requests.isNotEmpty())
            signal.cancel()
            val result = future.get(2, TimeUnit.SECONDS)
            assertTrue(result is RequestOutcome.Unknown)
            assertEquals(TransportFailure.CANCELLED, (result as RequestOutcome.Unknown).failure)
            assertEquals(1, host.requests.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun statusPollUsesTheShortTotalDeadline() {
        host.default("GET", "/v1/status", Reply.DelayedFixture(3_000, "fixtures/contract/status/sunshine-running.json"))

        val result = transport.execute(target, pin(), statusRequest())

        assertTrue(result is RequestOutcome.Unknown)
        assertEquals(TransportFailure.TIMEOUT, (result as RequestOutcome.Unknown).failure)
        assertEquals(1, host.requests.size)
    }

    @Test
    fun malformedSuccessAndSlowDiscoveryAreNeverReportedAsSuccess() {
        val truncated = "HTTP/1.0 202 Accepted\r\nContent-Length: 20\r\n\r\n{}".toByteArray()
        host.default("POST", "/v1/power", Reply.Raw(truncated))
        val powerBody = jsonObject(
            "request_id" to JsonValue.StringValue("power-2"),
            "action" to JsonValue.StringValue("restart"),
        )
        val power = ApiRequest(HttpMethod.POST, "/v1/power", powerBody, "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx")
        assertTrue(transport.execute(target, pin(), power) is RequestOutcome.Unknown)

        host.default("GET", "/v1/discovery", Reply.DelayedFixture(1_500, "fixtures/contract/discovery/ready.json"))
        val discovery = transport.probeDiscovery(target)
        assertEquals(DiscoveryProbeResult.Failed(TransportFailure.TIMEOUT), discovery)
    }

    private fun statusRequest() = ApiRequest(
        HttpMethod.GET,
        "/v1/status",
        bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
    )

    private fun pin() = "sha256:${host.certificateSha256}"

    private fun endpoint(address: InetAddress, port: Int): HostEndpoint {
        val hostValue = (address.hostAddress ?: throw AssertionError("Loopback address has no textual form")).substringBefore('%')
        val input = if (hostValue.contains(':')) "[$hostValue]:$port" else "$hostValue:$port"
        return HostEndpoint.parse(input)
    }

    private fun fixtureRequestBody(path: String): JsonValue.ObjectValue {
        val fixture = BoundedJson.parseObject(assets.open(path).use { it.readBytes() })
        return fixture["request"].asObject("request")["body"].asObject("request.body")
    }

    private fun jsonObject(vararg values: Pair<String, JsonValue>) = JsonValue.ObjectValue(linkedMapOf(*values))
}
