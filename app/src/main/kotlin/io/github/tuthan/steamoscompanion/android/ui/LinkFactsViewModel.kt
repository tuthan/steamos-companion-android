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
import io.github.tuthan.steamoscompanion.core.client.LinkFactsLoader
import io.github.tuthan.steamoscompanion.core.client.LinkFactsReading

/**
 * Holds the advisory DRM link facts for the Display screen. In memory only: the reading is replaced by
 * the next one and gone with the process; it is never saved, journaled, or exported. Reads happen off
 * the main thread, once per display interaction, and nothing here can fail a status read or an action.
 */
class LinkFactsViewModel(runtime: CompanionRuntime) : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cleared = false
    private val loader = LinkFactsLoader(runtime.linkFactsGateway, runtime.clock::monotonicNanos) { result ->
        main.post { if (!cleared) reading = result }
    }

    var reading by mutableStateOf<LinkFactsReading?>(null)
        private set

    /** Asks for the facts of this pairing and output topology; the screen decides when, see [DisplayScreen]. */
    fun request(host: ActiveHost, topology: String) = loader.request(host, topology)

    /** The pairing changed or went away: drop the reading and any read in flight. */
    fun clear() {
        loader.cancel()
        reading = null
    }

    override fun onCleared() {
        cleared = true
        loader.close()
        super.onCleared()
    }

    class Factory(private val runtime: CompanionRuntime) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LinkFactsViewModel::class.java)
            return LinkFactsViewModel(runtime) as T
        }
    }
}
