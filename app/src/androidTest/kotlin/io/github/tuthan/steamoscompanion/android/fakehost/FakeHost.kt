package io.github.tuthan.steamoscompanion.android.fakehost

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
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
import org.json.JSONObject

/**
 * Test-only Decky stand-in: a BouncyCastle TLS 1.2 server on loopback that exports its own
 * `tls-unique` and answers with bodies taken verbatim from the vendored `protocol/` bundle.
 *
 * Replies are scripted per route and consumed in order. When a route's script is empty the host
 * falls back to its default reply for that route, else the pinned host's authenticated `404 not_found`.
 * It does not model authentication: script the captured 401/403 fixtures where a test needs them.
 * It mirrors the pinned framing: HTTP/1.0 responses with `Connection: close` and `Cache-Control: no-store`.
 */
class FakeHost(private val assets: AssetManager) : Closeable {
    sealed interface Reply {
        /** Status and body from a vendored contract fixture (`{"response": {"status", "body"}}`). */
        data class Fixture(val assetPath: String) : Reply

        /** Status and body from a vendored plain body fixture, such as `fixtures/status-*.json`. */
        data class Body(val status: Int, val assetPath: String) : Reply

        /** Reads the request, then closes the TLS connection without answering: a lost reply. */
        data object Lost : Reply
    }

    data class Recorded(val method: String, val path: String, val headers: Map<String, String>, val body: String, val tlsUnique: ByteArray)

    private val crypto = BcTlsCrypto(SecureRandom())
    private val certificateDer = assets.open("fake-host/cert.der").use(InputStream::readBytes)
    private val privateKey = PrivateKeyFactory.createKey(assets.open("fake-host/key.pk8").use(InputStream::readBytes))
    private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Reply>>()
    private val defaults = ConcurrentHashMap<String, Reply>()
    private val failure = AtomicReference<Throwable?>(null)

    val requests = CopyOnWriteArrayList<Recorded>()
    val address: InetSocketAddress get() = InetSocketAddress(server.inetAddress, server.localPort)

    /** Lowercase hex SHA-256 of the DER certificate, the value a client pins. */
    val certificateSha256: String = MessageDigest.getInstance("SHA-256").digest(certificateDer).joinToString("") { "%02x".format(it) }

    private val acceptor = thread(name = "fake-host-accept", isDaemon = true) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "fake-host-conn", isDaemon = true) { serve(socket) }
        }
    }

    fun default(method: String, path: String, reply: Reply) = apply { defaults[key(method, path)] = reply }

    fun script(method: String, path: String, vararg replies: Reply) = apply {
        scripts.getOrPut(key(method, path)) { ConcurrentLinkedDeque() }.addAll(replies)
    }

    /** Rethrows the first server-side failure so a test cannot pass over a broken host. */
    fun assertHealthy() {
        failure.get()?.let { throw AssertionError("fake host failed", it) }
    }

    override fun close() {
        server.close()
        acceptor.join(TimeUnit.SECONDS.toMillis(2))
    }

    private fun key(method: String, path: String) = "${method.uppercase()} $path"

    private fun serve(socket: Socket) = socket.use {
        try {
            it.soTimeout = 10_000
            val tlsServer = Server()
            val protocol = TlsServerProtocol(it.getInputStream(), it.getOutputStream())
            protocol.accept(tlsServer)
            val request = readRequest(protocol.inputStream)
            val binding = tlsServer.tlsUnique()
            requests += request.copy(tlsUnique = binding)
            val reply = scripts[key(request.method, request.path)]?.pollFirst() ?: defaults[key(request.method, request.path)]
            when (reply) {
                Reply.Lost -> protocol.close()
                is Reply.Fixture -> respondWithFixture(protocol, reply.assetPath)
                is Reply.Body -> respond(protocol, reply.status, asset(reply.assetPath))
                null -> respondWithFixture(protocol, NOT_FOUND)
            }
        } catch (error: Throwable) {
            failure.compareAndSet(null, error)
        }
    }

    private fun asset(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Replays a contract fixture's status, body, and any recorded headers such as `Retry-After`. */
    private fun respondWithFixture(protocol: TlsServerProtocol, path: String) {
        val response = JSONObject(asset(path)).getJSONObject("response")
        val headers = response.optJSONObject("headers")
        val extra = headers?.keys()?.asSequence()?.associateWith { headers.getString(it) }.orEmpty()
        respond(protocol, response.getInt("status"), response.get("body").toString(), extra)
    }

    private fun respond(protocol: TlsServerProtocol, status: Int, body: String, extraHeaders: Map<String, String> = emptyMap()) {
        val encoded = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.0 $status ${reason(status)}\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${encoded.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        protocol.outputStream.write(head.toByteArray(Charsets.US_ASCII) + encoded)
        protocol.outputStream.flush()
        protocol.close()
    }

    private fun readRequest(input: InputStream): Recorded {
        val head = ByteArrayOutputStream()
        while (!head.toByteArray().endsWith(CRLF_CRLF)) {
            val next = input.read()
            check(next >= 0) { "connection closed before request headers" }
            head.write(next)
            check(head.size() <= MAX_HEAD) { "request head too large" }
        }
        val lines = head.toString(Charsets.US_ASCII.name()).split("\r\n").filter(String::isNotEmpty)
        val (method, path) = lines.first().split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).associate { line ->
            line.substringBefore(":").trim().lowercase() to line.substringAfter(":").trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        check(length in 0..MAX_JSON_BYTES) { "request body length $length out of range" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            check(n >= 0) { "connection closed inside request body" }
            read += n
        }
        return Recorded(method, path, headers, body.toString(Charsets.UTF_8), ByteArray(0))
    }

    private inner class Server : DefaultTlsServer(crypto) {
        fun tlsUnique(): ByteArray = context.exportChannelBinding(ChannelBinding.tls_unique)

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()

        override fun getSupportedCipherSuites() = SUITES

        override fun getRSASignerCredentials(): TlsCredentialedSigner = BcDefaultTlsCredentialedSigner(
            TlsCryptoParameters(context),
            this@FakeHost.crypto,
            privateKey,
            Certificate(arrayOf(crypto.createCertificate(certificateDer))),
            SignatureAndHashAlgorithm.getInstance(HashAlgorithm.sha256, SignatureAlgorithm.rsa),
        )
    }

    private companion object {
        const val NOT_FOUND = "fixtures/contract/unsupported/unknown-path-get-authenticated.json"
        const val MAX_HEAD = 16 * 1024
        const val MAX_JSON_BYTES = 256 * 1024
        val CRLF_CRLF = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val SUITES = intArrayOf(
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        )

        fun ByteArray.endsWith(suffix: ByteArray) =
            size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }

        fun reason(status: Int) = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            410 -> "Gone"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}
