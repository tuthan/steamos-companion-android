package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import io.github.tuthan.steamoscompanion.core.client.ControlObservation
import io.github.tuthan.steamoscompanion.core.client.ControlOperationKind
import io.github.tuthan.steamoscompanion.core.client.ControlOperationPresentation
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.PowerConfirmation
import io.github.tuthan.steamoscompanion.core.client.PowerVerb
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.wake.WakeAttemptState
import io.github.tuthan.steamoscompanion.core.wake.WakeSendFailure
import io.github.tuthan.steamoscompanion.core.wake.WakeSendResult

@Composable
fun PowerScreen(host: ActiveHost, name: String, context: ActionContext, state: PersistentState?,
                status: StatusPollState, nowMonotonicNanos: Long, busy: Boolean,
                feedback: ActionFeedback?, onConfirm: (PowerConfirmation) -> Unit,
                onCheckOperation: () -> Unit) {
    val palette = LocalCompanionPalette.current
    var confirmation by remember(host) { mutableStateOf<PowerConfirmation?>(null) }
    Text(stringResource(R.string.ui_power_options), style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    listOf(PowerVerb.SUSPEND, PowerVerb.RESTART, PowerVerb.SHUTDOWN).forEach { verb ->
        val action = when (verb) {
            PowerVerb.SUSPEND -> UiAction.SUSPEND
            PowerVerb.RESTART -> UiAction.RESTART
            PowerVerb.SHUTDOWN -> UiAction.SHUT_DOWN
        }
        val title = when (verb) {
            PowerVerb.SUSPEND -> stringResource(R.string.ui_suspend)
            PowerVerb.RESTART -> stringResource(R.string.ui_restart)
            PowerVerb.SHUTDOWN -> stringResource(R.string.ui_shut_down)
        }
        val consequence = when (verb) {
            PowerVerb.SUSPEND -> stringResource(R.string.ui_suspend_confirmation_body)
            PowerVerb.RESTART -> stringResource(R.string.ui_restart_confirmation_body)
            PowerVerb.SHUTDOWN -> stringResource(R.string.ui_shutdown_confirmation_body)
        }
        val allowed = ActionAvailability.forAction(action, context)
        ActionRow(title, secondary = consequence, enabled = allowed.enabled && !busy,
            reason = if (busy) "Request in progress" else allowed.block?.let(::blockReason),
            icon = when (verb) {
                PowerVerb.SUSPEND -> R.drawable.ic_pause
                PowerVerb.RESTART -> R.drawable.ic_replay
                PowerVerb.SHUTDOWN -> R.drawable.ic_status_blocked
            },
            danger = verb == PowerVerb.SHUTDOWN,
            onClick = { confirmation = PowerConfirmation.forHost(host, verb) })
    }
    feedback?.let { StatusLine(it.text, it.tone) }
    LaneReviewRow(host, state)
    ControlCard(host, state, status, nowMonotonicNanos,
        include = { it != ControlOperationKind.SUNSHINE_RESTART }, onCheckOperation = onCheckOperation)
    val pending = confirmation
    if (pending != null) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(pending) { cancelFocus.requestFocus() }
        AlertDialog(onDismissRequest = { confirmation = null },
            shape = RoundedCornerShape(CompanionShapes.CARD_DP.dp),
            containerColor = Color(palette.panel.toInt()),
            title = { Text(when (pending.verb) {
                PowerVerb.SUSPEND -> stringResource(R.string.ui_suspend_confirmation_title, name)
                PowerVerb.RESTART -> stringResource(R.string.ui_restart_confirmation_title, name)
                PowerVerb.SHUTDOWN -> stringResource(R.string.ui_shutdown_confirmation_title, name)
            }) },
            text = { Text(when (pending.verb) {
                PowerVerb.SUSPEND -> stringResource(R.string.ui_suspend_confirmation_body)
                PowerVerb.RESTART -> stringResource(R.string.ui_restart_confirmation_body)
                PowerVerb.SHUTDOWN -> stringResource(R.string.ui_shutdown_confirmation_body)
            }) },
            dismissButton = { TextButton(onClick = { confirmation = null }, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.ui_cancel))
            } },
            confirmButton = { TextButton(onClick = { confirmation = null; onConfirm(pending) },
                colors = ButtonDefaults.textButtonColors(contentColor = Color((if (pending.verb == PowerVerb.SHUTDOWN)
                    palette.red else palette.blue).toInt()))) {
                Text(when (pending.verb) {
                    PowerVerb.SUSPEND -> stringResource(R.string.ui_suspend_device)
                    PowerVerb.RESTART -> stringResource(R.string.ui_restart_device)
                    PowerVerb.SHUTDOWN -> stringResource(R.string.ui_shutdown_device)
                })
            } })
    }
}

