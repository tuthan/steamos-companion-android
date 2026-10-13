package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.SavedPairingProblem
import io.github.tuthan.steamoscompanion.core.client.SavedPairingProblemKind

/**
 * Phase 04: the encrypted state cannot be opened. This replaces the unpaired flow, which would otherwise invite a new
 * pairing that cannot be saved. Reset local pairing is offered only for a state that can never be opened again, and only
 * after a confirmation; a transient key problem or a newer schema gets Try again and is never erased.
 */
@Composable
fun SavedPairingUnavailableScreen(
    problem: SavedPairingProblem,
    busy: Boolean,
    feedback: ActionFeedback?,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    val palette = LocalCompanionPalette.current
    var confirming by remember { mutableStateOf(false) }
    Text(stringResource(R.string.ui_saved_pairing_unavailable), modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall, color = Color(palette.text.toInt()))
    if (problem.kind == SavedPairingProblemKind.APP_UPDATE_REQUIRED) {
        StatusLine(stringResource(R.string.ui_saved_pairing_version_mismatch), StatusTone.WARNING, glyph = StatusGlyph.INFO)
    } else {
        StatusLine(stringResource(R.string.ui_saved_pairing_unavailable), StatusTone.BLOCKED, glyph = StatusGlyph.KEY)
    }
    Text(stringResource(when (problem.kind) {
        SavedPairingProblemKind.RESET_REQUIRED -> R.string.ui_saved_pairing_reset_body
        SavedPairingProblemKind.RETRY -> R.string.ui_saved_pairing_retry_body
        SavedPairingProblemKind.APP_UPDATE_REQUIRED -> R.string.ui_saved_pairing_version_body
    }), style = MaterialTheme.typography.bodyLarge, color = Color(palette.textSoft.toInt()))
    if (problem.canRetry) {
        PrimaryAction(stringResource(R.string.ui_try_again), enabled = !busy, icon = R.drawable.ic_replay, onClick = onRetry)
    }
    if (problem.canReset) {
        ActionRow(stringResource(R.string.ui_reset_local_pairing), enabled = !busy, icon = R.drawable.ic_key,
            secondary = stringResource(R.string.ui_reset_local_pairing_detail), danger = true,
            onClick = { confirming = true })
    }
    feedback?.let { StatusLine(it.text, it.tone, live = true) }
    if (confirming && problem.canReset) {
        PictureConfirmationDialog(
            title = stringResource(R.string.ui_reset_local_pairing_title),
            body = stringResource(R.string.ui_reset_local_pairing_body),
            confirmLabel = stringResource(R.string.ui_reset_local_pairing_confirm),
            onDismiss = { confirming = false },
            onConfirm = { confirming = false; onReset() },
        )
    }
}
