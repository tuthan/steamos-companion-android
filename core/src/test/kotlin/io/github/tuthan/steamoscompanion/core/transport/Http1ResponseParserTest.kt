package io.github.tuthan.steamoscompanion.core.transport

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class Http1ResponseParserTest {
    @Test
    fun acceptsBothPinnedHostHttpVersionsAndReadsExactLength() {
        listOf("HTTP/1.0", "HTTP/1.1").forEach { version ->
            val input = ByteArrayInputStream("$version 202 Accepted\r\nContent-Length: 2\r\n\r\n{}tail".toByteArray())
            val response = Http1ResponseParser.read(input, 8)
            assertEquals(version, response.version)
            assertEquals(202, response.response.status)
            assertEquals("{}", response.response.body.toString(Charsets.UTF_8))
            assertEquals('t'.code, input.read())
        }
    }

    @Test
    fun rejectsInvalidStatusAndAmbiguousFraming() {
        val invalid = listOf(
            "HTTP/2 200 OK\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 100 Continue\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 302 Found\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\nContent-Length: 0\n\n",
            "HTTP/1.1 200 OK\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\n Content-Length: 0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nBad Header: x\r\nContent-Length: 0\r\n\r\n",
        )
        invalid.forEach { raw -> assertThrows(IOException::class.java) { parse(raw) } }
    }

    @Test
    fun rejectsOverflowOversizeAndTruncatedBodies() {
        assertThrows(IOException::class.java) { parse("HTTP/1.1 200 OK\r\nContent-Length: 99999999999999999999\r\n\r\n") }
        assertThrows(IOException::class.java) { parse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n12345", cap = 4) }
        assertThrows(EOFException::class.java) { parse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n{}") }
    }

    @Test
    fun enforcesHeaderLineCountAndTotalLimits() {
        val many = buildString {
            append("HTTP/1.1 200 OK\r\n")
            repeat(Http1ResponseParser.MAX_HEADERS + 1) { append("X-$it: y\r\n") }
            append("Content-Length: 0\r\n\r\n")
        }
        assertThrows(IOException::class.java) { parse(many) }

        val tooLong = "HTTP/1.1 200 OK\r\nX: ${"a".repeat(Http1ResponseParser.MAX_LINE_BYTES)}\r\nContent-Length: 0\r\n\r\n"
        assertThrows(IOException::class.java) { parse(tooLong) }
    }

    @Test
    fun callsDeadlineBeforeEveryBlockingRead() {
        var calls = 0
        val raw = "HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\n{}"
        Http1ResponseParser.read(ByteArrayInputStream(raw.toByteArray()), 10) { calls++ }
        assertTrue(calls > 4)
    }

    @Test
    fun deadlineAndCancellationCloseRegisteredSocket() {
        val clock = FakeClock()
        val socket = ClosingSocket()
        val cancellation = CancellationSignal()
        val deadline = RequestDeadline(clock, 5_000, cancellation)
        deadline.attach(socket)
        cancellation.cancel()
        assertTrue(socket.isClosed)
        assertThrows(RequestCancelledException::class.java) { deadline.check() }
        deadline.close()

        val timeoutClock = FakeClock()
        val timeoutSocket = ClosingSocket()
        val timeout = RequestDeadline(timeoutClock, 5_000, null)
        timeout.attach(timeoutSocket)
        timeoutClock.nanos += 5_000_000_000L
        assertThrows(RequestDeadlineExceededException::class.java) { timeout.check() }
        assertTrue(timeoutSocket.isClosed)
        timeout.close()
    }

    @Test
    fun fingerprintFormatIsPinnedToLowercaseSha256() {
        val digest = ByteArray(32) { 0xaa.toByte() }
        assertTrue(io.github.tuthan.steamoscompanion.core.protocol.fingerprintMatches(digest, "sha256:" + "aa".repeat(32)))
        assertTrue(!io.github.tuthan.steamoscompanion.core.protocol.fingerprintMatches(digest, "sha256:" + "AA".repeat(32)))
    }

    private fun parse(raw: String, cap: Int = 1024) = Http1ResponseParser.read(ByteArrayInputStream(raw.toByteArray()), cap)

    private class FakeClock(var nanos: Long = 0) : io.github.tuthan.steamoscompanion.core.ports.Clock {
        override fun wallTimeMillis() = 0L
        override fun monotonicNanos() = nanos
    }

    private class ClosingSocket : Socket() {
        @Volatile private var closed = false
        override fun close() { closed = true }
        override fun isClosed() = closed
    }
}
