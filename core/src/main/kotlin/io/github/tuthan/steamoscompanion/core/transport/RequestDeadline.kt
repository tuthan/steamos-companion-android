package io.github.tuthan.steamoscompanion.core.transport

import io.github.tuthan.steamoscompanion.core.ports.Clock
import java.io.Closeable
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Cancellation is independent of coroutine or thread interruption and closes in-flight sockets. */
class CancellationSignal {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val nextId = AtomicLong()
    private val listeners = java.util.concurrent.ConcurrentHashMap<Long, () -> Unit>()

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            listeners.values.forEach { listener -> runCatching(listener) }
            listeners.clear()
        }
    }

    internal fun onCancel(listener: () -> Unit): Closeable {
        if (cancelled.get()) {
            listener()
            return Closeable { }
        }
        val id = nextId.incrementAndGet()
        listeners[id] = listener
        if (cancelled.get() && listeners.remove(id) != null) listener()
        return Closeable { listeners.remove(id) }
    }
}

class RequestCancelledException : IOException("Request was cancelled")
class RequestDeadlineExceededException : SocketTimeoutException("Request deadline exceeded")

/** One absolute monotonic budget covers DNS, connect, TLS, writes, and reads. */
internal class RequestDeadline(
    private val clock: Clock,
    private val budgetMillis: Long,
    cancellation: CancellationSignal?,
) : Closeable {
    private enum class State { ACTIVE, CANCELLED, EXPIRED, CLOSED }

    private val startNanos = clock.monotonicNanos()
    private val budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis)
    private val state = AtomicReference(State.ACTIVE)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val futures = CopyOnWriteArrayList<Future<*>>()
    private val timer: ScheduledFuture<*>
    private val cancelRegistration: Closeable?

    init {
        require(budgetMillis > 0)
        timer = TIMER.schedule({ abort(State.EXPIRED) }, budgetMillis, TimeUnit.MILLISECONDS)
        cancelRegistration = cancellation?.onCancel { abort(State.CANCELLED) }
        check()
    }

    fun attach(socket: Socket) {
        check()
        sockets += socket
        if (state.get() != State.ACTIVE) {
            runCatching { socket.close() }
            check()
        }
    }

    fun track(future: Future<*>) {
        check()
        futures += future
        if (state.get() != State.ACTIVE) {
            future.cancel(true)
            check()
        }
    }

    fun untrack(future: Future<*>) {
        futures.remove(future)
    }

    fun check() {
        when (state.get()) {
            State.CANCELLED -> throw RequestCancelledException()
            State.EXPIRED -> throw RequestDeadlineExceededException()
            State.CLOSED -> throw IOException("Request deadline is closed")
            State.ACTIVE -> if (elapsedNanos() >= budgetNanos) {
                abort(State.EXPIRED)
                throw RequestDeadlineExceededException()
            }
        }
    }

    fun remainingMillis(): Int {
        check()
        val remaining = budgetNanos - elapsedNanos()
        return ((remaining + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }

    fun beforeRead(socket: Socket) {
        socket.soTimeout = remainingMillis()
    }

    private fun elapsedNanos(): Long = (clock.monotonicNanos() - startNanos).coerceAtLeast(0)

    private fun abort(reason: State) {
        if (state.compareAndSet(State.ACTIVE, reason)) {
            futures.forEach { it.cancel(true) }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        val previous = state.getAndSet(State.CLOSED)
        timer.cancel(false)
        cancelRegistration?.close()
        if (previous != State.CLOSED) {
            futures.forEach { it.cancel(true) }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private val TIMER = ScheduledThreadPoolExecutor(1, NamedDaemonThreadFactory("companion-deadline")).apply {
            removeOnCancelPolicy = true
        }
    }
}

internal object BoundedDnsExecutor {
    private val executor = ThreadPoolExecutor(
        2,
        4,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        NamedDaemonThreadFactory("companion-dns"),
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun <T> submit(block: () -> T): Future<T> = try {
        executor.submit<T> { block() }
    } catch (error: RejectedExecutionException) {
        throw IOException("DNS resolver is busy", error)
    }
}

private class NamedDaemonThreadFactory(private val name: String) : ThreadFactory {
    private val serial = AtomicLong()
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "$name-${serial.incrementAndGet()}").apply {
        isDaemon = true
    }
}
