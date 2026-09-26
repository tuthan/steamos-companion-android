package io.github.tuthan.steamoscompanion.core.transport

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asLong
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.ChannelBinding
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production transport against a JVM loopback TLS host using the shipped test key. */
class ProductionTransportSocketTest {
    @Test
    fun bootstrapHeaderIsIndependentlyCheckedAgainstServerTlsUniqueAndPinMismatchSendsNoRequest() {
        FakeTlsHost().use { host ->
            val target = target(host)
            host.script("POST /v1/pair/request", Reply.Fixture("fixtures/contract/pair-request/verification-pending.json"))
            val body = fixtureRequestBody("fixtures/contract/pair-request/verification-pending.json")
            val request = ApiRequest(HttpMethod.POST, "/v1/pair/request", body)
            val badPin = transport().execute(target, "sha256:${"00".repeat(32)}", request)
            assertEquals(RequestOutcome.NotSent(TransportFailure.PIN), badPin)
            assertTrue(host.requests.isEmpty())

            val result = transport().execute(target, host.pin, request)
            assertTrue(result is RequestOutcome.Reply)
            assertTrue((result as RequestOutcome.Reply).response is ApiReply.Versioned)
            val recorded = host.requests.single()
            val bindingHeader = recorded.headers["x-steamos-companion-tls-binding"]
            assertTrue(bindingHeader.orEmpty().matches(Regex("^[A-Za-z0-9_-]{16,510}={0,2}$")))
            assertTrue(Base64.getUrlDecoder().decode(bindingHeader).contentEquals(recorded.tlsUnique))
            assertFalse(recorded.headers.containsKey("authorization"))
            assertFalse(recorded.body.contains("verification_code"))
            host.assertHealthy()
        }
    }

    @Test
    fun productionTransportClassifiesLostMutationAndPairingPostsAfterSocketDispatch() {
        FakeTlsHost().use { host ->
            val target = target(host)
            host.script("POST /v1/power", Reply.Lost)
            val power = ApiRequest(
                HttpMethod.POST,
                "/v1/power",
                jsonObject("request_id" to JsonValue.StringValue("power-lost"), "action" to JsonValue.StringValue("suspend")),
                "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
            )
            assertTrue(transport().execute(target, host.pin, power) is RequestOutcome.Unknown)

            host.script("POST /v1/pair/request", Reply.Lost)
            val pair = ApiRequest(
                HttpMethod.POST,
                "/v1/pair/request",
                fixtureRequestBody("fixtures/contract/pair-request/verification-pending.json"),
            )
            assertTrue(transport().execute(target, host.pin, pair) is RequestOutcome.PairingUnresolved)
            assertEquals(listOf("/v1/power", "/v1/pair/request"), host.requests.map { it.path })
            host.assertHealthy()
        }
    }

