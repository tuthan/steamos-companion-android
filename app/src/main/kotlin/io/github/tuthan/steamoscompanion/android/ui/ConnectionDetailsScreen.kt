package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.HostAccessPresentation
import io.github.tuthan.steamoscompanion.core.client.HostHealthUnavailableReason
import io.github.tuthan.steamoscompanion.core.client.HostHealthView
import io.github.tuthan.steamoscompanion.core.client.HostJournalState
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState

private data class ReplacementIntent(val oldHost: ActiveHost, val candidateGeneration: Long)
private enum class RemovalChoice { REVOKE, FORGET }

@Composable
fun ConnectionDetailsScreen(
    host: ActiveHost,
    name: String,
    state: PersistentState?,
    status: StatusPollState,
    busy: Boolean,
    feedback: ActionFeedback?,
    onPairAnother: () -> Unit,
    onConfirmReplacement: (ActiveHost, Long) -> Unit,
    onDiscardReplacement: (Long) -> Unit,
    onRevoke: () -> Unit,
    onForget: () -> Unit,
    onExport: () -> Unit,
    wakeRelay: String = "",
    suggestedWakeRelay: String = "",
    onSaveWakeRelay: (String) -> Boolean = { false },
    /** Projected from the paired session's latest status observation by `HostHealthPresentation`. */
    hostHealth: HostHealthView = HostHealthView.Unavailable(HostHealthUnavailableReason.CHECKING),
    /** The host's operation journal; in memory only, and only ever shown for this pairing. */
    hostJournal: HostJournalState? = null,
    /** Asks for the host journal of this pairing: once when this screen opens, and again on Refresh. */
    onHostJournalWanted: (ActiveHost) -> Unit = {},
) {
    val palette = LocalCompanionPalette.current
    var replacementIntent by remember(StatusObservationSource.from(host)) { mutableStateOf<ReplacementIntent?>(null) }
    var discardGeneration by remember(StatusObservationSource.from(host)) { mutableStateOf<Long?>(null) }
    var removalChoice by remember(StatusObservationSource.from(host)) { mutableStateOf<RemovalChoice?>(null) }
    // Read when this screen opens for this pairing, never from the status poll and never on a timer.
    LaunchedEffect(StatusObservationSource.from(host)) { onHostJournalWanted(host) }
    Kicker("Paired device")
    Text(name, style = MaterialTheme.typography.titleMedium, color = Color(palette.heading.toInt()))
    MonoValue(host.endpoint)
    Kicker("Identity", Modifier.padding(top = 8.dp))
    InfoCard("Certificate pinned", "", icon = R.drawable.ic_lock) { MonoValue(host.certificatePin) }
    HostAccessSection(host.grantedScopes)
    Kicker("Granted permissions", Modifier.padding(top = 8.dp))
    ScopeBadges(host.grantedScopes.toList(), granted = true)
    val fresh = status.source == StatusObservationSource.from(host) && status.lastRead != null &&
        !status.stale && !status.unreachable
    StatusLine(if (fresh) "Status checked recently" else "Latest status not confirmed",
        if (status.unreachable) StatusTone.WARNING else if (fresh) StatusTone.GOOD else StatusTone.UNKNOWN,
        glyph = if (status.unreachable) StatusGlyph.BLOCKED else if (fresh) StatusGlyph.DOT else StatusGlyph.CLOCK)
    HostHealthSection(hostHealth)
    if (host.wakeTarget.available) {
        Kicker("Device", Modifier.padding(top = 8.dp))
        FactTable { FactRow("Wake target", host.wakeTarget.interfaceName ?: "Configured", divider = false) }
        WakeRelayEditor(StatusObservationSource.from(host), wakeRelay, suggestedWakeRelay, busy, onSaveWakeRelay)
    }

    val candidate = state?.candidate?.takeIf { it.phase == CandidatePhase.APPROVED_STAGED }
    if (candidate != null) {
        ReplacementCard(candidate, busy,
            onConfirm = { replacementIntent = ReplacementIntent(host, candidate.generation) },
            onDiscard = { discardGeneration = candidate.generation })
    } else {
        ActionRow(stringResource(R.string.ui_pair_another_device), enabled = !busy, icon = R.drawable.ic_radar,
            navigates = true, onClick = onPairAnother)
    }
    ActionRow(stringResource(R.string.ui_export_sanitized_diagnostics), enabled = !busy, icon = R.drawable.ic_download,
        secondary = stringResource(R.string.ui_export_diagnostics_detail),
        onClick = onExport)

    Kicker("Requests from this phone", Modifier.padding(top = 8.dp))
    if (state?.journal.isNullOrEmpty()) StatusLine("No requests recorded", StatusTone.UNKNOWN)
    else WireLog(state.journal.takeLast(8).asReversed().map { entry ->
        WireLogEntry(if (entry.phase == JournalPhase.FAILED) WireDirection.BLOCKED else WireDirection.REQUEST,
            "${entry.method} ${entry.path} · ${entry.phase.wireValue}${entry.rejectionCode?.let { " · ${it.wireValue}" } ?: ""}" +
                (entry.resultSummary?.takeIf { entry.phase == JournalPhase.FAILED }?.let { " · ${it.take(120)}" } ?: ""))
    })
    HostJournalSection(hostJournal?.forHost(host)) { onHostJournalWanted(host) }
    ActionRow(stringResource(R.string.ui_remove_pairing), enabled = !busy, icon = R.drawable.ic_key,
        secondary = "Revoke on the device or forget here", danger = true, navigates = true,
        onClick = { removalChoice = RemovalChoice.REVOKE })
    feedback?.let { StatusLine(it.text, it.tone) }

    replacementIntent?.let { intent ->
        PictureConfirmationDialog(
            title = "Replace ${intent.oldHost.endpoint}?",
            body = "The new device will become the active pairing. The current device remains usable until this change is saved.",
            confirmLabel = "Use new device",
            onDismiss = { replacementIntent = null },
            onConfirm = { replacementIntent = null; onConfirmReplacement(intent.oldHost, intent.candidateGeneration) },
        )
    }
    discardGeneration?.let { generation ->
        PictureConfirmationDialog(
            title = "Discard the new pairing?",
            body = "The current device remains selected. The app will ask the new host to remove this phone's access.",
            confirmLabel = "Discard new pairing",
            onDismiss = { discardGeneration = null },
            onConfirm = { discardGeneration = null; onDiscardReplacement(generation) },
        )
    }
    removalChoice?.let { choice ->
        when (choice) {
            RemovalChoice.REVOKE -> RemovalChoiceDialog(
                onCancel = { removalChoice = null },
                onRevoke = { removalChoice = null; onRevoke() },
                onForget = { removalChoice = RemovalChoice.FORGET })
            RemovalChoice.FORGET -> PictureConfirmationDialog(
                title = "Forget on this phone only?",
                body = "The host may still list this phone. This removes the local credential without confirming host-side removal.",
                confirmLabel = "Forget on this phone",
                onDismiss = { removalChoice = null },
                onConfirm = { removalChoice = null; onForget() },
            )
        }
    }
}

