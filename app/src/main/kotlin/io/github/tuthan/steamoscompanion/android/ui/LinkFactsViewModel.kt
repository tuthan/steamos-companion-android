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
import io.github.tuthan.steamoscompanion.core.client.LinkFactsGateway
import io.github.tuthan.steamoscompanion.core.client.LinkFactsLoader
import io.github.tuthan.steamoscompanion.core.client.LinkFactsReading

/**
 * Holds the advisory DRM link facts for the Display screen. In memory only: the reading is replaced by
 * the next one and gone with the process; it is never saved or journaled, and only the owner's explicit
 * export composes a redacted report from it. Reads happen off
 * the main thread, once per display interaction, and nothing here can fail a status read or an action.
 */
class LinkFactsViewModel(
    gateway: LinkFactsGateway,
    monotonicNanos: () -> Long,
    minSpacingMillis: Long = LinkFactsLoader.MIN_SPACING_MILLIS,
) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cleared = false
    /** Runs once, on the main thread, after the next reading is applied. Main thread only. */
    private var afterNextReading: (() -> Unit)? = null

    private val loader = LinkFactsLoader(gateway, monotonicNanos, minSpacingMillis) { result ->
        main.post {
            if (!cleared) {
                reading = result
                afterNextReading?.let { hook -> afterNextReading = null; hook() }
            }
        }
    }

    var reading by mutableStateOf<LinkFactsReading?>(null)
        private set

    /** Asks for the facts of this pairing and output topology; the screen decides when, see [DisplayScreen]. */
    fun request(host: ActiveHost, topology: String) = loader.request(host, topology)

    /**
     * The owner asked for a diagnostics export: ask once more for the facts of this pairing and topology (the loader keeps its
     * 5 s spacing and ignores a read already pending). The reading already held stays in place until the new one arrives.
     * [afterNextReading] runs once that reading is applied.
     */
    fun requestForExport(host: ActiveHost, topology: String, afterNextReading: () -> Unit) {
        this.afterNextReading = afterNextReading
        loader.request(host, topology)
    }

    /** The pairing changed or went away: drop the reading and any read in flight. */
    fun clear() {
        afterNextReading = null
        loader.cancel()
        reading = null
    }

    override fun onCleared() {
        cleared = true
        afterNextReading = null
        loader.close()
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LinkFactsViewModel::class.java)
            return LinkFactsViewModel(runtime.linkFactsGateway, runtime.clock::monotonicNanos) as T
        }
    }
}