    @Test
    fun pairedPlatformTlsPinIsCheckedBeforeStatusRequestBytes() {
        FakeTlsHost().use { host ->
            val target = target(host)
            val status = ApiRequest(HttpMethod.GET, "/v1/status", bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx")
            assertEquals(RequestOutcome.NotSent(TransportFailure.PIN), transport().execute(target, "sha256:${"00".repeat(32)}", status))
            assertTrue(host.requests.isEmpty())

            host.script("GET /v1/status", Reply.Body("fixtures/status-sunshine-running.json"))
            val result = transport().execute(target, host.pin, status)
            assertTrue(result is RequestOutcome.Reply)
            assertTrue(((result as RequestOutcome.Reply).response as ApiReply.Versioned).payload is VersionedPayload.Status)
            assertEquals("Bearer fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx", host.requests.single().headers["authorization"])
            host.assertHealthy()
        }
    }

    @Test
    fun tls13OnlyHostProducesExplicitTls12RequiredFailureBeforePairingBytes() {
        FakeTlsHost(tls13Only = true).use { host ->
            val request = ApiRequest(
                HttpMethod.POST,
                "/v1/pair/request",
                fixtureRequestBody("fixtures/contract/pair-request/verification-pending.json"),
            )
            val result = transport().execute(target(host), host.pin, request)
            assertEquals(RequestOutcome.NotSent(TransportFailure.TLS12_REQUIRED), result)
            assertTrue(host.requests.isEmpty())
        }
    }

    @Test
    fun discoveryRemainsUnauthenticatedAndFingerprintComesFromThatTlsConnection() {
        FakeTlsHost().use { host ->
            val target = target(host)
            val result = transport().probeDiscovery(target)
            assertTrue(result is DiscoveryProbeResult.Candidate)
            val candidate = (result as DiscoveryProbeResult.Candidate).value
            assertEquals(host.pin, candidate.certificateFingerprint)
            val request = host.requests.single()
            assertEquals("GET", request.method)
            assertEquals("/v1/discovery", request.path)
            assertTrue(request.body.isEmpty())
            assertTrue(request.headers.keys.none { it in setOf("authorization", "cookie", "x-steamos-companion-tls-binding") })
            host.assertHealthy()
        }
    }

    private fun transport() = PinnedHttpTransport(
        TestClock,
        object : NetworkAccess {
            override fun activeLanPaths() = emptyList<LanPath>()
            override fun resolve(host: String, path: LanPath) = listOf(InetAddress.getByName("127.0.0.1"))
            override fun bind(socket: Socket, path: LanPath) = Unit
            override fun bind(socket: DatagramSocket, path: LanPath) = Unit
        },
        TestSocketFactory,
    )

    private fun target(host: FakeTlsHost): TransportTarget {
        val address = InetAddress.getByName("127.0.0.1")
        val path = LanPath("loopback-test", null, listOf(address), emptyList())
        return TransportTarget(HostEndpoint.parse("127.0.0.1:${host.port}"), path)
    }

    private fun fixtureRequestBody(path: String) = resource(path).let(BoundedJson::parseObject)["request"]
        .asObject("request")["body"].asObject("body")

    private object TestClock : Clock {
        override fun wallTimeMillis() = System.currentTimeMillis()
        override fun monotonicNanos() = System.nanoTime()
    }

    private object TestSocketFactory : SocketFactory {
        override fun create() = Socket()
        override fun createDatagram() = DatagramSocket(null)
    }

    private sealed interface Reply {
        data class Fixture(val path: String) : Reply
        data class Body(val path: String) : Reply
        data object Lost : Reply
    }

    private data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val tlsUnique: ByteArray,
    )

    private class FakeTlsHost(private val tls13Only: Boolean = false) : Closeable {
        private val crypto = BcTlsCrypto(SecureRandom())
        private val certificate = resource("fake-host/cert.der")
        private val privateKey = PrivateKeyFactory.createKey(resource("fake-host/key.pk8"))
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val scripts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Reply>>()
        private val failure = AtomicReference<Throwable?>(null)
        val requests = CopyOnWriteArrayList<Recorded>()
        val port: Int get() = server.localPort
        val pin = "sha256:" + MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }

