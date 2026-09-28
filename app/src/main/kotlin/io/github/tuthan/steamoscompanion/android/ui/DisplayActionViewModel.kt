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
import io.github.tuthan.steamoscompanion.core.client.DisplayBlock
import io.github.tuthan.steamoscompanion.core.client.DisplayDispatchResult
import io.github.tuthan.steamoscompanion.core.client.DisplaySelection
import io.github.tuthan.steamoscompanion.core.client.JournalDispatchResult
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.JournalRejectionCode
import io.github.tuthan.steamoscompanion.core.client.KeepPreviewConfirmation
import io.github.tuthan.steamoscompanion.core.client.SaveCurrentConfirmation
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import java.util.concurrent.Executors

/** Keeps display POSTs off the main thread and never retries an ambiguous result. */
class DisplayActionViewModel(private val runtime: CompanionRuntime) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-display-actions").apply { isDaemon = true }
    }
    private var revision = 0L
    var busy by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<ActionFeedback?>(null)
        private set

    fun preview(host: ActiveHost, observation: StatusPollState, selection: DisplaySelection) = execute {
        runtime.displayDispatcher.preview(host, observation, selection, runtime.mutationSender)
    }

    fun saveCurrent(host: ActiveHost, observation: StatusPollState, confirmation: SaveCurrentConfirmation) = execute {
        runtime.displayDispatcher.saveCurrent(host, observation, confirmation, runtime.mutationSender)
    }

    fun restoreSaved(host: ActiveHost, observation: StatusPollState, profileId: String) = execute {
        runtime.displayDispatcher.restoreSaved(host, observation, profileId, runtime.mutationSender)
    }

    fun revert(host: ActiveHost, observation: StatusPollState) = execute {
        runtime.displayDispatcher.revertPreview(host, observation, runtime.mutationSender)
    }

    fun keep(host: ActiveHost, observation: StatusPollState, confirmation: KeepPreviewConfirmation) = execute {
        runtime.displayDispatcher.keepPreview(host, observation, confirmation, runtime.mutationSender)
    }

    private fun execute(action: () -> DisplayDispatchResult) {
        if (busy) return
        busy = true
        worker.execute {
            val result = try { action().message() } catch (_: RuntimeException) {
                "Could not send the display request" to StatusTone.WARNING
            }
            main.post {
                busy = false
                feedback = ActionFeedback(result.first, result.second, ++revision)
            }
        }
    }

    private fun DisplayDispatchResult.message(): Pair<String, StatusTone> = when (this) {
        is DisplayDispatchResult.Blocked -> when (reason) {
            DisplayBlock.STALE_SELECTION -> "Display changed; choose the output and mode again" to StatusTone.WARNING
            DisplayBlock.PROFILE_MISSING -> "Saved recovery mode is no longer available" to StatusTone.WARNING
            DisplayBlock.PREVIEW_ACTIVE -> "Keep or revert the current preview first" to StatusTone.WARNING
            DisplayBlock.PREVIEW_NOT_OWNED -> "This preview cannot be controlled from this pairing" to StatusTone.WARNING
            DisplayBlock.PREVIEW_ENDED -> "Preview time ended; checking restoration" to StatusTone.WARNING
            DisplayBlock.PREVIEW_DEADLINE_UNKNOWN -> "Cannot confirm the preview deadline" to StatusTone.WARNING
            DisplayBlock.OPERATION_UNRESOLVED -> "Another display operation needs review" to StatusTone.WARNING
            DisplayBlock.PAIRING_CHANGED, DisplayBlock.OBSERVATION_PAIRING_CHANGED,
            DisplayBlock.CONFIRMATION_MISMATCH -> "Device identity or display changed; check it again" to StatusTone.WARNING
            DisplayBlock.STATUS_NOT_FRESH -> "Check the latest display status before sending" to StatusTone.WARNING
            else -> "Display action unavailable in the current state" to StatusTone.WARNING
        }
        is DisplayDispatchResult.Dispatched -> when (val outcome = result) {
            is JournalDispatchResult.Finished -> when (outcome.entry.phase) {
                JournalPhase.FAILED -> failure(outcome.entry.rejectionCode) to StatusTone.WARNING
                JournalPhase.SUCCEEDED -> "Host reported completion; checking the current display" to StatusTone.GOOD
                else -> "Request recorded; checking the operation and display" to StatusTone.UNKNOWN
            }
            is JournalDispatchResult.ResultNotConfirmed -> "Result not confirmed; check the original operation" to StatusTone.WARNING
            else -> "Could not record the display request" to StatusTone.WARNING
        }
    }

    override fun onCleared() {
        worker.shutdownNow()
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == DisplayActionViewModel::class.java)
            return DisplayActionViewModel(runtime) as T
        }
    }
}

fun failure(code: JournalRejectionCode?): String = when (code) {
    JournalRejectionCode.SAVE_CURRENT_UNSUPPORTED -> "This host cannot save the current display as a recovery mode"
    JournalRejectionCode.PROFILE_NOT_FOUND -> "Saved recovery mode is no longer available"
    JournalRejectionCode.STALE_TARGET -> "Display changed; refresh and choose it again"
    JournalRejectionCode.MUTATION_CONFLICT -> "Another operation is in progress"
    JournalRejectionCode.BRIDGE_UNAVAILABLE -> "Display bridge is unavailable"
    JournalRejectionCode.READBACK_NOT_CONFIRMED -> "Could not confirm display recovery from readback"
    JournalRejectionCode.PREVIEW_NOT_FOUND -> "Preview is no longer available"
    JournalRejectionCode.PREVIEW_EXPIRED -> "Preview time ended; checking restoration"
    JournalRejectionCode.FORBIDDEN -> "Display control permission was rejected"
    JournalRejectionCode.OPERATION_FAILED, JournalRejectionCode.OTHER_REJECTION, null ->
        "Host did not confirm the display request"
}
