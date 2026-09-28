package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.DisplayPreviewSummary
import java.math.BigDecimal
import java.math.RoundingMode

enum class PendingDisplayResult {
    APPLYING_PREVIEW, CHECKING_PREVIEW, CHECKING_SAVE, CHECKING_SAVE_CURRENT, CHECKING_RESTORATION,
    REVIEW_LEGACY_OPERATION,
}

sealed interface PreviewPresentation {
    data object None : PreviewPresentation
    data class Pending(val result: PendingDisplayResult, val localGeneration: Long) : PreviewPresentation
    data class Active(
        /** Semantic mode facts come from the host preview, not a mode-list row index. */
        val preview: DisplayPreviewSummary,
        val ownedByThisPairing: Boolean,
        val unprovenOperationNeedsReview: Boolean,
        val remainingSeconds: Long?,
        val checkingRestoration: Boolean,
        val connectionLost: Boolean,
        val keepAvailable: Boolean,
        val revertAvailable: Boolean,
    ) : PreviewPresentation
}

data class DisplayFailurePresentation(
    val localGeneration: Long,
    val route: ApiRoute,
    val reason: JournalRejectionCode?,
)

/** Rebuilds the preview card after navigation or process recreation; it never infers restoration from time alone. */
object DisplayRecoveryPresentation {
    /** Latest rejected display action for this exact pairing, including after process recreation. */
    fun latestFailure(host: ActiveHost, state: PersistentState): DisplayFailurePresentation? {
        val active = state.activeHost ?: return null
        if (StatusObservationSource.from(active) != StatusObservationSource.from(host)) return null
        val entry = state.journal.asSequence()
            .filter { it.belongsTo(active) && it.path in DISPLAY_ROUTES }
            .maxByOrNull { it.localGeneration } ?: return null
        if (entry.phase != JournalPhase.FAILED) return null
        val route = ApiRoute.match(io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST, entry.path).route
        return DisplayFailurePresentation(entry.localGeneration, route, entry.rejectionCode)
    }

    fun project(
        host: ActiveHost,
        state: PersistentState,
        observation: StatusPollState,
        nowMonotonicNanos: Long,
    ): PreviewPresentation {
        val active = state.activeHost ?: return PreviewPresentation.None
        if (StatusObservationSource.from(active) != StatusObservationSource.from(host)) return PreviewPresentation.None
        val entries = state.journal.filter { it.belongsTo(active) }
        val possibleConflicts = state.journal.filter { it.mayConflictWith(active) }
        val unprovenConflict = possibleConflicts.any { it.phase.unresolved && !it.belongsTo(active) }
        val read = observation.lastRead?.takeIf {
            observation.source == StatusObservationSource.from(active) && it.status.hostId == active.hostId
        }
        val preview = read?.outputs?.takeIf { it.available && !it.legacyShape }?.preview
        if (preview != null) {
            val owned = entries.any { it.path == ApiRoute.DISPLAY_PREVIEW.path && it.operationId == preview.id }
            val conflicting = possibleConflicts.any { it.phase.unresolved &&
                !(it.belongsTo(active) && it.path == ApiRoute.DISPLAY_PREVIEW.path && it.operationId == preview.id) }
            val deadlineMillis = try {
                BigDecimal(preview.deadlineSeconds).movePointRight(3).setScale(0, RoundingMode.DOWN).longValueExact()
            } catch (_: Exception) { null }
            val anchor = deadlineMillis?.let { HostDeadlineAnchor.fromHostReply(it, read.outputsTiming) }
            val remainingMillis = anchor?.remainingMillis(nowMonotonicNanos)
            val fresh = !observation.unreachable && !observation.stale &&
                observation.lastSuccessMonotonicNanos?.let {
                    val age = nowMonotonicNanos - it
                    age in 0..FRESH_NANOS
                } == true
            return PreviewPresentation.Active(
                preview = preview,
                ownedByThisPairing = owned,
                unprovenOperationNeedsReview = unprovenConflict,
                remainingSeconds = anchor?.remainingSeconds(nowMonotonicNanos),
                checkingRestoration = remainingMillis == 0L,
                connectionLost = observation.unreachable,
                keepAvailable = owned && !conflicting && fresh && remainingMillis != null && remainingMillis > 0,
                revertAvailable = owned && !conflicting,
            )
        }
        val pending = possibleConflicts.filter { it.phase.unresolved && it.path in DISPLAY_ROUTES }
            .maxByOrNull { it.localGeneration } ?: return PreviewPresentation.None
        val result = if (!pending.belongsTo(active)) PendingDisplayResult.REVIEW_LEGACY_OPERATION else when (pending.path) {
            ApiRoute.DISPLAY_PREVIEW.path -> if (pending.operationId == null) {
                PendingDisplayResult.APPLYING_PREVIEW
            } else PendingDisplayResult.CHECKING_PREVIEW
            ApiRoute.DISPLAY_CONFIRM.path -> PendingDisplayResult.CHECKING_SAVE
            ApiRoute.DISPLAY_SAVE_CURRENT.path -> PendingDisplayResult.CHECKING_SAVE_CURRENT
            else -> PendingDisplayResult.CHECKING_RESTORATION
        }
        return PreviewPresentation.Pending(result, pending.localGeneration)
    }

    private val DISPLAY_ROUTES = setOf(
        ApiRoute.DISPLAY_PREVIEW.path,
        ApiRoute.DISPLAY_CONFIRM.path,
        ApiRoute.DISPLAY_SAVE_CURRENT.path,
        ApiRoute.DISPLAY_RESTORE.path,
    )
    private const val FRESH_NANOS = 15_000_000_000L
}