        private val acceptor = thread(name = "jvm-fake-host-accept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(name = "jvm-fake-host-connection", isDaemon = true) { serve(socket) }
            }
        }

        fun script(route: String, vararg replies: Reply) {
            scripts.getOrPut(route) { ConcurrentLinkedDeque() }.addAll(replies)
        }

        fun assertHealthy() {
            failure.get()?.let { throw AssertionError("JVM fake TLS host failed", it) }
        }

        override fun close() {
            server.close()
            acceptor.join(TimeUnit.SECONDS.toMillis(2))
        }

        private fun serve(socket: Socket) = socket.use {
            var protocol: TlsServerProtocol? = null
            try {
                it.soTimeout = 10_000
                val tlsServer = Server()
                protocol = TlsServerProtocol(it.getInputStream(), it.getOutputStream())
                protocol.accept(tlsServer)
                val request = readRequest(protocol.inputStream)
                requests += request.copy(tlsUnique = tlsServer.tlsUnique())
                when (val reply = scripts["${request.method} ${request.path}"]?.pollFirst()) {
                    Reply.Lost -> protocol.close()
                    is Reply.Fixture -> respondFixture(protocol, reply.path)
                    is Reply.Body -> respond(protocol, 200, resource(reply.path))
                    null -> if (request.method == "GET" && request.path == "/v1/discovery") {
                        respond(protocol, 200, """{"protocol_version":1,"service":"steamos-companion","host_id":"host-fixture0001xxxxxxxxxxxxx","certificate_fingerprint":"$pin","name":"JVM fake host"}""")
                    } else {
                        respond(protocol, 404, """{"error":"not_found","message":"not found"}""")
                    }
                }
            } catch (error: Throwable) {
                // A client-side pin rejection intentionally ends TLS before an HTTP request exists.
                if (requests.isNotEmpty()) failure.compareAndSet(null, error)
            } finally {
                runCatching { protocol?.close() }
            }
        }

        private fun respondFixture(protocol: TlsServerProtocol, path: String) {
            val fixture = BoundedJson.parseObject(resource(path))
            val response = fixture["response"].asObject("response")
            val body = BoundedJson.encode(response["body"]!!)
            respond(protocol, response["status"].asLong("status").toInt(), body)
        }

        private fun respond(protocol: TlsServerProtocol, status: Int, body: String) =
            respond(protocol, status, body.toByteArray(Charsets.UTF_8))

        private fun respond(protocol: TlsServerProtocol, status: Int, body: ByteArray) {
            val head = "HTTP/1.0 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                "Connection: close\r\nCache-Control: no-store\r\n\r\n"
            protocol.outputStream.write(head.toByteArray(Charsets.US_ASCII) + body)
            protocol.outputStream.flush()
            protocol.close()
        }

        private fun readRequest(input: InputStream): Recorded {
            val head = ByteArrayOutputStream()
            while (!head.toByteArray().endsWith(HEAD_END)) {
                val byte = input.read()
                check(byte >= 0) { "connection closed before HTTP headers" }
                head.write(byte)
                check(head.size() <= MAX_HEAD_BYTES) { "request headers exceeded limit" }
            }
            val lines = head.toString(Charsets.US_ASCII.name()).split("\r\n").filter(String::isNotEmpty)
            val first = lines.first().split(" ")
            val headers = lines.drop(1).associate { line ->
                line.substringBefore(":").trim().lowercase() to line.substringAfter(":").trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            check(length in 0..MAX_REQUEST_BYTES)
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                val count = input.read(body, offset, body.size - offset)
                check(count > 0) { "connection closed inside request body" }
                offset += count
            }
            return Recorded(first[0], first[1], headers, body.toString(Charsets.UTF_8), ByteArray(0))
        }

        private inner class Server : DefaultTlsServer(crypto) {
            fun tlsUnique() = context.exportChannelBinding(ChannelBinding.tls_unique)
            override fun getSupportedVersions(): Array<ProtocolVersion> = if (tls13Only) ProtocolVersion.TLSv13.only() else ProtocolVersion.TLSv12.only()
            override fun getSupportedCipherSuites() = if (tls13Only) TLS13_CIPHER_SUITES else CIPHER_SUITES
            override fun getRSASignerCredentials(): TlsCredentialedSigner = BcDefaultTlsCredentialedSigner(
                TlsCryptoParameters(context), this@FakeTlsHost.crypto, privateKey,
                Certificate(arrayOf(this@FakeTlsHost.crypto.createCertificate(certificate))),
                SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.rsa),
            )
        }

        companion object {
            private val HEAD_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            private const val MAX_HEAD_BYTES = 16 * 1024
            private const val MAX_REQUEST_BYTES = 256 * 1024
            private val TLS13_CIPHER_SUITES = intArrayOf(CipherSuite.TLS_AES_128_GCM_SHA256)
            private val CIPHER_SUITES = intArrayOf(
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            )
        }
    }

    companion object {
        private fun resource(path: String): ByteArray =
            ProductionTransportSocketTest::class.java.classLoader.getResourceAsStream(path)?.use(InputStream::readBytes)
                ?: throw FileNotFoundException("Test resource not found: $path")

        private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
            size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }
    }
}
