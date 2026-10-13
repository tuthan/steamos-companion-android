package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource

/** What the screen does when the saved state is read again: restart the paired session, and where to go. */
internal object PersistentReload {
    enum class Navigation { NONE, UNPAIRED, PAIRED }

    data class Decision(val restartSession: Boolean, val navigation: Navigation)

    private val unchanged = Decision(restartSession = false, navigation = Navigation.NONE)

    /**
     * [hadProblem] and [hasProblem] say whether the saved pairing was unreadable before and after this read. A reset (or a
     * retry that finds nothing paired) leaves the unpaired flow rather than a stale destination. Edit address and Find device
     * again move the endpoint of the same pairing: the session restarts on the new address but the owner stays where they are.
     */
    fun decide(hadProblem: Boolean, hasProblem: Boolean, current: ActiveHost?, next: ActiveHost?): Decision {
        if (current == next) {
            return if (hadProblem && !hasProblem && next == null) Decision(false, Navigation.UNPAIRED) else unchanged
        }
        val sameCredential = current != null && next != null &&
            StatusObservationSource.from(current) == StatusObservationSource.from(next)
        if (sameCredential) return unchanged
        val sameIdentity = current != null && next != null && current.hostId == next.hostId &&
            current.clientId == next.clientId && current.certificatePin == next.certificatePin &&
            current.pairedAtMillis == next.pairedAtMillis && current.bearerToken == next.bearerToken
        val navigation = when {
            next == null -> Navigation.UNPAIRED
            !sameIdentity -> Navigation.PAIRED
            else -> Navigation.NONE
        }
        return Decision(restartSession = true, navigation = navigation)
    }
}
