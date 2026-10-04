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
import io.github.tuthan.steamoscompanion.core.client.HostJournalGateway
import io.github.tuthan.steamoscompanion.core.client.HostJournalLoader
import io.github.tuthan.steamoscompanion.core.client.HostJournalState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource

/**
 * Holds the host operation journal for Connection details. In memory only: the state is replaced by the next
 * request, dropped when the pairing changes, and gone with the process; it is never saved or journaled, and
 * only the owner's explicit export composes a redacted report from it. Reads happen off the main thread, only
 * when the screen opens, the owner asks again, or the owner exports, and nothing here can fail a status read
 * or an action.
 */
class HostJournalViewModel(
    gateway: HostJournalGateway,
    monotonicNanos: () -> Long,
    minSpacingMillis: Long = HostJournalLoader.MIN_SPACING_MILLIS,
) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cleared = false

    /** The pairing the last request was for; a result for any other pairing, or after [clear], is dropped. Main thread only. */
    private var wanted: StatusObservationSource? = null

    /** Runs once, on the main thread, after the next result for the wanted pairing is applied. Main thread only. */
    private var afterNextResult: (() -> Unit)? = null

    private val loader = HostJournalLoader(gateway, monotonicNanos, minSpacingMillis) { result ->
        main.post {
            if (!cleared && result.source == wanted) {
                state = result
                afterNextResult?.let { hook -> afterNextResult = null; hook() }
            }
        }
    }

    var state by mutableStateOf<HostJournalState?>(null)
        private set

    /** Shows "loading" for this pairing and asks for the journal. The screen decides when: on entry and on Refresh. */
    fun request(host: ActiveHost) {
        val source = StatusObservationSource.from(host)
        wanted = source
        state = HostJournalState.Loading(source)
        loader.request(host)
    }

    /**
     * The owner asked for a diagnostics export: ask once more for this pairing's journal (the loader keeps its 5 s spacing
     * and ignores a read already pending), but unlike [request] leave the records already held in place, so a read that
     * does not finish in time cannot take them away from the report. [afterNextResult] runs once the next result is applied.
     */
    fun requestForExport(host: ActiveHost, afterNextResult: () -> Unit) {
        wanted = StatusObservationSource.from(host)
        this.afterNextResult = afterNextResult
        loader.request(host)
    }

    /** The pairing changed or went away: drop the records and any read in flight. */
    fun clear() {
        wanted = null
        afterNextResult = null
        loader.cancel()
        state = null
    }

    override fun onCleared() {
        cleared = true
        wanted = null
        afterNextResult = null
        loader.close()
        state = null
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == HostJournalViewModel::class.java)
            return HostJournalViewModel(runtime.hostJournalGateway, runtime.clock::monotonicNanos) as T
        }
    }
}
