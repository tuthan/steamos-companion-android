package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A completed read contains the optional dependent reads from the same successful status cycle. */
data class PairedStatusRead(
    val status: VersionedPayload.Status,
    val outputs: VersionedPayload.DisplayOutputs?,
    val order: VersionedPayload.DisplayOrder?,
)

fun interface PairedStatusGateway {
    /** Implementations must honor cancellation and verify the saved host pin and identity. */
    fun read(host: ActiveHost, cancellation: CancellationSignal): PairedStatusRead
}

data class StatusPollState(
    val reading: Boolean = false,
    val lastRead: PairedStatusRead? = null,
    val lastSuccessMonotonicNanos: Long? = null,
    val unreachable: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
) {
    val headline: StatusHeadline get() = when {
        unreachable -> StatusHeadline.UNREACHABLE
        lastRead == null -> StatusHeadline.CHECKING
        stale -> StatusHeadline.STALE
        else -> StatusHeadline.CONNECTED
    }
}

enum class StatusHeadline { CHECKING, CONNECTED, STALE, UNREACHABLE }

/**
 * One foreground poller for the paired graph. A successful read schedules the next cycle two
 * seconds after completion; an unreachable read waits six seconds. A one-second clock tick drives
 * freshness even when no response arrives. No task is scheduled while stopped.
 */
class StatusPoller(
    private val host: ActiveHost,
    private val gateway: PairedStatusGateway,
    private val monotonicNanos: () -> Long,
    private val listener: (StatusPollState) -> Unit,
) : Closeable {
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "companion-status-clock").apply { isDaemon = true }
    }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-status-read").apply { isDaemon = true }
    }
    private var active = false
    private var closed = false
    private var generation = 0L
    private var pendingRefresh = false
    private var running: Future<*>? = null
    private var signal: CancellationSignal? = null
    private var next: ScheduledFuture<*>? = null
    private var ticker: ScheduledFuture<*>? = null
    @Volatile private var published = StatusPollState()

    val state: StatusPollState get() = published

    fun start() {
        synchronized(lock) {
            check(!closed)
            if (active) return
            active = true
            generation++
            published = StatusPollState()
            ticker = timer.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
            dispatchLocked()
        }
        listener(state)
    }

    fun stop() {
        synchronized(lock) {
            if (!active) return
            active = false
            generation++
            pendingRefresh = false
            next?.cancel(false)
            next = null
            ticker?.cancel(false)
            ticker = null
            signal?.cancel()
            running?.cancel(true)
            signal = null
            running = null
            published = published.copy(reading = false)
        }
        listener(state)
    }

    /** Multiple taps during a read produce one immediate follow-up read. */
    fun refresh() {
        synchronized(lock) {
            if (!active || closed) return
            if (signal != null) {
                pendingRefresh = true
            } else {
                next?.cancel(false)
                next = null
                dispatchLocked()
            }
        }
    }

    /** Network changes invalidate the in-flight observation and trigger one fresh read. */
    fun networkChanged() {
        synchronized(lock) {
            if (!active || closed) return
            generation++
            pendingRefresh = true
            next?.cancel(false)
            next = null
            signal?.cancel()
            running?.cancel(true)
            signal = null
            running = null
            published = published.copy(reading = false)
            dispatchLocked()
        }
    }

    private fun dispatchLocked() {
        if (!active || signal != null) return
        val cycle = generation
        val cancellation = CancellationSignal()
        signal = cancellation
        published = published.copy(reading = true)
        running = worker.submit {
            val result = runCatching { gateway.read(host, cancellation) }
            val finishedAt = monotonicNanos()
            val updated: StatusPollState?
            synchronized(lock) {
                if (!active || cycle != generation || signal !== cancellation || cancellation.isCancelled) return@submit
                signal = null
                running = null
                val read = result.getOrNull()?.takeIf { it.status.hostId == host.hostId }
                published = if (read != null) {
                    StatusPollState(lastRead = read, lastSuccessMonotonicNanos = finishedAt)
                } else {
                    published.copy(reading = false, unreachable = true, error =
                        if (result.isSuccess) "Host identity changed" else result.exceptionOrNull()?.message ?: "Status read failed")
                }
                updated = published
                if (pendingRefresh) {
                    pendingRefresh = false
                    dispatchLocked()
                } else {
                    next = timer.schedule({ synchronized(lock) { next = null; dispatchLocked() } },
                        if (read != null) CONNECTED_DELAY_MS else UNREACHABLE_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }
            updated?.let(listener)
        }
    }

    private fun tick() {
        val updated: StatusPollState?
        synchronized(lock) {
            if (!active) return
            val last = published.lastSuccessMonotonicNanos
            val stale = last != null && (monotonicNanos() - last).coerceAtLeast(0) >= STALE_NANOS
            updated = if (stale != published.stale) published.copy(stale = stale).also { published = it } else null
        }
        updated?.let(listener)
    }

    override fun close() {
        stop()
        synchronized(lock) { closed = true }
        timer.shutdownNow()
        worker.shutdownNow()
    }

    companion object {
        const val CONNECTED_DELAY_MS = 2_000L
        const val UNREACHABLE_DELAY_MS = 6_000L
        const val STALE_NANOS = 15_000_000_000L
    }
}
