package io.github.tuthan.steamoscompanion.core.transport

import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class ParsedHttpResponse(val version: String, val response: HttpResponse)

class HttpFramingException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** Strict single-response parser for the pinned host's Content-Length-framed JSON responses. */
object Http1ResponseParser {
    fun read(
        input: InputStream,
        maxBodyBytes: Int,
        beforeRead: () -> Unit = {},
    ): ParsedHttpResponse {
        require(maxBodyBytes >= 0)
        var headerBytes = 0
        val statusLine = line(input, MAX_LINE_BYTES, beforeRead) { bytes ->
            headerBytes += bytes
            if (headerBytes > MAX_HEADER_BYTES) throw HttpFramingException("HTTP response headers are too large")
        }
        val match = STATUS_LINE.matchEntire(statusLine) ?: throw HttpFramingException("Malformed HTTP status line")
        val version = "HTTP/1.${match.groupValues[1]}"
        val status = match.groupValues[2].toIntOrNull() ?: throw HttpFramingException("Invalid HTTP status")
        if (status !in 200..599 || status in 300..399) {
            throw HttpFramingException("Informational, redirect, or invalid HTTP status is not supported")
        }

        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val headerLine = line(input, MAX_LINE_BYTES, beforeRead) { bytes ->
                headerBytes += bytes
                if (headerBytes > MAX_HEADER_BYTES) throw HttpFramingException("HTTP response headers are too large")
            }
            if (headerLine.isEmpty()) break
            if (headerLine[0] == ' ' || headerLine[0] == '\t') throw HttpFramingException("Folded HTTP headers are not supported")
            val colon = headerLine.indexOf(':')
            if (colon <= 0) throw HttpFramingException("Malformed HTTP header")
            val name = headerLine.substring(0, colon)
            if (!HEADER_NAME.matches(name)) throw HttpFramingException("Malformed HTTP header name")
            val key = name.lowercase()
            if (headers.containsKey(key)) throw HttpFramingException("Duplicate HTTP header")
            val value = headerLine.substring(colon + 1).trim(' ', '\t')
            if (value.any { it.code < 0x20 && it != '\t' || it.code == 0x7f }) {
                throw HttpFramingException("Invalid control character in HTTP header")
            }
            headers[key] = value
            if (++count > MAX_HEADERS) throw HttpFramingException("HTTP response has too many headers")
        }

        if (headers.containsKey("transfer-encoding")) throw HttpFramingException("Transfer-Encoding is not supported")
        val contentEncoding = headers["content-encoding"]
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
            throw HttpFramingException("Compressed response bodies are not supported")
        }
        val lengthText = headers["content-length"] ?: throw HttpFramingException("Content-Length is required")
        if (!DECIMAL.matches(lengthText)) throw HttpFramingException("Invalid Content-Length")
        val length = lengthText.toLongOrNull() ?: throw HttpFramingException("Content-Length overflows")
        if (length > maxBodyBytes) throw HttpFramingException("Response body exceeds the route limit")
        if (status == 204 && length != 0L) throw HttpFramingException("204 response cannot include a body")

        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            beforeRead()
            val countRead = try {
                input.read(body, offset, body.size - offset)
            } catch (error: java.net.SocketTimeoutException) {
                throw error
            } catch (error: Exception) {
                throw HttpFramingException("Failed while reading HTTP response body", error)
            }
            if (countRead < 0) throw EOFException("HTTP response body ended before Content-Length")
            if (countRead == 0) continue
            offset += countRead
        }
        return ParsedHttpResponse(version, HttpResponse(status, headers, body))
    }

    private fun line(input: InputStream, maxBytes: Int, beforeRead: () -> Unit, onBytes: (Int) -> Unit): String {
        val buffer = ByteArrayOutputStream()
        var previousWasCr = false
        while (true) {
            beforeRead()
            val next = input.read()
            if (next < 0) throw EOFException("HTTP response ended inside a line")
            onBytes(1)
            if (next == '\n'.code) {
                if (!previousWasCr) throw HttpFramingException("HTTP lines must use CRLF")
                val bytes = buffer.toByteArray()
                if (bytes.isEmpty() || bytes.last() != '\r'.code.toByte()) throw HttpFramingException("Malformed CRLF sequence")
                return bytes.copyOf(bytes.size - 1).toString(StandardCharsets.US_ASCII)
            }
            if (previousWasCr) throw HttpFramingException("CR must be followed by LF")
            if (next > 0x7e || next < 0x20 && next != '\t'.code && next != '\r'.code) {
                throw HttpFramingException("Non-ASCII or control byte in HTTP header")
            }
            buffer.write(next)
            previousWasCr = next == '\r'.code
            if (buffer.size() > maxBytes) throw HttpFramingException("HTTP line exceeds $maxBytes bytes")
        }
    }

    private val STATUS_LINE = Regex("HTTP/1\\.([01]) ([0-9]{3})(?: [\\t\\x20-\\x7e]*)?")
    private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val DECIMAL = Regex("[0-9]+")

    const val MAX_HEADER_BYTES = 16 * 1024
    const val MAX_LINE_BYTES = 4 * 1024
    const val MAX_HEADERS = 64
}
