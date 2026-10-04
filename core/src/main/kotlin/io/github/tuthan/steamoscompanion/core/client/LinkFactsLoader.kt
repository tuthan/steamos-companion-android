package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The advisory DRM read. Implementations must never throw: null is "no facts" for any reason at all. */
fun interface LinkFactsGateway {
    fun read(host: ActiveHost, cancellation: CancellationSignal): LinkFacts?
}

/**
 * One advisory reading, tied to the pairing and the output topology it was requested for. It lives in
 * memory only: it is never saved or journaled, and only the owner's explicit export composes a redacted
 * report from it.
 */
data class LinkFactsReading(val source: StatusObservationSource, val topology: String, val facts: LinkFacts?) {
    /** The facts, but only for this very pairing and topology; anything else is "no facts". */
    fun factsFor(host: ActiveHost, currentTopology: String?): LinkFacts? =
        facts.takeIf { currentTopology != null && topology == currentTopology && source == StatusObservationSource.from(host) }
}

/**
 * Reads the advisory link facts once per display interaction (the screen opening, or its output topology
 * changing), never as part of the 2-second status poll. The host rate-limits each client to 120 requests
 * a minute and the poll already spends about 90, so this loader also spaces its reads at least
 * [minSpacingMillis] apart: a flapping topology can cost one request per spacing, not one per poll.
 *
 * The latest request wins. A request for the key already pending or running is ignored; a newer one
 * cancels the older read, whose result is then discarded. Nothing here can fail a status read, a screen
 * load, a preview, or an action: it shares no state with them and swallows every failure as "no facts".
 */
class LinkFactsLoader(
    private val gateway: LinkFactsGateway,
    private val monotonicNanos: () -> Long,
    private val minSpacingMillis: Long = MIN_SPACING_MILLIS,
    private val deliver: (LinkFactsReading) -> Unit,
) : Closeable {
    private class Pending(val host: ActiveHost, val source: StatusObservationSource, val topology: String) {
        val cancellation = CancellationSignal()
        var scheduled: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "companion-link-facts").apply { isDaemon = true }
    }
    private var current: Pending? = null
    private var lastStartNanos: Long? = null
    private var closed = false

    /** Safe from any thread. [topology] comes from [LinkFactsTopology.key]. */
    fun request(host: ActiveHost, topology: String) {
        synchronized(lock) {
            if (closed) return
            val source = StatusObservationSource.from(host)
            current?.let { if (it.source == source && it.topology == topology) return }
            cancelCurrentLocked()
            val pending = Pending(host, source, topology)
            current = pending
            val sinceLast = lastStartNanos?.let { TimeUnit.NANOSECONDS.toMillis((monotonicNanos() - it).coerceAtLeast(0)) }
            val wait = if (sinceLast == null) 0L else (minSpacingMillis - sinceLast).coerceAtLeast(0)
            pending.scheduled = try {
                worker.schedule({ run(pending) }, wait, TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                current = null
                null
            }
        }
    }

    /** Drops any pending or running read, for example when the pairing goes away; its result is never delivered. */
    fun cancel() {
        synchronized(lock) { cancelCurrentLocked() }
    }

    private fun run(pending: Pending) {
        synchronized(lock) {
            if (closed || current !== pending || pending.cancellation.isCancelled) return
            lastStartNanos = monotonicNanos()
        }
        val facts = try {
            gateway.read(pending.host, pending.cancellation)
        } catch (_: Exception) {
            null
        }
        synchronized(lock) {
            if (closed || current !== pending || pending.cancellation.isCancelled) return
            current = null
        }
        try {
            deliver(LinkFactsReading(pending.source, pending.topology, facts))
        } catch (_: RuntimeException) {
            // The listener's problem is never this read's: advisory data cannot break anything.
        }
    }

    private fun cancelCurrentLocked() {
        current?.let {
            it.cancellation.cancel()
            it.scheduled?.cancel(false)
        }
        current = null
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            cancelCurrentLocked()
        }
        worker.shutdownNow()
    }

    companion object {
        const val MIN_SPACING_MILLIS = 5_000L
    }
}
