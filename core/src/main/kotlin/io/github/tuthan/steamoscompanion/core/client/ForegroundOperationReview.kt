package io.github.tuthan.steamoscompanion.core.client

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

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        stop()
        worker.shutdownNow()
    }
}
