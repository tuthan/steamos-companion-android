package io.github.tuthan.steamoscompanion.android.fakehost

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject
import org.bouncycastle.tls.ChannelBinding
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.ServerOnlyTlsAuthentication
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto

/**
 * Minimal pinned BC client used only to exercise the fake host. It is not the production
 * bootstrap transport, which Phase 02 proves and Phase 03 implements in :core.
 */
class PinnedTestClient(private val pinSha256: String) {
    data class Response(val status: Int, val headers: Map<String, String>, val body: String, val tlsUnique: ByteArray, val resumed: Boolean)

    private val crypto = BcTlsCrypto(SecureRandom())

    fun request(address: InetSocketAddress, method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Response {
        Socket().use { socket ->
            socket.soTimeout = 5_000
            socket.connect(address, 5_000)
            val client = Client()
            val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            protocol.connect(client)
            val encoded = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val requestHeaders = headers.toMutableMap()
            val pairingSessionPresent = body?.let { runCatching { JSONObject(it).has("pairing_session") }.getOrDefault(false) } == true
            if (method.equals("POST", ignoreCase = true) && path == "/v1/pair/request" && !pairingSessionPresent &&
                requestHeaders.keys.none { it.equals("X-SteamOS-Companion-TLS-Binding", ignoreCase = true) }
            ) {
                requestHeaders["X-SteamOS-Companion-TLS-Binding"] = Base64.getUrlEncoder().encodeToString(client.tlsUnique())
            }
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${address.hostString}:${address.port}\r\n")
                requestHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${encoded.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            protocol.outputStream.write(head.toByteArray(Charsets.US_ASCII) + encoded)
            protocol.outputStream.flush()
            val raw = try {
                protocol.inputStream.readBytes()
            } catch (error: IOException) {
                throw IOException("no reply from host", error)
            }
            if (raw.isEmpty()) throw IOException("host closed the connection without a reply")
            val text = raw.toString(Charsets.UTF_8)
            val headText = text.substringBefore("\r\n\r\n")
            val statusLine = headText.lineSequence().first()
            val responseHeaders = headText.lineSequence().drop(1).associate {
                it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim()
            }
            return Response(
                status = statusLine.split(" ")[1].toInt(),
                headers = responseHeaders,
                body = text.substringAfter("\r\n\r\n"),
                tlsUnique = client.tlsUnique(),
                resumed = client.resumed(),
            )
        }
    }

    private inner class Client : DefaultTlsClient(crypto) {
        fun tlsUnique(): ByteArray = context.exportChannelBinding(ChannelBinding.tls_unique)

        fun resumed(): Boolean = context.securityParametersConnection.isResumedSession

        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()

        override fun getSupportedCipherSuites() = intArrayOf(
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        )

        override fun getAuthentication(): TlsAuthentication = object : ServerOnlyTlsAuthentication() {
            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate) {
                val leaf = serverCertificate.certificate.getCertificateAt(0).encoded
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf).joinToString("") { "%02x".format(it) }
                if (actual != pinSha256) throw TlsFatalAlert(AlertDescription.bad_certificate)
            }
        }
    }
}
