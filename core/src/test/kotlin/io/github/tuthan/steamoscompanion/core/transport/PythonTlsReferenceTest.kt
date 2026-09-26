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
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies Android/JVM channel-binding bytes against CPython's independent OpenSSL TLS stack. */
class PythonTlsReferenceTest {
    @Test
    fun bootstrapHeaderIsByteIdenticalToPythonTlsUniqueOnFullTls12Handshake() {
        val script = resourceFile("python-tls/tls_reference_server.py")
        val certificate = resourceFile("python-tls/fake-host-cert.pem")
        val privateKey = resourceFile("python-tls/fake-host-key.pem")
        val certDer = resource("fake-host/cert.der")
        val pin = "sha256:" + MessageDigest.getInstance("SHA-256").digest(certDer)
            .joinToString("") { "%02x".format(it) }
        val process = ProcessBuilder(
            "python3",
            script.absolutePath,
            certificate.absolutePath,
            privateKey.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader()

        try {
            val ready = output.readLine() ?: error("Python TLS server exited before announcing its port")
            val readyFields = ready.split('\t')
            assertEquals("READY", readyFields.first())
            val port = readyFields.getOrNull(1)?.toIntOrNull() ?: error("Invalid Python TLS server port: $ready")
            val address = InetAddress.getByName("127.0.0.1")
            val path = LanPath("python-reference", null, listOf(address), emptyList())
            val target = TransportTarget(HostEndpoint.parse("127.0.0.1:$port"), path)
            val request = ApiRequest(
                HttpMethod.POST,
                "/v1/pair/request",
                resource("fixtures/contract/pair-request/verification-pending.json")
                    .let(BoundedJson::parseObject)["request"].asObject("request")["body"].asObject("body"),
            )

            val outcome = PinnedHttpTransport(TestClock, TestNetworkAccess, TestSocketFactory)
                .execute(target, pin, request)

            assertTrue("Python reference pairing request failed: $outcome", outcome is RequestOutcome.Reply)
            assertTrue((outcome as RequestOutcome.Reply).response is ApiReply.Versioned)
            val result = output.readLine() ?: error("Python TLS server did not report its binding")
            val fields = result.split('\t')
            assertEquals("RESULT", fields.first())
            assertEquals("TLSv1.2", fields[1])
            assertTrue(fields[2] in ACCEPTED_OPENSSL_SUITES)
            assertEquals("false", fields[3])
            assertEquals(fields[4], fields[5])
            assertTrue(fields[5].matches(Regex("^[A-Za-z0-9_-]{16,510}={0,2}$")))
            val pythonBinding = Base64.getUrlDecoder().decode(fields[4])
            val requestBinding = Base64.getUrlDecoder().decode(fields[5])
            assertTrue(pythonBinding.size >= PinnedHttpTransport.MIN_BINDING_BYTES)
            assertTrue(pythonBinding.contentEquals(requestBinding))
            assertTrue("Python TLS server did not exit cleanly", process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            process.destroy()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun resourceFile(path: String): File = File(
        requireNotNull(javaClass.classLoader.getResource(path)) { "Test resource not found: $path" }.toURI(),
    )

    private object TestClock : Clock {
        override fun wallTimeMillis() = System.currentTimeMillis()
        override fun monotonicNanos() = System.nanoTime()
    }

    private object TestNetworkAccess : NetworkAccess {
        override fun activeLanPaths() = emptyList<LanPath>()
        override fun resolve(host: String, path: LanPath) = listOf(InetAddress.getByName("127.0.0.1"))
        override fun bind(socket: Socket, path: LanPath) = Unit
        override fun bind(socket: DatagramSocket, path: LanPath) = Unit
    }

    private object TestSocketFactory : SocketFactory {
        override fun create() = Socket()
        override fun createDatagram() = DatagramSocket(null)
    }

    private companion object {
        val ACCEPTED_OPENSSL_SUITES = setOf(
            "ECDHE-RSA-AES128-GCM-SHA256",
            "ECDHE-RSA-AES256-GCM-SHA384",
            "ECDHE-RSA-CHACHA20-POLY1305",
        )

        fun resource(path: String): ByteArray =
            requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Test resource not found: $path" }
                .use { it.readBytes() }
    }
}
