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
import io.github.tuthan.steamoscompanion.core.client.HostAccessPresentation
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.KeepPreviewConfirmation
import io.github.tuthan.steamoscompanion.core.client.LinkAdvisory
import io.github.tuthan.steamoscompanion.core.client.LinkFactsReading
import io.github.tuthan.steamoscompanion.core.client.LinkFactsTopology
import io.github.tuthan.steamoscompanion.core.client.OrderRestartConfirmation
import io.github.tuthan.steamoscompanion.core.client.OperationLanePresentation
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.client.SaveCurrentConfirmation
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.HostScopes
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
    /** Advisory DRM link facts; only a reading for this pairing and output topology is ever used. */
    linkFacts: LinkFactsReading? = null,
    /** Asks for the link facts of this pairing and topology. Called once per display interaction. */
    onLinkFactsWanted: (ActiveHost, String) -> Unit = { _, _ -> },
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
    // Advisory facts: asked for once when this screen opens and again when the output topology changes,
    // never from the status poll. A reading for any other pairing or topology is treated as no facts.
    val linkTopology = LinkFactsTopology.key(inventory)
    var linkRequested by remember(pairingKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(pairingKey, linkTopology) {
        if (linkTopology != null && linkTopology != linkRequested) {
            linkRequested = linkTopology
            onLinkFactsWanted(host, linkTopology)
        }
    }
    val linkFactsNow = linkFacts?.factsFor(host, linkTopology)
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
    // The owner of the host can withdraw this scope at any time; every display request needs it, the screen stays.
    val displayDenied = displayDeniedReason(host)

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
            canRevert = preview.revertAvailable && !busy && displayDenied == null,
            canKeep = preview.keepAvailable && !busy && displayDenied == null,
            onRevert = onRevert,
            onKeep = { keepConfirmation = KeepPreviewConfirmation.forPreview(host, preview.preview) },
            question = stringResource(R.string.ui_preview_question, host.endpoint),
            revertReason = displayDenied,
            keepReason = displayDenied,
        )
        is PreviewPresentation.Pending -> StatusLine(stringResource(R.string.ui_result_not_confirmed),
            StatusTone.WARNING, glyph = StatusGlyph.QUESTION, detail = "Check the original operation")
        PreviewPresentation.None -> Unit
    }
    LaneReviewRow(host, state)
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
            displayDenied != null -> displayDenied
            preview != PreviewPresentation.None -> "Waiting for this preview to be kept or reverted"
            state != null && OperationLanePresentation.hasUnresolved(host, state) ->
                "An earlier request has no confirmed result. Check it or mark it reviewed above."
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
                enabled = !busy && displayDenied == null && (preview == PreviewPresentation.None ||
                    preview is PreviewPresentation.Active && preview.revertAvailable),
                reason = displayDenied ?: "Resolve the preview or operation first",
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
            enabled = !legacyInventory && !busy && !status.unreachable && !status.stale && displayDenied == null &&
                currentConfirmation != null && preview == PreviewPresentation.None,
            reason = when {
                displayDenied != null -> displayDenied
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
                LinkAdvisory.connectorFor(linkFactsNow, selectedOutput.id)?.let { LinkFactsLine(LinkAdvisory.line(it)) }
                Kicker("Modes")
                val previewBlock = when {
                    displayDenied != null -> displayDenied
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
                            LinkAdvisory.evaluate(linkFactsNow, selectedOutput.id, mode, selectedOutput.currentModeId)
                                ?.let { LinkAdvisoryRow(it) }
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
            card.failure?.let { StatusLine(failure(it), StatusTone.WARNING,
                detail = card.summary?.takeIf { _ -> card.phase == JournalPhase.FAILED }) }
            if (card.phase.unresolved && card.blocksLane) ActionRow("Check result", enabled = card.operationId != null,
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

/**
 * Shown whenever any earlier request blocks this credential's actions, not only when the newest card is
 * that request: the blocker can be an older entry that no card presents, and then nothing else offers a way out.
 */
@Composable
internal fun LaneReviewRow(host: ActiveHost, state: PersistentState?) {
    if (state == null || !OperationLanePresentation.hasUnresolved(host, state)) return
    StatusLine("An earlier request has no confirmed result", StatusTone.WARNING, glyph = StatusGlyph.QUESTION,
        detail = "It blocks new actions until the host settles it or you mark it reviewed")
    MarkReviewedRow()
}

/** Lets any screen with an unresolved-operation card offer the explicit, confirmed way out. */
val LocalAcknowledgeUnresolved = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun MarkReviewedRow() {
    val acknowledge = LocalAcknowledgeUnresolved.current
    var confirming by remember { mutableStateOf(false) }
    ActionRow(stringResource(R.string.ui_mark_reviewed), secondary = stringResource(R.string.ui_mark_reviewed_hint),
        icon = R.drawable.ic_check, onClick = { confirming = true })
    if (confirming) PictureConfirmationDialog(
        title = stringResource(R.string.ui_mark_reviewed_title),
        body = stringResource(R.string.ui_mark_reviewed_body),
        confirmLabel = stringResource(R.string.ui_mark_reviewed),
        onDismiss = { confirming = false },
        onConfirm = { confirming = false; acknowledge() })
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

/** Why every display control is disabled for this pairing, or null while the host allows Display. */
internal fun displayDeniedReason(host: ActiveHost): String? =
    if (HostAccessPresentation.allows(host.grantedScopes, HostScopes.DISPLAY_CONTROL)) null
    else HostAccessPresentation.denied(HostScopes.DISPLAY_CONTROL)

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