/**
 * What this pairing may do on the host, in the host's own words: a role, then the permissions held, and a note when it
 * is not full access because the owner of the host chose that and can change it there. [scopes] is the stored pairing's
 * current value, replaced on every status read by what the host reports.
 */
@Composable
internal fun HostAccessSection(scopes: List<String>) {
    val palette = LocalCompanionPalette.current
    val view = remember(scopes) { HostAccessPresentation.view(scopes) }
    Kicker("Access on this host", Modifier.padding(top = 8.dp))
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(view.line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = Color(palette.text.toInt()))
        view.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(palette.textDim.toInt())) }
    }
}

@Composable
private fun WakeRelayEditor(source: Any, saved: String, suggested: String, busy: Boolean,
                            onSave: (String) -> Boolean) {
    val start = saved.ifEmpty { suggested }
    var text by remember(source, saved, suggested) { mutableStateOf(start) }
    var result by remember(source, saved, suggested) { mutableStateOf<Boolean?>(null) }
    val suggesting = saved.isEmpty() && suggested.isNotEmpty() && text.trim() == suggested
    OutlinedTextField(text, onValueChange = { text = it.take(21); result = null },
        label = { Text(stringResource(R.string.ui_wake_relay_label)) },
        supportingText = { Text(stringResource(
            if (suggesting) R.string.ui_wake_relay_suggested else R.string.ui_wake_relay_hint)) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    ActionRow(stringResource(R.string.ui_wake_relay_save), enabled = !busy && text.trim() != saved && (text.isNotBlank() || saved.isNotEmpty()),
        onClick = { result = onSave(text) })
    when (result) {
        true -> StatusLine(stringResource(R.string.ui_wake_relay_saved), StatusTone.GOOD)
        false -> StatusLine(stringResource(R.string.ui_wake_relay_invalid), StatusTone.WARNING)
        null -> Unit
    }
}

@Composable
private fun ReplacementCard(candidate: PairingCandidate, busy: Boolean,
                            onConfirm: () -> Unit, onDiscard: () -> Unit) {
    Kicker("New pairing approved")
    InfoCard("Review the new device", "", icon = R.drawable.ic_fingerprint) {
        MonoValue(candidate.endpoint)
        MonoValue(candidate.certificatePin)
    }
    PrimaryAction("Use new device", enabled = !busy, icon = R.drawable.ic_check, onClick = onConfirm)
    ActionRow("Keep current device and discard new pairing", enabled = !busy, onClick = onDiscard)
}

@Composable
private fun RemovalChoiceDialog(onCancel: () -> Unit, onRevoke: () -> Unit, onForget: () -> Unit) {
    val palette = LocalCompanionPalette.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Color(palette.panel.toInt()),
        title = { Text("Remove this pairing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionRow("Remove access on the host", secondary = "Ask the host to revoke this phone",
                    onClick = onRevoke)
                ActionRow("Forget on this phone only", secondary = "Host removal will not be confirmed",
                    onClick = onForget)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onCancel) {
            Text(stringResource(R.string.ui_cancel))
        } },
    )
}
