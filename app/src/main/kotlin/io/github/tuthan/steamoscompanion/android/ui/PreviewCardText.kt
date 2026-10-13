package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.DisplayObservation
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationCard
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationKind
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation

/** The countdown sentence a preview card shows. The deadline text never claims a restore that was not observed. */
internal enum class PreviewStatus {
    /** "Reverts in N seconds": the deadline is anchored to the host and still ahead. */
    REVERTS_IN,

    /** "Preview time ended · Checking restoration…": the deadline passed and the host still reports the preview. */
    ENDED_CHECKING,

    /** "Checking restoration…": no trustworthy deadline yet (after a restart, or a reply without usable timing). */
    CHECKING,
}

/** A state worth announcing once beside the countdown; the countdown itself never announces per tick. */
internal enum class PreviewNotice {
    /** Flows §9: the connection dropped, but the host restores the previous mode at its own deadline. */
    CONNECTION_LOST,

    /** The connection is still down after the deadline: restoration is unknown until the host is read again. */
    RESTORATION_NOT_CONFIRMED,
}

internal data class PreviewCardState(val status: PreviewStatus, val notice: PreviewNotice?)

internal fun previewCardState(preview: PreviewPresentation.Active): PreviewCardState {
    val status = when {
        preview.checkingRestoration -> PreviewStatus.ENDED_CHECKING
        preview.remainingSeconds != null -> PreviewStatus.REVERTS_IN
        else -> PreviewStatus.CHECKING
    }
    val notice = when {
        !preview.connectionLost -> null
        preview.checkingRestoration -> PreviewNotice.RESTORATION_NOT_CONFIRMED
        else -> PreviewNotice.CONNECTION_LOST
    }
    return PreviewCardState(status, notice)
}

@Composable
internal fun previewStatusText(state: PreviewCardState, remainingSeconds: Long?): String = when (state.status) {
    PreviewStatus.REVERTS_IN -> "Reverts in ${remainingSeconds ?: 0} seconds"
    PreviewStatus.ENDED_CHECKING -> stringResource(R.string.ui_preview_ended_checking)
    PreviewStatus.CHECKING -> "Checking restoration…"
}

@Composable
internal fun previewNoticeText(notice: PreviewNotice?): String? = when (notice) {
    null -> null
    PreviewNotice.CONNECTION_LOST -> stringResource(R.string.ui_preview_connection_lost)
    PreviewNotice.RESTORATION_NOT_CONFIRMED -> stringResource(R.string.ui_restoration_not_confirmed)
}

/**
 * Flows §9: a restore (a revert, or Restore saved display) that failed, or that is still open while the connection is
 * down, says "Couldn't confirm display recovery" and offers Try connection. A save, a keep or a preview never does.
 */
internal fun recoveryNotConfirmed(card: DisplayOperationCard): Boolean =
    (card.kind == DisplayOperationKind.REVERT_PREVIEW || card.kind == DisplayOperationKind.RESTORE_SAVED) &&
        (card.phase == JournalPhase.FAILED ||
            (card.phase.unresolved && card.observed == DisplayObservation.CONNECTION_LOST))
