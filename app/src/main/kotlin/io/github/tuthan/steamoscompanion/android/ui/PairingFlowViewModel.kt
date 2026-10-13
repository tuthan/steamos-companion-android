package io.github.tuthan.steamoscompanion.android.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.tuthan.steamoscompanion.android.CompanionRuntime
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanResult
import io.github.tuthan.steamoscompanion.core.discovery.ManualProbeResult
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** One foreground onboarding owner; all credential state remains in the encrypted core repository. */
class PairingFlowViewModel(private val runtime: CompanionRuntime) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-onboarding").apply { isDaemon = true }
    }
    private var pollVisible = false
    private var pollInFlight = false
    private var scanRevision = 0L
    private val pollTick = Runnable { pollOnce() }
    var scanning by mutableStateOf(false)
        private set
    var discovery by mutableStateOf<DiscoveryScanResult?>(null)
        private set
    var selected by mutableStateOf<UnverifiedDiscoveryCandidate?>(null)
        private set
    var pairing by mutableStateOf<PairingAction>(PairingAction.Idle)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun scan() {
        if (scanning) return
        val revision = ++scanRevision
        scanning = true
        message = null
        worker.execute {
            val result = runCatching { runtime.discoveryScanner.scan() }
            main.post {
                if (revision != scanRevision) return@post
                scanning = false
                result.onSuccess { discovery = it }
                    .onFailure { message = "Could not scan this network" }
            }
        }
    }

    fun cancelScan() {
        scanRevision++
        runtime.discoveryScanner.cancelActiveScan()
        scanning = false
    }

    fun probeManual(endpoint: String) {
        if (busy) return
        busy = true
        message = null
        worker.execute {
            val result = try {
                val path = runtime.networkAccess.activeLanPaths().firstOrNull()
                if (path == null) ManualProbeResult.Failed("No local network")
                else runtime.discoveryScanner.probeManual(endpoint, path)
            } catch (_: RuntimeException) { ManualProbeResult.Failed("Connection failed") }
            main.post {
                busy = false
                when (result) {
                    is ManualProbeResult.Candidate -> selected = result.value
                    else -> message = manualProbeMessage(result)
                }
            }
        }
    }

    fun select(candidate: UnverifiedDiscoveryCandidate) { selected = candidate; message = null }

    fun startNonce() {
        val candidate = selected ?: return
        if (busy) return
        busy = true
        consume(runtime.pairingCoordinator.startNonce(candidate))
    }

    fun startPayload(payload: String) {
        if (busy) return
        busy = true
        val networkId = runCatching { runtime.networkAccess.activeLanPaths().firstOrNull()?.id }.getOrNull()
        consume(runtime.pairingCoordinator.startPayload(payload, networkId))
    }

    fun resume() { consume(runtime.pairingCoordinator.resume()) }

    fun cancelPairing() {
        val candidate = candidate() ?: return
        if (busy) return
        busy = true
        stopPolling()
        consume(runtime.pairingCoordinator.cancel(candidate.generation))
    }

    fun startPolling() {
        if (pollVisible) return
        pollVisible = true
        main.removeCallbacks(pollTick)
        main.post(pollTick)
    }

    fun stopPolling() {
        pollVisible = false
        main.removeCallbacks(pollTick)
    }

    fun checkNow() {
        if (!pollVisible) return
        main.removeCallbacks(pollTick)
        main.post(pollTick)
    }

    private fun pollOnce() {
        if (!pollVisible || pollInFlight || busy) return
        val candidate = candidate() ?: return
        if (pairing !is PairingAction.Pending && pairing !is PairingAction.RateLimited) return
        val delay = (pairing as? PairingAction.RateLimited)?.let {
            (it.retryAtMillis - runtime.clock.wallTimeMillis()).coerceAtLeast(0)
        } ?: 0L
        if (delay > 0) {
            main.postDelayed(pollTick, delay.coerceAtMost(MAX_POLL_WAIT_MS))
            return
        }
        pollInFlight = true
        runtime.pairingCoordinator.poll(candidate.generation).whenComplete { result, error ->
            main.post {
                pollInFlight = false
                if (error == null && result != null) pairing = result
                else message = "Could not check pairing approval"
                if (pollVisible && (pairing is PairingAction.Pending || pairing is PairingAction.RateLimited)) {
                    main.postDelayed(pollTick, POLL_INTERVAL_MS)
                }
            }
        }
    }

    private fun consume(future: CompletableFuture<PairingAction>) {
        future.whenComplete { result, error ->
            main.post {
                busy = false
                if (error == null && result != null) pairing = result
                else message = "Could not continue pairing"
                if (pollVisible && pairing is PairingAction.Pending) main.post(pollTick)
            }
        }
    }

    private fun candidate() = when (val value = pairing) {
        is PairingAction.Ready -> value.candidate
        is PairingAction.Pending -> value.candidate
        is PairingAction.RequestUnconfirmed -> value.candidate
        is PairingAction.Retryable -> value.candidate
        is PairingAction.RateLimited -> value.candidate
        is PairingAction.Rejected -> value.candidate
        is PairingAction.Expired -> value.candidate
        is PairingAction.NeedsAttention -> value.candidate
        is PairingAction.InProgress -> value.candidate
        is PairingAction.UnsupportedState -> value.candidate
        is PairingAction.CredentialCouldNotBeSaved -> value.candidate
        else -> null
    }

    override fun onCleared() {
        stopPolling()
        runtime.discoveryScanner.cancelActiveScan()
        worker.shutdownNow()
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PairingFlowViewModel::class.java)
            return PairingFlowViewModel(runtime) as T
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLL_WAIT_MS = 30_000L
    }
}

/** What a manual address check that found nothing says; the API 36 case that cannot prove the restriction says so. */
internal fun manualProbeMessage(result: ManualProbeResult): String? = when {
    result is ManualProbeResult.Candidate -> null
    result is ManualProbeResult.Failed && result.qualifiedPermission -> LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE
    else -> "No SteamOS Companion server responded at this address"
}
