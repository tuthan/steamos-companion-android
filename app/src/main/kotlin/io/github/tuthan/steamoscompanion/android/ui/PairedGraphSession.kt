package io.github.tuthan.steamoscompanion.android.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.tuthan.steamoscompanion.android.CompanionRuntime
import io.github.tuthan.steamoscompanion.android.data.PairedNetworkMonitor
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StatusPoller
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** One foreground status owner for all paired destinations in a navigation graph. */
class PairedGraphSession(
    context: Context,
    host: ActiveHost,
    runtime: CompanionRuntime,
    private val onState: (StatusPollState) -> Unit,
    private val onWakeInterfaceChanged: () -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val lifecycleGeneration = AtomicLong()
    private var started = false
    private var closed = false
    private val poller = StatusPoller(host, runtime.statusGateway, runtime.clock::monotonicNanos) { state ->
        val generation = lifecycleGeneration.get()
        main.post { if (started && !closed && generation == lifecycleGeneration.get()) onState(state) }
    }
    private val network = PairedNetworkMonitor(context, poller, runtime.discoveryScanner) {
        val generation = lifecycleGeneration.get()
        main.post { if (started && !closed && generation == lifecycleGeneration.get()) onWakeInterfaceChanged() }
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
        } catch (error: RuntimeException) {
            started = false
            lifecycleGeneration.incrementAndGet()
            network.close()
            poller.stop()
            throw error
        }
    }

    /** Called by the paired graph's ON_STOP observer. */
    fun onStop() {
        requireMainThread()
        if (!started) return
        started = false
        lifecycleGeneration.incrementAndGet()
        network.close()
        poller.stop()
    }

    fun refresh() {
        requireMainThread()
        if (started && !closed) poller.refresh()
    }

    override fun close() {
        requireMainThread()
        if (closed) return
        onStop()
        closed = true
        poller.close()
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Paired graph lifecycle must run on the main thread" }
    }
}
