package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The diagnostic journal read. Implementations must never throw: null is "not available" for any reason at all. */
fun interface HostJournalGateway {
    fun read(host: ActiveHost, cancellation: CancellationSignal): HostJournal?
}

/**
 * What the Host journal section shows for one pairing. It lives in memory only: it is never saved,
 * journaled, or exported, and it is dropped when the pairing changes.
 */
sealed interface HostJournalState {
    val source: StatusObservationSource

    data class Loading(override val source: StatusObservationSource) : HostJournalState
    data class Unavailable(override val source: StatusObservationSource) : HostJournalState
    data class Ready(override val source: StatusObservationSource, val journal: HostJournal) : HostJournalState

    /** This state, but only for this very pairing; a state held for any other credential is "nothing". */
    fun forHost(host: ActiveHost): HostJournalState? = takeIf { source == StatusObservationSource.from(host) }
}

/**
 * Reads the host journal when Connection details opens and when the owner asks again, never as part of the
 * 2-second status poll and never on a timer. The host rate-limits each client to 120 requests a minute and
 * the poll already spends about 90, so this loader also spaces its reads at least [minSpacingMillis] apart:
 * pressing Refresh again soon after a read costs a short wait, not an extra request.
 *
 * A request for the pairing already pending or running is ignored (it will deliver its result); a request
 * for another pairing replaces it, so the latest pairing wins and the older read's result is discarded.
 * Nothing here can fail a status read, a screen load, or an action: it shares no state with them and swallows
 * every failure as [HostJournalState.Unavailable].
 */
class HostJournalLoader(
    private val gateway: HostJournalGateway,
    private val monotonicNanos: () -> Long,
    private val minSpacingMillis: Long = MIN_SPACING_MILLIS,
    private val deliver: (HostJournalState) -> Unit,
) : Closeable {
    private class Pending(val host: ActiveHost, val source: StatusObservationSource) {
        val cancellation = CancellationSignal()
        var scheduled: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "companion-host-journal").apply { isDaemon = true }
    }
    private var current: Pending? = null
    private var lastStartNanos: Long? = null
    private var closed = false

    /** Safe from any thread. Delivers [HostJournalState.Ready] or [HostJournalState.Unavailable], never Loading. */
    fun request(host: ActiveHost) {
        synchronized(lock) {
            if (closed) return
            val source = StatusObservationSource.from(host)
            current?.let { if (it.source == source) return }
            cancelCurrentLocked()
            val pending = Pending(host, source)
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
        val journal = try {
            gateway.read(pending.host, pending.cancellation)
        } catch (_: Exception) {
            null
        }
        synchronized(lock) {
            if (closed || current !== pending || pending.cancellation.isCancelled) return
            current = null
        }
        try {
            deliver(
                if (journal != null) HostJournalState.Ready(pending.source, journal)
                else HostJournalState.Unavailable(pending.source),
            )
        } catch (_: RuntimeException) {
            // The listener's problem is never this read's: diagnostic data cannot break anything.
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
