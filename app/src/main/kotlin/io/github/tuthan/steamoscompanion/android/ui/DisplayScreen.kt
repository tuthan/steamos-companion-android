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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.tuthan.steamoscompanion.core.client.DisplayOrderPresentation
import io.github.tuthan.steamoscompanion.core.client.DisplaySelection
import io.github.tuthan.steamoscompanion.core.client.DisplayRecoveryPresentation
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.KeepPreviewConfirmation
import io.github.tuthan.steamoscompanion.core.client.OrderRestartConfirmation
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.client.SaveCurrentConfirmation
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
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
    onSaveOrder: (List<String>, Long) -> Unit,
    onSaveOrderAndRestart: (OrderRestartConfirmation) -> Unit,
    onResetOrder: () -> Unit,
    onCheckOperation: () -> Unit,
    /** Opens the Gaming Mode display order tab, as the overview row does. */
    startOnOrder: Boolean = false,
) {
    val palette = LocalCompanionPalette.current
    val pairingKey = StatusObservationSource.from(host)
    val read = status.lastRead?.takeIf { status.source == StatusObservationSource.from(host) }
    val reportedOutputs = read?.outputs
    val order = read?.order?.snapshot
    val inventory = reportedOutputs?.takeIf { it.available }
    val legacyInventory = inventory?.legacyShape == true
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
    val profileInventory = DisplayInventory.distinctProfiles(
        if (inventory != null && !status.unreachable && !status.stale) inventory.profiles
        else host.cachedRecoveryProfiles.orEmpty(),
    )
    var selectedProfileId by remember(pairingKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(profileInventory) {
        if (profileInventory.none { it.id == selectedProfileId }) selectedProfileId = profileInventory.firstOrNull()?.id
    }
    var saveConfirmation by remember(pairingKey) { mutableStateOf<SaveCurrentConfirmation?>(null) }
    var keepConfirmation by remember(pairingKey) { mutableStateOf<KeepPreviewConfirmation?>(null) }
    val preview = state?.let { DisplayRecoveryPresentation.project(host, it, status, nowMonotonicNanos) }
        ?: PreviewPresentation.None

    val operationCard = state?.let { DisplayOperationPresentation.latest(host, it, status, nowMonotonicNanos) }
    val orderDraft = rememberOrderDraft(host, order, operationCard)
    var restartConfirmation by remember(pairingKey) { mutableStateOf<OrderRestartConfirmation?>(null) }

    Text(stringResource(if (startOnOrder) R.string.ui_display_order else R.string.ui_display_settings),
        style = MaterialTheme.typography.headlineSmall, color = Color(palette.text.toInt()))
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
    val orderTab = DisplayOrderPresentation.offered(order)
    val tabKeys = buildList { add(TAB_RESOLUTION); add(TAB_SAVED); if (orderTab) add(TAB_ORDER) }
    var tabKey by rememberSaveable(startOnOrder) { mutableStateOf(if (startOnOrder) TAB_ORDER else TAB_RESOLUTION) }
    val orderMissing = tabKey == TAB_ORDER && !orderTab
    val currentTab = if (orderMissing) TAB_RESOLUTION else tabKey
    SegmentTabs(tabKeys.map(::tabLabel), tabKeys.indexOf(currentTab).coerceAtLeast(0)) { tabKey = tabKeys[it] }
    if (orderMissing) StatusLine(if (status.unreachable) "Display is unreachable" else "Display ordering isn't available",
        StatusTone.WARNING, detail = "This host does not currently report a Gaming Mode display order")
    if (currentTab == TAB_ORDER && order != null) {
        val orderBlock = when {
            "display.control" !in host.grantedScopes -> "Changing the display order needs display control permission"
            preview != PreviewPresentation.None -> "Waiting for this preview to be kept or reverted"
            operationCard?.phase?.unresolved == true -> "Check the unresolved display operation first"
            else -> null
        }
        DisplayOrderPanel(order, orderDraft.value, onDraft = { orderDraft.value = it },
            canAct = !busy && !status.unreachable && !status.stale && orderBlock == null,
            outdated = status.unreachable || status.stale, blockedReason = orderBlock,
            onSave = { orderDraft.value?.let { onSaveOrder(it.keys, order.generation ?: return@let) } },
            onSaveAndRestart = { orderDraft.value?.let { draft ->
                order.generation?.let { restartConfirmation = OrderRestartConfirmation(pairingKey, draft.keys, it) }
            } },
            onReset = onResetOrder)
    } else if (currentTab == TAB_SAVED) {
        if (status.unreachable) StatusLine("Display is unreachable", StatusTone.WARNING, glyph = StatusGlyph.BLOCKED,
            detail = "Showing the last saved recovery modes")
        Kicker("Saved recovery modes")
        if (profileInventory.isEmpty()) StatusLine(stringResource(R.string.ui_no_recovery_mode_saved), StatusTone.WARNING,
            detail = stringResource(R.string.ui_no_recovery_mode_saved_help))
        else profileInventory.forEach { profile ->
            val chosen = profile.id == selectedProfileId
            ActionRow(modeLabel(profile.mode),
                secondary = inventory?.outputs?.firstOrNull { it.id == profile.outputId }?.let { it.name ?: it.id }
                    ?: "Output ${profile.outputId}",
                selected = chosen, onClick = { selectedProfileId = profile.id })
            // Next to the row it acts on, so a long list never separates the choice from its button.
            if (chosen) ActionRow(stringResource(R.string.ui_restore_saved_display),
                enabled = !busy && (preview == PreviewPresentation.None ||
                    preview is PreviewPresentation.Active && preview.revertAvailable),
                reason = "Resolve the preview or operation first",
                icon = R.drawable.ic_replay,
                onClick = { onRestore(profile.id) })
        }
        Kicker("Save the current display")
        val currentOutput = selectedOutput
        val currentConfirmation = currentOutput?.let { SaveCurrentConfirmation.forOutput(host, it) }
        ActionRow(stringResource(R.string.ui_save_current_recovery_mode),
            secondary = currentOutput?.let { output ->
                val current = output.modes.firstOrNull { it.id == output.currentModeId }
                listOfNotNull(output.name ?: output.id, current?.let(::modeLabel)).joinToString(" · ")
            },
            enabled = !legacyInventory && !busy && !status.unreachable && !status.stale &&
                currentConfirmation != null && preview == PreviewPresentation.None,
            reason = when {
                legacyInventory -> "This host cannot save a recovery mode"
                currentOutput == null -> "Display outputs are unavailable"
                currentConfirmation == null -> "Current mode is unavailable"
                else -> "Finish the current preview first"
            },
            icon = R.drawable.ic_download,
            onClick = { saveConfirmation = currentConfirmation })
    } else if (currentTab == TAB_RESOLUTION) {
        if (inventory == null) {
            when {
                status.unreachable -> StatusLine("Display is unreachable", StatusTone.WARNING, glyph = StatusGlyph.BLOCKED,
                    detail = "Saved recovery may still be available")
                reportedOutputs != null -> StatusLine("Display outputs unavailable", StatusTone.WARNING,
                    detail = (reportedOutputs.body["reason"] as? JsonValue.StringValue)?.value
                        ?: "The host did not report an available display")
                read?.outputsError != null -> StatusLine("Couldn't load display outputs", StatusTone.WARNING,
                    detail = read.outputsError)
                else -> StatusLine("Checking display outputs…", StatusTone.PENDING)
            }
        } else {
            if (legacyInventory) StatusLine("Display modes are read-only with this host version", StatusTone.WARNING,
                detail = "Update the host plugin to preview or save a recovery mode")
            Kicker("Outputs")
            if (inventory.outputs.size == 1) {
                val output = inventory.outputs.single()
                StatusLine(output.name ?: output.id, StatusTone.UNKNOWN, detail = "Output ${output.id}")
            }
            if (inventory.outputs.size > 1) inventory.outputs.forEach { output ->
                ActionRow(output.name ?: output.id, secondary = "Output ${output.id}",
                    selected = output.id == selectedOutput?.id,
                    onClick = { selection = DisplaySelection(output.id, output.generation, output.currentModeId) })
            }
            if (selectedOutput != null) {
                Kicker("Modes")
                val previewBlock = when {
                    "display.control" !in host.grantedScopes -> "This pairing doesn't have display control permission"
                    status.unreachable || status.stale -> "Waiting for a fresh reading from the device"
                    else -> null
                }
                DisplayInventory.orderedModes(selectedOutput).forEach { mode ->
                    val chosen = mode.id == selection?.modeId
                    ActionRow(modeLabel(mode),
                        badge = if (mode.id == selectedOutput.currentModeId) stringResource(R.string.ui_current_mode) else null,
                        badgeTone = StatusTone.PENDING,
                        selected = chosen,
                        onClick = { selection = selection?.copy(modeId = mode.id) })
                    // Next to the chosen resolution, so a long list never separates the choice from its button.
                    if (chosen && mode.id != selectedOutput.currentModeId && !legacyInventory) {
                        if (preview == PreviewPresentation.None) {
                            PrimaryAction("Preview ${modeLabel(mode)}", icon = R.drawable.ic_play,
                                enabled = !busy && previewBlock == null, onClick = { selection?.let(onPreview) })
                            previewBlock?.let { StatusLine(it, StatusTone.WARNING) }
                        } else StatusLine("Keep or revert the current preview first", StatusTone.WARNING)
                    }
                }
            }
        }
    }
    state?.let {
        operationCard?.let { card ->
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
    restartConfirmation?.let { captured ->
        PictureConfirmationDialog(
            title = "Save order and restart Gaming Mode?",
            body = "Games and the Steam UI on ${host.endpoint} will close, and the stream may disconnect. " +
                "The order is saved first; if the restart fails, the saved order stays for the next session.",
            confirmLabel = "Save and restart",
            onDismiss = { restartConfirmation = null },
            onConfirm = { restartConfirmation = null; onSaveOrderAndRestart(captured) },
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

private const val TAB_RESOLUTION = "resolution"
private const val TAB_SAVED = "saved"
private const val TAB_ORDER = "order"

private fun tabLabel(key: String) = when (key) {
    TAB_SAVED -> "Saved"
    TAB_ORDER -> "Display order"
    else -> "Resolution"
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
    DisplayObservation.ORDER_UNAVAILABLE -> "order read unavailable"
    DisplayObservation.ORDER_SAVED_MATCHES_REQUEST -> "host reports the requested saved order"
    DisplayObservation.ORDER_SAVED_DIFFERS_FROM_REQUEST -> "host reports a different saved order"
    DisplayObservation.ORDER_AUTOMATIC -> "no order saved; automatic"
    DisplayObservation.ORDER_STILL_SAVED -> "an order is still saved"
}
