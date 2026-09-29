package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationCard
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationKind
import io.github.tuthan.steamoscompanion.core.client.DisplayOrderDraft
import io.github.tuthan.steamoscompanion.core.client.DisplayOrderPresentation
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.JournalRejectionCode
import io.github.tuthan.steamoscompanion.core.protocol.DisplayOrderSummary

private val DraftSaver: Saver<DisplayOrderDraft?, Any> = listSaver(
    save = { draft ->
        if (draft == null) emptyList<Any?>() else listOf(ArrayList(draft.baseKeys), ArrayList(draft.keys), draft.topology,
            draft.stale, draft.pendingKeys?.let { ArrayList(it) })
    },
    restore = { saved ->
        if (saved.isEmpty()) null else @Suppress("UNCHECKED_CAST") DisplayOrderDraft(
            saved[0] as List<String>, saved[1] as List<String>, saved[2] as String, saved[3] as Boolean,
            saved[4] as List<String>?,
        )
    },
)

/**
 * The owner's draft outlives tab switches and rotation. Background reads never reorder it; a saved
 * order holds the draft steady until the host reports it back.
 */
@Composable
internal fun rememberOrderDraft(
    host: ActiveHost, order: DisplayOrderSummary?, card: DisplayOperationCard?,
): MutableState<DisplayOrderDraft?> {
    val draft = rememberSaveable(host.hostId, host.clientId, stateSaver = DraftSaver) {
        mutableStateOf<DisplayOrderDraft?>(null)
    }
    // Journal entries that predate this screen must not seed a pending order.
    var consumed by rememberSaveable(host.hostId, host.clientId) { mutableLongStateOf(card?.localGeneration ?: 0L) }
    LaunchedEffect(order) {
        if (order != null) draft.value = draft.value?.observed(order) ?: DisplayOrderDraft.from(order)
    }
    LaunchedEffect(card?.localGeneration, card?.phase, card?.failure) {
        if (card == null || card.localGeneration <= consumed) return@LaunchedEffect
        val savedOrder = card.requestedKeys != null &&
            (card.phase == JournalPhase.SUCCEEDED || card.failure == JournalRejectionCode.ORDER_SAVED_RESTART_FAILED)
        val cleared = card.kind == DisplayOperationKind.ORDER_RESET && card.phase == JournalPhase.SUCCEEDED
        when {
            savedOrder -> draft.value = draft.value?.saved(requireNotNull(card.requestedKeys))
            cleared -> draft.value = draft.value?.copy(pendingKeys = null)
            else -> return@LaunchedEffect
        }
        consumed = card.localGeneration
    }
    return draft
}

@Composable
internal fun DisplayOrderPanel(
    order: DisplayOrderSummary,
    draft: DisplayOrderDraft?,
    onDraft: (DisplayOrderDraft) -> Unit,
    /** The host is reachable and fresh, scoped, idle, and no preview or unresolved operation blocks a change. */
    canAct: Boolean,
    outdated: Boolean,
    blockedReason: String?,
    onSave: () -> Unit,
    onSaveAndRestart: () -> Unit,
    onReset: () -> Unit,
) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val keys = draft?.keys.orEmpty()
    val selected = selectedKey?.takeIf { it in keys } ?: keys.firstOrNull()
    val canEdit = canAct && draft != null && !draft.stale && draft.pendingKeys == null &&
        DisplayOrderPresentation.editable(order)

    if (outdated) StatusLine("Showing the last order read", StatusTone.WARNING, glyph = StatusGlyph.CLOCK)
    when {
        !order.available -> StatusLine("Display order unavailable", StatusTone.WARNING,
            detail = "The host did not report a display order")
        order.ambiguous -> StatusLine("Some displays can't be told apart", StatusTone.WARNING,
            detail = "The host can't identify every connector, so the order can't be changed")
        order.stale -> StatusLine("Display order changed", StatusTone.WARNING,
            detail = "Refresh the order before saving")
        draft?.stale == true -> StatusLine("Displays changed", StatusTone.WARNING,
            detail = "Refresh the order before saving; your unsaved changes will be discarded")
    }
    Kicker("Priority · first is preferred")
    if (keys.isEmpty()) StatusLine("No connected displays reported", StatusTone.UNKNOWN)
    keys.forEachIndexed { index, key ->
        val row = DisplayOrderPresentation.row(order, key)
        ActionRow("${index + 1}. ${row.label}", secondary = orderDetail(row.connector, row.active),
            selected = key == selected, onClick = { selectedKey = key })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostAction("Move up", Modifier.weight(1f), enabled = canEdit && selected != null && draft!!.canMove(selected, -1),
            icon = R.drawable.ic_prev, onClick = { selected?.let { onDraft(draft!!.move(it, -1)) } })
        GhostAction("Move down", Modifier.weight(1f), enabled = canEdit && selected != null && draft!!.canMove(selected, 1),
            icon = R.drawable.ic_next, onClick = { selected?.let { onDraft(draft!!.move(it, 1)) } })
    }
    val missing = DisplayOrderPresentation.disconnectedSavedCount(order)
    if (missing > 0) StatusLine("$missing saved display${if (missing == 1) " is" else "s are"} not connected",
        StatusTone.UNKNOWN, detail = "The host skips them until they return")
    when {
        draft?.pendingKeys != null -> StatusLine("Waiting for the host to report the saved order", StatusTone.PENDING)
        draft?.dirty == true -> StatusLine("Unsaved changes", StatusTone.PENDING,
            detail = "Save the order for it to take effect")
    }
    if (order.restartRequired) StatusLine("Restart Gaming Mode to apply the saved order", StatusTone.PENDING)
    if (blockedReason != null) StatusLine(blockedReason, StatusTone.WARNING)

    PrimaryAction("Save for next session", enabled = canEdit, icon = R.drawable.ic_download, onClick = onSave)
    DangerAction("Save and restart Gaming Mode", enabled = canEdit && order.restartAvailable,
        icon = R.drawable.ic_replay, onClick = onSaveAndRestart)
    GhostAction("Use automatic order", enabled = canAct && order.savedOutputKeys.isNotEmpty() && !order.unsupported &&
        !order.ambiguous, onClick = onReset)
    GhostAction("Refresh order", enabled = draft != null || order.available,
        onClick = { onDraft(DisplayOrderDraft.from(order)) })
}

private fun orderDetail(connector: String?, active: Boolean?): String =
    listOfNotNull(connector, "Active".takeIf { active == true }, "Not active".takeIf { active == false })
        .joinToString(" · ")
