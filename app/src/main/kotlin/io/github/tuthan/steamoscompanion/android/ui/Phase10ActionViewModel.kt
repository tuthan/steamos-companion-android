package io.github.tuthan.steamoscompanion.android.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.tuthan.steamoscompanion.android.CompanionRuntime
import io.github.tuthan.steamoscompanion.android.data.DiagnosticWriteResult
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.JournalDispatchResult
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.ReplacementDiscardResult
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import java.util.concurrent.Executors

/** Foreground replacement, removal, and export operations with fixed user-facing outcomes. */
class Phase10ActionViewModel(private val runtime: CompanionRuntime) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-phase10-actions").apply { isDaemon = true }
    }
    private var revision = 0L
    var busy by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<ActionFeedback?>(null)
        private set

    fun confirmReplacement(expectedOld: ActiveHost, generation: Long) = execute {
        when (runtime.connectionReplacement.confirm(expectedOld, generation)) {
            is StateCommitResult.Saved -> "New pairing selected; checking its status" to StatusTone.GOOD
            is StateCommitResult.Rejected -> "Pairing changed; review the new device again" to StatusTone.WARNING
            else -> "Could not save the new pairing; current device remains selected" to StatusTone.WARNING
        }
    }

    fun discardReplacement(generation: Long) = execute {
        when (runtime.connectionReplacement.discard(generation, runtime.mutationSender)) {
            is ReplacementDiscardResult.RemovedOnHost -> "New pairing removed from the host; current device remains selected" to StatusTone.GOOD
            is ReplacementDiscardResult.HostRemovalUnconfirmed ->
                "New pairing discarded here; check the new host for remaining access" to StatusTone.WARNING
            is ReplacementDiscardResult.NotDiscarded ->
                "Could not discard the new pairing; current device remains selected" to StatusTone.WARNING
        }
    }

    fun revoke(host: ActiveHost) = execute {
        when (val result = runtime.connectionRemoval.revoke(host, runtime.mutationSender)) {
            is JournalDispatchResult.Finished -> if (result.entry.phase == JournalPhase.SUCCEEDED) {
                "Access removed on the host" to StatusTone.GOOD
            } else "Host removal was not confirmed; check the operation" to StatusTone.WARNING
            is JournalDispatchResult.ResultNotConfirmed ->
                "Host removal was not confirmed; check the operation" to StatusTone.WARNING
            else -> "Could not send the removal request" to StatusTone.WARNING
        }
    }

    fun forgetOnThisDevice(host: ActiveHost) = execute {
        when (runtime.connectionRemoval.forgetOnThisDevice(host)) {
            is StateCommitResult.Saved -> "Pairing forgotten on this phone" to StatusTone.GOOD
            is StateCommitResult.Rejected -> "Pairing changed; check the selected device" to StatusTone.WARNING
            else -> "Could not forget the pairing on this phone" to StatusTone.WARNING
        }
    }

    fun export(destination: Uri, observation: StatusPollState) = execute {
        when (val result = runtime.diagnosticDocumentWriter.write(destination, observation)) {
            is DiagnosticWriteResult.Saved -> "Sanitized diagnostics saved (${result.bytesWritten} bytes)" to StatusTone.GOOD
            DiagnosticWriteResult.PermissionDenied -> "Document permission denied" to StatusTone.WARNING
            DiagnosticWriteResult.DestinationUnavailable -> "Document location is unavailable" to StatusTone.WARNING
            DiagnosticWriteResult.InvalidDestination -> "Choose a document location" to StatusTone.WARNING
            DiagnosticWriteResult.ExportFailed, DiagnosticWriteResult.WriteFailed ->
                "Could not save sanitized diagnostics" to StatusTone.WARNING
        }
    }

    private fun execute(action: () -> Pair<String, StatusTone>) {
        if (busy) return
        busy = true
        worker.execute {
            val result = try { action() } catch (_: RuntimeException) {
                "Action could not be completed" to StatusTone.WARNING
            }
            main.post {
                busy = false
                feedback = ActionFeedback(result.first, result.second, ++revision)
            }
        }
    }

    override fun onCleared() {
        worker.shutdownNow()
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == Phase10ActionViewModel::class.java)
            return Phase10ActionViewModel(runtime) as T
        }
    }
}
