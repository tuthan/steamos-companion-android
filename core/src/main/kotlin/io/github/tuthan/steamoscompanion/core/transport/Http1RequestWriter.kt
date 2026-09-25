package io.github.tuthan.steamoscompanion.core.transport

import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import java.io.IOException
import java.io.OutputStream

/** Writes one close-delimited HTTP/1.1 request after TLS trust has been established. */
internal object Http1RequestWriter {
    fun write(
        output: OutputStream,
        endpoint: HostEndpoint,
        request: ApiRequest,
        body: ByteArray,
        channelBinding: String?,
        scope: RequestDeadline,
        beforeWrite: () -> Unit,
    ) {
        val headers = buildString {
            append(request.method.wireValue).append(' ').append(request.path).append(" HTTP/1.1\r\n")
            append("Host: ").append(endpoint.authority).append("\r\n")
            append("Accept: application/json\r\n")
            append("Accept-Encoding: identity\r\n")
            append("Connection: close\r\n")
            request.bearerToken?.let { append("Authorization: Bearer ").append(it).append("\r\n") }
            if (channelBinding != null) append("X-SteamOS-Companion-TLS-Binding: ").append(channelBinding).append("\r\n")
            if (request.method == HttpMethod.POST) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        scope.check()
        beforeWrite()
        try {
            output.write(headers)
            if (body.isNotEmpty()) output.write(body)
            output.flush()
            scope.check()
        } catch (error: IOException) {
            // Closing the socket is how the deadline unblocks an OS/TLS write. Surface the
            // request-level cause rather than misclassifying that close as generic I/O.
            scope.check()
            throw error
        }
    }
}
