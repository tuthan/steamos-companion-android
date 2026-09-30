package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** One read-only reconciliation sweep per visible paired-graph start. No timer or mutation replay. */
class ForegroundOperationReview(
    private val repository: EncryptedStateRepository,
    private val reconciler: OperationReconciler,
    private val host: ActiveHost,
    private val listener: (Long, ReconcileResult) -> Unit,
) : Closeable {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-operation-review").apply { isDaemon = true }
    }
    private var generation = 0L
    private var signal: CancellationSignal? = null
    private var running: Future<*>? = null
    private var closed = false
    private var lastEndedPreviewSweep = Long.MIN_VALUE / 2
    private val endedPreviewAttempts = HashMap<Long, Int>()
    private var lastOpenSweep = Long.MIN_VALUE / 2
    private val openAttempts = HashMap<Long, Int>()

    fun start() {
        synchronized(lock) {
            check(!closed)
            if (signal != null) return
            val cancellation = CancellationSignal()
            signal = cancellation
            val cycle = ++generation
            running = worker.submit { sweep(cycle, cancellation) }
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            signal?.cancel()
            signal = null
            running?.cancel(true)
            running = null
        }
    }

    /**
     * The host's preview is gone but its operation is still open: ask the host once every few
     * seconds, a bounded number of times per entry, whether it restored the original mode.
     * Read-only GETs; nothing is replayed, and it stops when the entry settles or the cap is hit.
     */
    fun reviewEndedPreviews(nowNanos: Long) {
        synchronized(lock) {
            val cancellation = signal ?: return
            if (closed || running?.isDone == false || nowNanos - lastEndedPreviewSweep < ENDED_PREVIEW_INTERVAL_NANOS) return
            lastEndedPreviewSweep = nowNanos
            val cycle = generation
            running = worker.submit { sweepEndedPreviews(cycle, cancellation) }
        }
    }

    /**
     * Async host operations (restore, revert, Sunshine restart) reply `accepted` and finish a moment later.
     * Until the app asked again, that entry blocked every other action. Ask every few seconds while the
     * paired screen is visible, a bounded number of times per entry. Read-only GETs; nothing is replayed.
     */
    fun reviewOpenOperations(nowNanos: Long) {
        synchronized(lock) {
            val cancellation = signal ?: return
            if (closed || running?.isDone == false || nowNanos - lastOpenSweep < OPEN_INTERVAL_NANOS) return
            lastOpenSweep = nowNanos
            val cycle = generation
            running = worker.submit { sweepOpenOperations(cycle, cancellation) }
        }
    }

    private fun sweepOpenOperations(cycle: Long, cancellation: CancellationSignal) {
        val entries = (repository.load() as? StateLoadResult.Ready)?.state?.journal.orEmpty()
            .filter { it.blocksLane && it.operationId != null && it.path != ApiRoute.DISPLAY_PREVIEW.path &&
                it.belongsTo(host) }
            .map { it.localGeneration }
            .filter { synchronized(lock) { openAttempts.merge(it, 1, Int::plus)!! } <= MAX_OPEN_ATTEMPTS }
        for (localGeneration in entries) {
            if (cancellation.isCancelled) return
            val result = reconciler.reconcile(host, localGeneration, cancellation)
            if (cancellation.isCancelled) return
            synchronized(lock) {
                if (!closed && signal === cancellation && generation == cycle) listener(localGeneration, result)
            }
        }
    }

    private fun sweepEndedPreviews(cycle: Long, cancellation: CancellationSignal) {
        val entries = (repository.load() as? StateLoadResult.Ready)?.state?.journal.orEmpty()
            .filter { it.phase.unresolved && it.operationId != null && it.path == ApiRoute.DISPLAY_PREVIEW.path &&
                it.belongsTo(host) }
            .map { it.localGeneration }
            .filter { synchronized(lock) { endedPreviewAttempts.merge(it, 1, Int::plus)!! } <= MAX_ENDED_PREVIEW_ATTEMPTS }
        for (localGeneration in entries) {
            if (cancellation.isCancelled) return
            val result = reconciler.reconcile(host, localGeneration, cancellation)
            if (cancellation.isCancelled) return
            synchronized(lock) {
                if (!closed && signal === cancellation && generation == cycle) listener(localGeneration, result)
            }
        }
    }

    private fun sweep(cycle: Long, cancellation: CancellationSignal) {
        val entries = (repository.load() as? StateLoadResult.Ready)?.state?.journal.orEmpty()
            .filter { it.phase.unresolved && it.operationId != null && it.belongsTo(host) }
            .map { it.localGeneration }
        for (localGeneration in entries) {
            if (cancellation.isCancelled) return
            val result = reconciler.reconcile(host, localGeneration, cancellation)
            if (cancellation.isCancelled) return
            synchronized(lock) {
                if (!closed && signal === cancellation && generation == cycle) listener(localGeneration, result)
            }
        }
    }

    private companion object {
        const val ENDED_PREVIEW_INTERVAL_NANOS = 4_000_000_000L
        const val MAX_ENDED_PREVIEW_ATTEMPTS = 6
        const val OPEN_INTERVAL_NANOS = 3_000_000_000L
        const val MAX_OPEN_ATTEMPTS = 20
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        worker.shutdownNow()
    }
}
