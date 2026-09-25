package io.github.tuthan.steamoscompanion.core.transport

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class Http1RequestWriterTest {
    @Test
    fun totalDeadlineClosesSocketToUnblockAWrite() {
        val socket = BlockingWriteSocket()
        val deadline = RequestDeadline(SystemClock, 150, null)
        deadline.attach(socket)
        val request = ApiRequest(
            method = HttpMethod.GET,
            path = "/v1/status",
            bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                Http1RequestWriter.write(
                    output = socket.blockedStream,
                    endpoint = HostEndpoint.parse("127.0.0.1:18443"),
                    request = request,
                    body = byteArrayOf(),
                    channelBinding = null,
                    scope = deadline,
                    beforeWrite = {},
                )
            }

            assertTrue("request writer never reached the blocked write", socket.writeEntered.await(1, TimeUnit.SECONDS))
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                future.get(2, TimeUnit.SECONDS)
            }
            assertTrue(error.cause is RequestDeadlineExceededException)
            assertTrue("deadline must close the socket", socket.isClosed)
        } finally {
            deadline.close()
            executor.shutdownNow()
        }
    }

    private object SystemClock : Clock {
        override fun wallTimeMillis() = System.currentTimeMillis()
        override fun monotonicNanos() = System.nanoTime()
    }

    private class BlockingWriteSocket : Socket() {
        private val closed = CountDownLatch(1)
        val writeEntered = CountDownLatch(1)
        @Volatile private var closedFlag = false

        val blockedStream: OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                writeEntered.countDown()
                if (!closed.await(2, TimeUnit.SECONDS)) throw IOException("socket was not closed")
                throw SocketException("socket closed while writing")
            }
        }

        override fun getOutputStream(): OutputStream = blockedStream
        override fun close() {
            closedFlag = true
            closed.countDown()
        }
        override fun isClosed() = closedFlag
    }
}
