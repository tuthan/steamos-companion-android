package io.github.tuthan.steamoscompanion.android.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.tuthan.steamoscompanion.android.CompanionRuntime
import io.github.tuthan.steamoscompanion.android.data.PairedNetworkMonitor
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.ForegroundOperationReview
import io.github.tuthan.steamoscompanion.core.client.ReconcileResult
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StatusPoller
import io.github.tuthan.steamoscompanion.core.wake.WakeAttempt
import io.github.tuthan.steamoscompanion.core.wake.WakeAttemptState
import io.github.tuthan.steamoscompanion.core.wake.WakeSendResult
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** One foreground status owner for all paired destinations in a navigation graph. */
class PairedGraphSession(
    context: Context,
    private val host: ActiveHost,
    runtime: CompanionRuntime,
    private val onState: (StatusPollState) -> Unit,
    private val onWakeInterfaceChanged: () -> Unit,
    private val onWakeState: (WakeAttemptState?) -> Unit = {},
    private val onOperationReview: (Long, ReconcileResult) -> Unit = { _, _ -> },
    private val onJournalChanged: () -> Unit = {},
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val clock = runtime.clock
    private val lifecycleGeneration = AtomicLong()
    private var started = false
    private var closed = false
    private var wakeAttempt: WakeAttempt? = null
    private val wakeTick = object : Runnable {
        override fun run() {
            val attempt = wakeAttempt ?: return
            if (!started || closed) return
            val before = attempt.state
            val after = attempt.tick(clock.monotonicNanos())
            if (after != before) onWakeState(after)
            if (!after.authenticatedResponse && !after.timedOut && after.packet !is WakeSendResult.Failed) {
                main.postDelayed(this, WAKE_TICK_MILLIS)
            }
        }
    }
    private val poller = StatusPoller(host, runtime.statusGateway, runtime.clock::monotonicNanos) { state ->
        val generation = lifecycleGeneration.get()
        // Off the main thread: an accepted power request finishes when the device drops off or returns.
        val settled = try { runtime.powerSettlement.observe(host, state) } catch (_: RuntimeException) { false }
        main.post {
            if (started && !closed && generation == lifecycleGeneration.get()) {
                if (settled) onJournalChanged()
                onState(state)
                wakeAttempt?.let { attempt ->
                    val before = attempt.state
                    val after = attempt.observe(state, clock.monotonicNanos())
                    if (after != before) onWakeState(after)
                    if (after.authenticatedResponse) main.removeCallbacks(wakeTick)
                }
            }
        }
    }
    private val network = PairedNetworkMonitor(context, poller, runtime.discoveryScanner) {
        val generation = lifecycleGeneration.get()
        main.post { if (started && !closed && generation == lifecycleGeneration.get()) onWakeInterfaceChanged() }
    }
    private val operations = ForegroundOperationReview(runtime.stateRepository, runtime.operationReconciler, host) {
            localGeneration, result ->
        val generation = lifecycleGeneration.get()
        main.post {
            if (started && !closed && generation == lifecycleGeneration.get()) {
                onOperationReview(localGeneration, result)
            }
        }
    }

    val state: StatusPollState get() = poller.state

    /** Called by the paired graph's ON_START observer. */
    fun onStart() {
        requireMainThread()
        check(!closed)
        if (started) return
        started = true
        lifecycleGeneration.incrementAndGet()
        try {
            network.start()
            poller.start()
            operations.start()
        } catch (error: RuntimeException) {
            started = false
            lifecycleGeneration.incrementAndGet()
            network.close()
            poller.stop()
            operations.stop()
            throw error
        }
    }

    /** Called by the paired graph's ON_STOP observer. */
    fun onStop() {
        requireMainThread()
        if (!started) return
        started = false
        lifecycleGeneration.incrementAndGet()
        clearWakeObservation()
        operations.stop()
        network.close()
        poller.stop()
    }

    /** Called only after an explicit Wake send completes; packet transmission is its own fact. */
    fun beginWakeObservation(packet: WakeSendResult) {
        requireMainThread()
        check(started && !closed) { "Wake observation requires a visible paired session" }
        main.removeCallbacks(wakeTick)
        wakeAttempt = WakeAttempt(host, clock.monotonicNanos(), packet)
        onWakeState(wakeAttempt!!.state)
        if (packet !is WakeSendResult.Failed) {
            poller.refresh()
            main.postDelayed(wakeTick, WAKE_TICK_MILLIS)
        }
    }

    /** Leaving Wake stops its countdown; reopening never sends or resumes a packet. */
    fun clearWakeObservation() {
        requireMainThread()
        main.removeCallbacks(wakeTick)
        if (wakeAttempt != null) {
            wakeAttempt = null
            onWakeState(null)
        }
    }

    fun refresh() {
        requireMainThread()
        if (started && !closed) poller.refresh()
    }

    /** A visible user request repeats only the authenticated operation GET sweep. */
    fun reviewOperations() {
        requireMainThread()
        if (!started || closed) return
        operations.stop()
        operations.start()
    }

    /** An accepted host operation is still open; see whether the host has finished it. */
    fun reviewOpenOperations() {
        requireMainThread()
        if (started && !closed) operations.reviewOpenOperations(clock.monotonicNanos())
    }

    /** A preview vanished from the host but its operation is still open; see if the host restored it. */
    fun reviewEndedPreview() {
        requireMainThread()
        if (started && !closed) operations.reviewEndedPreviews(clock.monotonicNanos())
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        onStop()
        closed = true
        poller.close()
        operations.close()
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Paired graph lifecycle must run on the main thread" }
    }

    private companion object { const val WAKE_TICK_MILLIS = 1_000L }
}
