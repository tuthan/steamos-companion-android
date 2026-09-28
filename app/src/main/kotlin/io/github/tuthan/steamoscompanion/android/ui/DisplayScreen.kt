package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.DisplayInventory
import io.github.tuthan.steamoscompanion.core.client.DisplayObservation
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationPresentation
import io.github.tuthan.steamoscompanion.core.client.DisplaySelection
import io.github.tuthan.steamoscompanion.core.client.DisplayRecoveryPresentation
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.KeepPreviewConfirmation
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.client.SaveCurrentConfirmation
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload

@Composable
fun DisplayScreen(
    host: ActiveHost,
    state: PersistentState?,
    status: StatusPollState,
    nowMonotonicNanos: Long,
    busy: Boolean,
    feedback: ActionFeedback?,
    onPreview: (DisplaySelection) -> Unit,
    onRestore: (String) -> Unit,
    onRevert: () -> Unit,
    onKeep: (KeepPreviewConfirmation) -> Unit,
    onSaveCurrent: (SaveCurrentConfirmation) -> Unit,
    onCheckOperation: () -> Unit,
) {
    val palette = LocalCompanionPalette.current
    val pairingKey = StatusObservationSource.from(host)
    val inventory = status.lastRead?.outputs?.takeIf {
        status.source == StatusObservationSource.from(host) && it.available && !it.legacyShape
    }
    var previousInventory by remember(pairingKey) { mutableStateOf<VersionedPayload.DisplayOutputs?>(null) }
    var selection by remember(pairingKey) { mutableStateOf<DisplaySelection?>(null) }
    LaunchedEffect(inventory) {
        if (inventory != null && (previousInventory == null || !DisplayInventory.sameInventory(previousInventory!!, inventory))) {
            selection = DisplayInventory.select(inventory, selection)
        }
        previousInventory = inventory
    }
    val selectedOutput = inventory?.outputs?.firstOrNull {
        it.id == selection?.outputId && it.generation == selection?.outputGeneration
    }
    val profileInventory = if (inventory != null && !status.unreachable && !status.stale) {
        inventory.profiles
    } else host.cachedRecoveryProfiles.orEmpty()
    var selectedProfileId by remember(pairingKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(profileInventory) {
        if (profileInventory.none { it.id == selectedProfileId }) selectedProfileId = profileInventory.firstOrNull()?.id
    }
    var saveConfirmation by remember(pairingKey) { mutableStateOf<SaveCurrentConfirmation?>(null) }
    var keepConfirmation by remember(pairingKey) { mutableStateOf<KeepPreviewConfirmation?>(null) }
    val preview = state?.let { DisplayRecoveryPresentation.project(host, it, status, nowMonotonicNanos) }
        ?: PreviewPresentation.None

    Text(stringResource(R.string.ui_display_settings), style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    when (preview) {
        is PreviewPresentation.Active -> PreviewCard(
            mode = modeLabel(preview.preview.targetMode),
            remainingSeconds = preview.remainingSeconds,
            status = preview.remainingSeconds?.let { "Reverts in $it seconds" } ?: "Checking restoration…",
            canRevert = preview.revertAvailable && !busy,
            canKeep = preview.keepAvailable && !busy,
            onRevert = onRevert,
            onKeep = { keepConfirmation = KeepPreviewConfirmation.forPreview(host, preview.preview) },
            question = stringResource(R.string.ui_preview_question, host.endpoint),
        )
        is PreviewPresentation.Pending -> StatusLine(stringResource(R.string.ui_result_not_confirmed),
            StatusTone.WARNING, glyph = StatusGlyph.QUESTION, detail = "Check the original operation")
        PreviewPresentation.None -> Unit
    }
    if (inventory == null) {
        if (status.unreachable) StatusLine("Display is unreachable", StatusTone.WARNING, glyph = StatusGlyph.BLOCKED,
            detail = "Saved recovery may still be available")
        else StatusLine("Checking display outputs…", StatusTone.PENDING)
    } else {
        Kicker("Outputs")
        if (inventory.outputs.size > 1) inventory.outputs.forEach { output ->
            ActionRow(output.name ?: output.id, secondary = "Output ${output.id}",
                selected = output.id == selectedOutput?.id,
                onClick = { selection = DisplaySelection(output.id, output.generation, output.currentModeId) })
        }
        if (selectedOutput != null) {
            Kicker("Modes")
            DisplayInventory.orderedModes(selectedOutput).forEach { mode ->
                ActionRow(modeLabel(mode),
                    badge = if (mode.id == selectedOutput.currentModeId) stringResource(R.string.ui_current_mode) else null,
                    badgeTone = StatusTone.PENDING,
                    selected = mode.id == selection?.modeId,
                    onClick = { selection = selection?.copy(modeId = mode.id) })
            }
            val canPreview = !busy && !status.unreachable && !status.stale &&
                preview == PreviewPresentation.None && selection?.modeId != null &&
                selection?.modeId != selectedOutput.currentModeId && "display.control" in host.grantedScopes
            if (preview == PreviewPresentation.None) {
                PrimaryAction("Preview selected mode", enabled = canPreview, icon = R.drawable.ic_play,
                    onClick = { selection?.let(onPreview) })
            }
            val currentConfirmation = SaveCurrentConfirmation.forOutput(host, selectedOutput)
            ActionRow(stringResource(R.string.ui_save_current_recovery_mode),
                enabled = !busy && !status.unreachable && !status.stale &&
                    currentConfirmation != null && preview == PreviewPresentation.None,
                reason = if (currentConfirmation == null) "Current mode is unavailable" else "Finish the current preview first",
                icon = R.drawable.ic_download,
                onClick = { saveConfirmation = currentConfirmation })
        }
    }
    Kicker("Saved recovery")
    if (profileInventory.isEmpty()) StatusLine(stringResource(R.string.ui_no_recovery_mode_saved), StatusTone.WARNING,
        detail = stringResource(R.string.ui_no_recovery_mode_saved_help))
    else profileInventory.forEach { profile ->
        ActionRow(modeLabel(profile.mode), secondary = "Output ${profile.outputId}",
            selected = profile.id == selectedProfileId,
            onClick = { selectedProfileId = profile.id })
    }
    ActionRow(stringResource(R.string.ui_restore_saved_display),
        enabled = !busy && selectedProfileId != null && preview !is PreviewPresentation.Pending &&
            preview !is PreviewPresentation.Active,
        reason = if (selectedProfileId == null) stringResource(R.string.ui_no_recovery_mode_saved) else "Resolve the preview or operation first",
        icon = R.drawable.ic_replay,
        onClick = { selectedProfileId?.let(onRestore) })
    state?.let { persisted ->
        DisplayOperationPresentation.latest(host, persisted, status, nowMonotonicNanos)?.let { card ->
            Kicker("Latest display operation")
            StatusLine("Request: ${card.kind.name.replace('_', ' ').lowercase()} · ${card.phase.wireValue}",
                if (card.phase == JournalPhase.FAILED) StatusTone.WARNING else StatusTone.UNKNOWN)
            StatusLine("Current display: ${observationLabel(card.observed)}",
                if (card.observed == DisplayObservation.CONNECTION_LOST) StatusTone.WARNING else StatusTone.UNKNOWN)
            card.failure?.let { StatusLine(failure(it), StatusTone.WARNING) }
            if (card.phase.unresolved) ActionRow("Check result", enabled = card.operationId != null,
                reason = if (card.operationId == null) "No operation ID was returned; review the host" else null,
                icon = R.drawable.ic_status_question, onClick = onCheckOperation)
        }
    }
    feedback?.let { StatusLine(it.text, it.tone) }

    saveConfirmation?.let { captured ->
        PictureConfirmationDialog(
            title = "Save current display as recovery mode?",
            body = stringResource(R.string.ui_save_current_confirmation, host.endpoint),
            confirmLabel = "Save current display",
            onDismiss = { saveConfirmation = null },
            onConfirm = { saveConfirmation = null; onSaveCurrent(captured) },
        )
    }
    keepConfirmation?.let { captured ->
        PictureConfirmationDialog(
            title = "Keep this display preview?",
            body = "Is the picture on this device's display correct right now? The preview deadline still applies.",
            confirmLabel = "Keep and save",
            onDismiss = { keepConfirmation = null },
            onConfirm = { keepConfirmation = null; onKeep(captured) },
        )
    }
}

@Composable
internal fun PictureConfirmationDialog(title: String, body: String, confirmLabel: String,
                                      onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val palette = LocalCompanionPalette.current
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(title) { cancelFocus.requestFocus() }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(CompanionShapes.CARD_DP.dp),
        containerColor = Color(palette.panel.toInt()),
        title = { Text(title) }, text = { Text(body) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
            Text(stringResource(R.string.ui_cancel))
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } })
}

