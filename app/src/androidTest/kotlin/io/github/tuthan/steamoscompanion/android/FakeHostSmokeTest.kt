package io.github.tuthan.steamoscompanion.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.android.fakehost.FakeHost
import io.github.tuthan.steamoscompanion.android.fakehost.FakeHost.Reply
import io.github.tuthan.steamoscompanion.android.fakehost.PinnedTestClient
import java.io.IOException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 01 instrumented smoke check: pinned TLS to the fake host serving the vendored bundle. */
@RunWith(AndroidJUnit4::class)
class FakeHostSmokeTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var host: FakeHost
    private lateinit var client: PinnedTestClient

    @Before
    fun start() {
        host = FakeHost(assets)
        client = PinnedTestClient(host.certificateSha256)
    }

    @After
    fun stop() {
        host.close()
    }

    @Test
    fun servesPinnedStatusFixtureOverFullHandshake() {
        host.default("GET", "/v1/status", Reply.Body(200, "fixtures/status-sunshine-running.json"))

        val response = client.request(host.address, "GET", "/v1/status")

        assertEquals(200, response.status)
        assertEquals("close", response.headers["connection"])
        assertEquals("no-store", response.headers["cache-control"])
        assertEquals(
            JSONObject(asset("fixtures/status-sunshine-running.json")).toString(),
            JSONObject(response.body).toString(),
        )
        assertFalse("tls-unique requires a full handshake", response.resumed)
        assertTrue(response.tlsUnique.size >= 12)
        assertArrayEquals(host.requests.single().tlsUnique, response.tlsUnique)
        host.assertHealthy()
    }

    @Test
    fun scriptedRepliesAreConsumedInOrderThenFallBack() {
        host.default("GET", "/v1/display/outputs", Reply.Body(200, "fixtures/outputs-working.json"))
        host.script("GET", "/v1/display/outputs", Reply.Lost)

        assertThrows(IOException::class.java) { client.request(host.address, "GET", "/v1/display/outputs") }
        assertEquals(200, client.request(host.address, "GET", "/v1/display/outputs").status)
        assertEquals(404, client.request(host.address, "GET", "/v1/unknown").status)
        assertEquals(3, host.requests.size)
        host.assertHealthy()
    }

    @Test
    fun scriptsApprovalRejectionExpiryRateLimitAndJournalPruning() {
        val pair = "/v1/pair/request"
        host.script(
            "POST", pair,
            Reply.Fixture(contract("pair-request/verification-pending")),
            Reply.Fixture(contract("pair-request/rate-limited")),
            Reply.Fixture(contract("pair-request/verification-approved")),
            Reply.Fixture(contract("pair-request/verification-rejected")),
            Reply.Fixture(contract("pair-request/verification-expired")),
        )
        val operation = "/v1/operations/op-fixture0001xxxxxxxxxxxxx"
        host.script(
            "GET", operation,
            Reply.Fixture(contract("operations/accepted")),
            Reply.Fixture(contract("operations/not-found-owned-by-other")),
        )

        val outcomes = List(5) { client.request(host.address, "POST", pair, body = "{}") }
        assertEquals(listOf(200, 429, 200, 409, 410), outcomes.map { it.status })
        assertEquals("pending", JSONObject(outcomes[0].body).getString("state"))
        assertEquals("2", outcomes[1].headers["retry-after"])
        assertEquals("pairing_rejected", JSONObject(outcomes[3].body).getString("error"))
        assertEquals("pairing_expired", JSONObject(outcomes[4].body).getString("error"))

        assertEquals(200, client.request(host.address, "GET", operation).status)
        val pruned = client.request(host.address, "GET", operation)
        assertEquals(404, pruned.status)
        assertEquals("operation_not_found", JSONObject(pruned.body).getString("error"))
        host.assertHealthy()
    }

    @Test
    fun wrongPinIsRejectedBeforeAnyRequest() {
        val stranger = PinnedTestClient("00".repeat(32))

        assertThrows(IOException::class.java) { stranger.request(host.address, "GET", "/v1/status") }
        assertTrue(host.requests.isEmpty())
    }

    private fun contract(case: String) = "fixtures/contract/$case.json"

    private fun asset(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
}
