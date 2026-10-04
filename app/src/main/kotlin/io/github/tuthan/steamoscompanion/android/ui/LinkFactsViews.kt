package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.LinkAdvisoryNotice
import io.github.tuthan.steamoscompanion.core.client.LinkLine

/**
 * One muted line naming the physical link behind the selected output: connector, GPU card, display name
 * and the display's identity. It reads as a single unit. The facts come from the host's kernel and are
 * advisory; the line claims nothing about what the display can do.
 */
@Composable
fun LinkFactsLine(line: LinkLine) {
    val palette = LocalCompanionPalette.current
    val join = stringResource(R.string.ui_link_join)
    val vendor = line.vendor
    val product = line.productId
    val parts = listOfNotNull(
        line.connector,
        line.card,
        line.displayName,
        if (vendor != null && product != null) stringResource(R.string.ui_link_model, vendor, product) else null,
    )
    val text = stringResource(R.string.ui_link_line, parts.joinToString(join))
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
        color = Color(palette.textDim.toInt()))
}

/**
 * The advisory shown directly above Preview when the selected mode's resolution is not in the display
 * link's list. It is a warning, never a block, and not colour alone: an info glyph, the words "Heads up",
 * and an "Advisory" state spoken with the merged label. It says the resolution may still work and never
 * calls the mode invalid.
 */
@Composable
fun LinkAdvisoryRow(notice: LinkAdvisoryNotice) {
    val state = stringResource(R.string.ui_link_advisory_state)
    StatusLine(
        text = stringResource(R.string.ui_link_advisory_title),
        tone = StatusTone.WARNING,
        modifier = Modifier.semantics { stateDescription = state },
        glyph = StatusGlyph.INFO,
        detail = stringResource(R.string.ui_link_advisory_body, notice.resolution.replace("x", " × ")),
    )
}
