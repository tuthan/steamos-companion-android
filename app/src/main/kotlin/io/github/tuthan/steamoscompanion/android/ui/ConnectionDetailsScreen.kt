package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
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
) {
    val palette = LocalCompanionPalette.current
    var replacementIntent by remember(StatusObservationSource.from(host)) { mutableStateOf<ReplacementIntent?>(null) }
    var discardGeneration by remember(StatusObservationSource.from(host)) { mutableStateOf<Long?>(null) }
    var removalChoice by remember(StatusObservationSource.from(host)) { mutableStateOf<RemovalChoice?>(null) }
    Kicker("Paired device")
    Text(name, style = MaterialTheme.typography.titleMedium, color = Color(palette.heading.toInt()))
    MonoValue(host.endpoint)
    Kicker("Identity", Modifier.padding(top = 8.dp))
    InfoCard("Certificate pinned", "", icon = R.drawable.ic_lock) { MonoValue(host.certificatePin) }
    Kicker("Granted permissions", Modifier.padding(top = 8.dp))
    ScopeBadges(host.grantedScopes.toList(), granted = true)
    val fresh = status.source == StatusObservationSource.from(host) && status.lastRead != null &&
        !status.stale && !status.unreachable
    StatusLine(if (fresh) "Status checked recently" else "Latest status not confirmed",
        if (status.unreachable) StatusTone.WARNING else if (fresh) StatusTone.GOOD else StatusTone.UNKNOWN,
        glyph = if (status.unreachable) StatusGlyph.BLOCKED else if (fresh) StatusGlyph.DOT else StatusGlyph.CLOCK)
    if (host.wakeTarget.available) {
        Kicker("Device", Modifier.padding(top = 8.dp))
        FactTable { FactRow("Wake target", host.wakeTarget.interfaceName ?: "Configured", divider = false) }
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
        secondary = "Exports bounded status and operation facts without stored secrets",
        onClick = onExport)

    Kicker("Operation journal", Modifier.padding(top = 8.dp))
    if (state?.journal.isNullOrEmpty()) StatusLine("No operations recorded", StatusTone.UNKNOWN)
    else WireLog(state.journal.takeLast(8).asReversed().map { entry ->
        WireLogEntry(if (entry.phase == JournalPhase.FAILED) WireDirection.BLOCKED else WireDirection.REQUEST,
            "${entry.method} ${entry.path} · ${entry.phase.wireValue}${entry.rejectionCode?.let { " · ${it.wireValue}" } ?: ""}")
    })
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
