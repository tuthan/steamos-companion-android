package io.github.tuthan.steamoscompanion.android.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.tuthan.steamoscompanion.android.CompanionRuntime
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.client.JournalUpdateResult
import io.github.tuthan.steamoscompanion.core.client.PowerVerb
import io.github.tuthan.steamoscompanion.core.client.ControlBlock
import io.github.tuthan.steamoscompanion.core.client.ControlDispatchResult
import io.github.tuthan.steamoscompanion.core.client.JournalDispatchResult
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.PowerConfirmation
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.ports.HostPaths
import io.github.tuthan.steamoscompanion.core.wake.WakeSendFailure
import io.github.tuthan.steamoscompanion.core.wake.WakeSender
import io.github.tuthan.steamoscompanion.core.wake.WakeSendResult
import java.util.concurrent.Executors

data class ActionFeedback(val text: String, val tone: StatusTone, val revision: Long)

/** Mutations run off the main thread; their journal and status observation stay separate. */
class Phase08ActionViewModel(private val runtime: CompanionRuntime) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-phase08-actions").apply { isDaemon = true }
    }
    private var revision = 0L
    var busy by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<ActionFeedback?>(null)
        private set

    fun power(host: ActiveHost, confirmation: PowerConfirmation, observation: StatusPollState) {
        execute {
            runtime.controlDispatcher.power(host, confirmation, observation, runtime.mutationSender)
                .message(restart = confirmation.verb == PowerVerb.RESTART)
        }
    }

    fun acknowledgeUnresolved(host: ActiveHost) = execute {
        when (runtime.operationJournal.acknowledgeUnresolved(host)) {
            JournalUpdateResult.Saved -> "Marked as reviewed; actions are available again" to StatusTone.GOOD
            else -> "Could not mark the operation as reviewed" to StatusTone.WARNING
        }
    }

    fun recoverSunshine(host: ActiveHost, observation: StatusPollState) {
        execute {
            runtime.controlDispatcher.recoverSunshine(host, observation, runtime.mutationSender).message()
        }
    }

    fun wake(host: ActiveHost, onPacket: (WakeSendResult) -> Unit) {
        if (busy) return
        busy = true
        worker.execute {
            val packet = try {
                val paths = runtime.networkAccess.activeLanPaths()
                val candidates = HostPaths.candidates(paths, host.endpoint, host.wakeTarget.interfaceName)
                if (candidates.isEmpty()) WakeSendResult.Failed(WakeSendFailure.NETWORK_MISSING)
                else runtime.wakeSender.send(host.wakeTarget, candidates, runtime.wakeRelayStore.get(host))
            } catch (_: SecurityException) {
                WakeSendResult.Failed(WakeSendFailure.PERMISSION)
            } catch (error: RuntimeException) {
                WakeSendResult.Failed(WakeSendFailure.SEND_FAILED, WakeSender.describe(error))
            }
            main.post {
                busy = false
                onPacket(packet)
            }
        }
    }

    private fun execute(action: () -> Pair<String, StatusTone>) {
        if (busy) return
        busy = true
        worker.execute {
            val result = try { action() } catch (_: RuntimeException) {
                "Could not send the request" to StatusTone.WARNING
            }
            main.post {
                busy = false
                feedback = ActionFeedback(result.first, result.second, ++revision)
            }
        }
    }

    private fun ControlDispatchResult.message(restart: Boolean = false): Pair<String, StatusTone> = when (this) {
        is ControlDispatchResult.Blocked -> when (reason) {
            ControlBlock.STATUS_NOT_FRESH -> "Check the latest device status before sending" to StatusTone.WARNING
            ControlBlock.PREVIEW_ACTIVE, ControlBlock.PREVIEW_UNVERIFIED ->
                "Check the display preview before sending" to StatusTone.WARNING
            ControlBlock.OPERATION_UNRESOLVED -> "Another operation needs review" to StatusTone.WARNING
            ControlBlock.SUNSHINE_MONITORING_OFF -> "Sunshine monitoring is off" to StatusTone.WARNING
            ControlBlock.SUNSHINE_PROVIDER_UNAVAILABLE -> "Sunshine recovery is unavailable" to StatusTone.WARNING
            ControlBlock.SUNSHINE_NOT_STOPPED -> "Sunshine is not stopped" to StatusTone.WARNING
            ControlBlock.PAIRING_CHANGED, ControlBlock.OBSERVATION_PAIRING_CHANGED,
            ControlBlock.CONFIRMATION_MISMATCH -> "Device identity changed; check it again" to StatusTone.WARNING
            else -> "Request unavailable; check the device status and permissions" to StatusTone.WARNING
        }
        is ControlDispatchResult.Dispatched -> when (val outcome = result) {
            is JournalDispatchResult.Finished -> when (outcome.entry.phase) {
                JournalPhase.FAILED -> "The host rejected the request" to StatusTone.WARNING
                JournalPhase.SUCCEEDED -> "Operation reported complete; checking device state" to StatusTone.GOOD
                // The host acknowledges a power request and Steam then carries it out; the device may drop off now.
                JournalPhase.ACCEPTED, JournalPhase.DISPATCHED, JournalPhase.OBSERVED_RETURN ->
                    if (outcome.entry.path == ApiRoute.POWER.path) (if (restart) "The device accepted the request. Waiting for it to restart and come back…"
                        else "The device accepted the request. It may disconnect now.") to StatusTone.GOOD
                    else "Request recorded; checking operation result" to StatusTone.UNKNOWN
                else -> "Request recorded; checking operation result" to StatusTone.UNKNOWN
            }
            is JournalDispatchResult.ResultNotConfirmed -> "Result not confirmed; check the operation" to StatusTone.WARNING
            else -> "Could not record the request" to StatusTone.WARNING
        }
    }

    override fun onCleared() {
        worker.shutdownNow()
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == Phase08ActionViewModel::class.java)
            return Phase08ActionViewModel(runtime) as T
        }
    }
}
