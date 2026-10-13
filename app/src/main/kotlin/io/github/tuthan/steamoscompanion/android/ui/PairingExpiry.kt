package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction

/** What the compare screen shows under the code: a countdown, then Expired. Never a negative time. */
internal sealed interface PairingExpiryView {
    /** [remainingSeconds] is rounded down, so the screen never claims more time than the request has. */
    data class Counting(val remainingSeconds: Long, val urgent: Boolean) : PairingExpiryView {
        val text: String get() = PairingExpiry.format(remainingSeconds)
    }

    /** The deadline passed. The line freezes here, in red; a retry is a new request with a new code. */
    data object Expired : PairingExpiryView
}

internal object PairingExpiry {
    /**
     * [expiresAtMillis] is the candidate's effective deadline (the earliest of the local, host and payload deadlines the
     * coordinator already enforces); [nowMillis] is wall time. A candidate with no deadline shows nothing.
     */
    fun view(expiresAtMillis: Long?, nowMillis: Long): PairingExpiryView? {
        val deadline = expiresAtMillis ?: return null
        val remainingMillis = deadline - nowMillis
        if (remainingMillis <= 0) return PairingExpiryView.Expired
        return PairingExpiryView.Counting(
            remainingSeconds = remainingMillis / 1_000,
            urgent = remainingMillis <= CompanionMotion.PAIRING_AMBER_LAST_SECONDS * 1_000L,
        )
    }

    /** `m:ss`, so 117 seconds reads "1:57". */
    fun format(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
    }

    /** The countdown belongs to a request that is still alive; an approved, declined or cancelled one shows no timer. */
    fun showsFor(action: PairingAction): Boolean = when (action) {
        is PairingAction.Ready, is PairingAction.Pending, is PairingAction.RateLimited,
        is PairingAction.RequestUnconfirmed, is PairingAction.InProgress, is PairingAction.Expired -> true
        else -> false
    }
}

/** The pairing explainer rail (`StepRail`): which steps this pairing variant has, and where the screen is in them. */
internal object PairingSteps {
    const val REVIEW = "Review the device"
    const val REQUEST = "Request pairing"
    const val COMPARE = "Compare the code"
    const val APPROVE = "Approve on the other device"

    /** The payload variant has no code to compare, so its rail has one step fewer. */
    fun steps(variant: PairingVariant?): List<String> =
        if (variant == PairingVariant.ADVANCED_PAYLOAD) listOf(REVIEW, REQUEST, APPROVE)
        else listOf(REVIEW, REQUEST, COMPARE, APPROVE)

    /** Before the request is sent the rail rests on the review; once it is out, on the code (or the approval). */
    fun activeIndex(variant: PairingVariant?, action: PairingAction?): Int {
        val last = steps(variant).lastIndex
        return when (action) {
            null -> 0
            is PairingAction.Ready, is PairingAction.InProgress -> 1
            else -> if (variant == PairingVariant.ADVANCED_PAYLOAD) last else last - 1
        }
    }
}
