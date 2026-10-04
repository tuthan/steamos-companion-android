package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.HostJournalPresentation
import io.github.tuthan.steamoscompanion.core.client.HostJournalRow
import io.github.tuthan.steamoscompanion.core.client.HostJournalState

/**
 * The "Host journal" group of Connection details: what the host recorded about operations it ran for this
 * phone and for the Deck's own display, newest first. It is diagnostics, not status: each row is one
 * readable unit with a spoken description, and a standing note says a completed entry does not by itself
 * show that the picture is visible. [state] is already for this pairing; null means nothing has been asked
 * yet. Loading, "not available" (an older host, a rejection, a transport or decode problem) and an empty
 * journal are each a single muted line, never an error.
 */
@Composable
fun HostJournalSection(state: HostJournalState?, onRefresh: () -> Unit) {
    Kicker(stringResource(R.string.ui_host_journal), Modifier.padding(top = 8.dp))
    ActionRow(stringResource(R.string.ui_host_journal_refresh), enabled = state !is HostJournalState.Loading,
        icon = R.drawable.ic_replay, onClick = onRefresh)
    when (state) {
        null -> Unit
        is HostJournalState.Loading -> JournalMutedLine(stringResource(R.string.ui_host_journal_loading))
        is HostJournalState.Unavailable -> JournalMutedLine(stringResource(R.string.ui_host_journal_unavailable))
        is HostJournalState.Ready -> {
            val view = remember(state.journal) { HostJournalPresentation.view(state.journal) }
            if (view.rows.isEmpty()) JournalMutedLine(stringResource(R.string.ui_host_journal_empty))
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CompanionSpacing.ROW_GROUP_DP.dp)) {
                view.rows.forEach { JournalRow(it) }
            }
            if (view.truncated) JournalMutedLine(stringResource(R.string.ui_host_journal_showing, view.rows.size, view.total))
            JournalMutedLine(HostJournalPresentation.STANDING_NOTE)
        }
    }
}

/** Title and muted detail lines, merged into one node that speaks the whole record as sentences. */
@Composable
private fun JournalRow(row: HostJournalRow) {
    val palette = LocalCompanionPalette.current
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = row.spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(row.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = Color(palette.text.toInt()))
        row.lines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(palette.textDim.toInt()))
        }
    }
}

@Composable
private fun JournalMutedLine(text: String) {
    val palette = LocalCompanionPalette.current
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
        color = Color(palette.textDim.toInt()))
}