@Composable
fun WakeScreen(attempt: WakeAttemptState?, configured: Boolean, busy: Boolean,
               onSend: () -> Unit, target: io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget? = null) {
    val palette = LocalCompanionPalette.current
    Text(stringResource(R.string.ui_wake_device), style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    if (!configured) {
        StatusLine(stringResource(R.string.ui_wake_not_configured), StatusTone.WARNING, glyph = StatusGlyph.INFO)
    }
    if (attempt != null) {
        val packet = attempt.packet
        StatusLine(when (packet) {
            is WakeSendResult.Sent -> if (packet.viaRelay) "Wake packet sent to the relay" else "Wake packet sent"
            is WakeSendResult.Partial -> "Wake packet sent to some network addresses"
            is WakeSendResult.Failed -> stringResource(when (packet.reason) {
                WakeSendFailure.RELAY_REQUIRED -> R.string.ui_wake_relay_required
                WakeSendFailure.PERMISSION -> R.string.ui_wake_permission_missing
                WakeSendFailure.NETWORK_MISSING -> R.string.ui_wake_network_missing
                WakeSendFailure.TARGET_MISSING -> R.string.ui_wake_not_configured
                WakeSendFailure.SEND_FAILED -> R.string.ui_wake_packet_failed
            })
        }, if (packet is WakeSendResult.Failed) StatusTone.WARNING else StatusTone.GOOD,
            detail = when (packet) {
                is WakeSendResult.Failed -> packet.detail
                is WakeSendResult.Partial -> packet.detail
                is WakeSendResult.Sent -> (if (packet.viaRelay) "Relay " else "To ") +
                    packet.destinations.joinToString { it.hostAddress.orEmpty() } +
                    (if (packet.viaRelay) ". It must re-broadcast the packet on the device's network." else "")
            })
        target?.mac?.takeIf { it.length == 12 && packet !is WakeSendResult.Failed }?.let { mac ->
            StatusLine("For ${mac.chunked(2).joinToString(":")}", StatusTone.UNKNOWN,
                detail = target.interfaceName?.takeIf { it.isNotBlank() }?.let { "Device network interface: $it" })
        }
        StatusLine(when {
            attempt.authenticatedResponse -> stringResource(R.string.ui_device_connected)
            attempt.timedOut -> stringResource(R.string.ui_wake_no_response)
            else -> "Waiting for an authenticated device response…"
        }, when {
            attempt.authenticatedResponse -> StatusTone.GOOD
            attempt.timedOut -> StatusTone.UNKNOWN
            else -> StatusTone.PENDING
        }, glyph = if (attempt.authenticatedResponse) StatusGlyph.DOT else null)
        StatusLine(if (attempt.steamReady) "Steam ready" else "Steam readiness not confirmed",
            if (attempt.steamReady) StatusTone.GOOD else StatusTone.UNKNOWN)
        if (attempt.timedOut && target?.interfaceName?.startsWith("wl") == true) {
            StatusLine(stringResource(R.string.ui_wake_wifi_hint), StatusTone.UNKNOWN, glyph = StatusGlyph.INFO)
        }
    }
    val canSend = configured && !busy && (attempt == null || attempt.timedOut || attempt.packet is WakeSendResult.Failed)
    PrimaryAction(if (attempt == null) stringResource(R.string.ui_wake_device)
        else stringResource(R.string.ui_send_another_wake_packet), enabled = canSend, icon = R.drawable.ic_external,
        onClick = onSend)
}

@Composable
fun SunshineScreen(state: WireValue<SunshineState>?, context: ActionContext, host: ActiveHost,
                   persisted: PersistentState?, status: StatusPollState, nowMonotonicNanos: Long,
                   busy: Boolean, feedback: ActionFeedback?, onRecover: () -> Unit,
                   onCheckOperation: () -> Unit) {
    val palette = LocalCompanionPalette.current
    Text("Sunshine", style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    val (label, tone, glyph) = when (state) {
        WireValue.Known(SunshineState.RUNNING) -> Triple(stringResource(R.string.ui_sunshine_running), StatusTone.GOOD, StatusGlyph.CHECK)
        WireValue.Known(SunshineState.STOPPED) -> Triple(stringResource(R.string.ui_sunshine_stopped), StatusTone.WARNING, StatusGlyph.BLOCKED)
        WireValue.Known(SunshineState.DISABLED) -> Triple(stringResource(R.string.ui_sunshine_monitoring_off), StatusTone.WARNING, StatusGlyph.INFO)
        WireValue.Known(SunshineState.UNAVAILABLE) -> Triple("Sunshine provider unavailable", StatusTone.WARNING, StatusGlyph.INFO)
        else -> Triple(stringResource(R.string.ui_sunshine_checking), StatusTone.PENDING, StatusGlyph.PENDING)
    }
    StatusLine(label, tone, glyph = glyph)
    val allowed = ActionAvailability.forAction(UiAction.RECOVER_SUNSHINE, context)
    ActionRow(stringResource(R.string.ui_recover_sunshine), enabled = allowed.enabled && !busy,
        reason = if (busy) "Request in progress" else allowed.block?.let(::blockReason), icon = R.drawable.ic_replay,
        onClick = onRecover)
    feedback?.let { StatusLine(it.text, it.tone) }
    LaneReviewRow(host, persisted)
    ControlCard(host, persisted, status, nowMonotonicNanos,
        include = { it == ControlOperationKind.SUNSHINE_RESTART }, onCheckOperation = onCheckOperation)
}

@Composable
private fun ControlCard(host: ActiveHost, state: PersistentState?, status: StatusPollState,
                        nowMonotonicNanos: Long, include: (ControlOperationKind) -> Boolean,
                        onCheckOperation: () -> Unit) {
    val card = state?.let { ControlOperationPresentation.project(host, it, status, nowMonotonicNanos) }
        ?.firstOrNull { include(it.kind) } ?: return
    Kicker("Latest control operation")
    val hostReturned = card.phase == io.github.tuthan.steamoscompanion.core.client.JournalPhase.OBSERVED_RETURN &&
        !card.blocksLane
    StatusLine("Request: ${card.kind.name.replace('_', ' ').lowercase()} · " +
        (if (hostReturned) "accepted by the device" else card.phase.wireValue),
        if (card.phase == io.github.tuthan.steamoscompanion.core.client.JournalPhase.FAILED)
            StatusTone.WARNING else StatusTone.UNKNOWN)
    val restarting = card.kind == ControlOperationKind.RESTART && card.blocksLane && card.operationId != null &&
        card.phase != io.github.tuthan.steamoscompanion.core.client.JournalPhase.FAILED &&
        card.phase != io.github.tuthan.steamoscompanion.core.client.JournalPhase.SUCCEEDED
    if (restarting) StatusLine("Restarting. Waiting for the device to come back…", StatusTone.PENDING,
        glyph = StatusGlyph.PENDING, detail = "The app keeps checking; nothing is sent again")
    val settled = card.summary?.takeIf { card.phase == io.github.tuthan.steamoscompanion.core.client.JournalPhase.SUCCEEDED }
    if (settled != null) StatusLine(settled, StatusTone.GOOD, glyph = StatusGlyph.CHECK)
    StatusLine("Observed: ${controlObservationLabel(card.observed)}",
        if (card.observed == ControlObservation.CONNECTION_LOST) StatusTone.WARNING else StatusTone.UNKNOWN)
    if (card.phase.unresolved && card.blocksLane) {
        ActionRow("Check result", enabled = card.operationId != null,
            reason = if (card.operationId == null) "No operation ID was returned; review the host" else null,
            icon = R.drawable.ic_status_question, onClick = onCheckOperation)
    }
}

private fun controlObservationLabel(value: ControlObservation): String = when (value) {
    ControlObservation.NOT_CHECKED -> "not checked"
    ControlObservation.CONNECTION_LOST -> "connection lost; action result unconfirmed"
    ControlObservation.HOST_REACHABLE -> "host reachable; physical transition unconfirmed"
    ControlObservation.BOOT_ID_CHANGED -> "host boot identity changed"
    ControlObservation.SUNSHINE_RUNNING -> "Sunshine running"
    ControlObservation.SUNSHINE_STOPPED -> "Sunshine stopped"
    ControlObservation.SUNSHINE_UNAVAILABLE -> "Sunshine state unavailable"
    ControlObservation.UNPROVEN_PAIRING -> "pairing provenance unproven"
}

private fun blockReason(block: ActionBlock): String = when (block) {
    ActionBlock.STATUS_NOT_FRESH -> "Check the latest device status"
    ActionBlock.PREVIEW_ACTIVE, ActionBlock.PREVIEW_OWNED_ELSEWHERE, ActionBlock.PREVIEW_STATE_UNKNOWN ->
        "Check the display preview first"
    ActionBlock.OPERATION_IN_PROGRESS, ActionBlock.OPERATION_HISTORY_FULL -> "Review the current operation first"
    ActionBlock.MISSING_POWER_PERMISSION -> "Power control permission is missing"
    ActionBlock.SUNSHINE_NOT_FRESHLY_STOPPED -> "Sunshine is not confirmed stopped"
    ActionBlock.CAPABILITY_UNAVAILABLE, ActionBlock.CAPABILITY_UNVERIFIED -> "Host capability unavailable"
    ActionBlock.DEVICE_IDENTITY_CHANGED, ActionBlock.DEVICE_CREDENTIAL_REJECTED -> "Check device identity"
    else -> "Action unavailable in the current device state"
}
