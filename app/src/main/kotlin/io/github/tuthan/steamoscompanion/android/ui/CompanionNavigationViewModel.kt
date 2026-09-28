package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Saves only allowlisted route data. Credentials and operation state are reopened from encrypted storage. */
class CompanionNavigationViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private var graph = savedState.get<String>(NAVIGATION_KEY)?.let { serialized ->
        runCatching { PairedNavigationGraph.restore(serialized) }.getOrNull()
    } ?: PairedNavigationGraph()

    var route by mutableStateOf(graph.current)
        private set

    val canGoBack: Boolean get() = graph.routes.size > 1

    fun initialize(hasActiveHost: Boolean) {
        if (savedState.get<String>(NAVIGATION_KEY) != null) {
            if (!hasActiveHost && route.destination.paired) reset(UiDestination.ONBOARDING)
            return
        }
        if (hasActiveHost) reset(UiDestination.REMOTE_OVERVIEW)
    }

    fun navigate(destination: UiDestination, arguments: Map<String, String> = emptyMap()) {
        if (graph.navigate(UiRoute(destination, arguments))) publish()
    }

    fun back(): Boolean {
        if (!graph.back()) return false
        publish()
        return true
    }

    fun paired() = reset(UiDestination.REMOTE_OVERVIEW)

    fun unpaired() = reset(UiDestination.ONBOARDING)

    fun showPairing() {
        if (route.destination == UiDestination.PAIRING) return
        if (route.destination in setOf(UiDestination.CANDIDATE_DETAILS, UiDestination.SCANNER)) {
            navigate(UiDestination.PAIRING)
        } else reset(UiDestination.PAIRING)
    }

    private fun reset(destination: UiDestination) {
        graph = PairedNavigationGraph(UiRoute(destination))
        publish()
    }

    private fun publish() {
        route = graph.current
        savedState[NAVIGATION_KEY] = graph.saveState()
    }

    private companion object { const val NAVIGATION_KEY = "companion_navigation_v1" }
}