private fun modeLabel(mode: DisplayModeSummary): String =
    "${mode.width} × ${mode.height} · ${mode.refreshHz ?: "?"} Hz"

private fun observationLabel(observed: DisplayObservation): String = when (observed) {
    DisplayObservation.NOT_CHECKED -> "not checked"
    DisplayObservation.CONNECTION_LOST -> "connection lost"
    DisplayObservation.OUTPUTS_UNAVAILABLE -> "output read unavailable"
    DisplayObservation.UNPROVEN_PAIRING -> "pairing provenance unproven"
    DisplayObservation.PREVIEW_ACTIVE -> "preview active"
    DisplayObservation.PREVIEW_DIFFERENT -> "different preview active"
    DisplayObservation.PREVIEW_ABSENT -> "preview absent; restoration not confirmed"
    DisplayObservation.OUTPUT_MISSING -> "output missing"
    DisplayObservation.OUTPUT_REENUMERATED -> "output identity changed"
    DisplayObservation.PROFILE_NOT_LISTED -> "saved profile not listed"
    DisplayObservation.MODE_MATCHES_REQUEST -> "current mode matches the request"
    DisplayObservation.MODE_DIFFERS_FROM_REQUEST -> "current mode differs from the request"
    DisplayObservation.DISPLAY_REACHABLE -> "display reachable; save outcome needs operation readback"
}
