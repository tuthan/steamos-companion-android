package io.github.tuthan.steamoscompanion.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import java.util.Locale

/** The site's `.pill`: a translucent capsule for meta facts, optionally with the glowing dot. */
@Composable
fun Pill(text: String, dotTone: StatusTone? = null) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.PILL_DP.dp)
    Row(Modifier.border(1.dp, Color(palette.lineStrong.toInt()), shape)
        .background(Color(palette.panel.toInt()), shape)
        .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        dotTone?.let { StatusGlyphIcon(StatusGlyph.DOT, it, sizeDp = 14) }
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = Color(palette.textSoft.toInt()))
    }
}

/** The site's `.tag` / `.q-badge`: uppercase, tracked, tone text on a 14 % tint of the same tone. */
@Composable
fun Badge(text: String, tone: StatusTone, @DrawableRes icon: Int? = null) {
    val palette = LocalCompanionPalette.current
    val color = Color(tone.color(palette).toInt())
    val shape = RoundedCornerShape(4.dp)
    Row(Modifier.background(color.copy(alpha = CompanionAlpha.BADGE_FILL), shape)
        .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(13.dp),
            colorFilter = ColorFilter.tint(color))
        Text(text.uppercase(Locale.ROOT), style = cappedLabelStyle(MaterialTheme.typography.labelSmall), color = color,
            maxLines = 1, softWrap = false)
    }
}

sealed interface ProgressState {
    data object Indeterminate : ProgressState
    data class Determinate(val fraction: Float) : ProgressState {
        init { require(fraction in 0f..1f) }
    }
}

/** An unknown progress value is never presented as a fabricated percentage. */
@Composable
fun ProgressStrip(state: ProgressState, reduceMotion: Boolean = false, modifier: Modifier = Modifier) {
    val palette = LocalCompanionPalette.current
    val fraction = when (state) {
        ProgressState.Indeterminate -> .33f
        is ProgressState.Determinate -> state.fraction
    }
    val slide = if (state == ProgressState.Indeterminate && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "discovery progress")
        val position by transition.animateFloat(0f, 1f,
            animationSpec = infiniteRepeatable(tween(CompanionMotion.DISCOVERY_SWEEP_MILLIS,
                easing = LinearEasing), repeatMode = RepeatMode.Restart),
            label = "progress position")
        position
    } else 0f
    val description = when (state) {
        ProgressState.Indeterminate -> "Working"
        is ProgressState.Determinate -> "${(state.fraction * 100).toInt()} percent complete"
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp)
        .background(Color(palette.lineStrong.toInt()), RoundedCornerShape(CompanionShapes.PILL_DP.dp))
        .semantics { contentDescription = description }) {
        Box(Modifier.offset(x = maxWidth * (1f - fraction) * slide)
            .fillMaxWidth(fraction).height(4.dp)
            .background(Brush.horizontalGradient(listOf(Color(palette.gradProgressStart.toInt()),
                Color(palette.gradProgressEnd.toInt()))), RoundedCornerShape(CompanionShapes.PILL_DP.dp)))
    }
}

/** The site's `.q-radio`: a radio ring beside the title so selection never rests on colour alone. */
@Composable
fun ChoiceCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.CARD_DP.dp)
    val blue = Color(palette.blue.toInt())
    val border = if (selected) blue else Color(palette.lineStrong.toInt())
    val fill = if (selected) blue.copy(alpha = CompanionAlpha.SELECTED_FILL) else Color(palette.panel.toInt())
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, border, shape)
        .background(fill, shape)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(CompanionSpacing.CARD_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 2.dp).size(20.dp)
            .border(2.dp, if (selected) blue else Color(palette.textDim.toInt()), CircleShape),
            contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).background(blue, CircleShape))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color(palette.heading.toInt()))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Color(palette.textDim.toInt()))
        }
    }
}

enum class WireDirection(val label: String, val arrow: String, val tone: StatusTone) {
    REQUEST("Sent", "→", StatusTone.PENDING),
    RESPONSE("Received", "←", StatusTone.GOOD),
    LOCAL("Local", "!", StatusTone.WARNING),
    BLOCKED("Blocked", "!", StatusTone.BLOCKED),
}

data class WireLogEntry(val direction: WireDirection, val message: String)

/** The site's `.wire`: a slab with a toned direction chip and a mono line per entry. */
@Composable
fun WireLog(entries: List<WireLogEntry>) {
    val palette = LocalCompanionPalette.current
    Column(Modifier.fillMaxWidth().background(Color(palette.slab.toInt()),
        RoundedCornerShape(CompanionShapes.CARD_DP.dp))
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.widthIn(min = 100.dp)) { Badge("${entry.direction.arrow} ${entry.direction.label}", entry.direction.tone) }
                Text(entry.message, style = CompanionMonoStyle, color = Color(palette.mono.toInt()),
                    modifier = Modifier.weight(1f).padding(top = 3.dp))
            }
        }
    }
}

/** The site's `.steps`: completed steps carry a check, the active step is emphasised. */
@Composable
fun StepRail(steps: List<String>, activeIndex: Int) {
    require(activeIndex in steps.indices)
    val palette = LocalCompanionPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, label ->
            val done = index < activeIndex
            val active = index == activeIndex
            val tone = when {
                done -> StatusTone.GOOD
                active -> StatusTone.PENDING
                else -> StatusTone.UNKNOWN
            }
            val color = Color(tone.color(palette).toInt())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp)
                    .background(color.copy(alpha = CompanionAlpha.BADGE_FILL), CircleShape)
                    .border(1.dp, color.copy(alpha = if (active) 1f else .5f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (done) Image(painterResource(R.drawable.ic_check), contentDescription = null,
                        modifier = Modifier.size(14.dp), colorFilter = ColorFilter.tint(color))
                    else Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = color)
                }
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    color = Color((if (active) palette.heading else if (done) palette.text else palette.textDim).toInt()),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** The site's `.tabs`: uppercase tracked labels with a blue underline under the selected one. */
@Composable
fun SegmentTabs(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    require(items.isNotEmpty() && selectedIndex in items.indices)
    val palette = LocalCompanionPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(Modifier.weight(1f).selectable(selected = selected, role = Role.Tab,
                    onClick = { onSelect(index) }).heightIn(min = CompanionSpacing.MIN_TOUCH_TARGET_DP.dp),
                    verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.uppercase(Locale.ROOT), Modifier.padding(vertical = 12.dp),
                        style = cappedLabelStyle(MaterialTheme.typography.labelMedium), textAlign = TextAlign.Center,
                        color = Color((if (selected) palette.heading else palette.textDim).toInt()))
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(Color((if (selected) palette.blue else palette.line).toInt())))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(palette.line.toInt())))
    }
}

/** The plugin header on a phone: `bgDeep`, the 8 dp back square, and the plain app title. */
@Composable
fun CompanionAppBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val palette = LocalCompanionPalette.current
    Row(modifier.fillMaxWidth().background(Color(palette.bgDeep.toInt()))
        .padding(horizontal = CompanionSpacing.SCREEN_GUTTER_DP.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onBack != null) {
            Box(Modifier.size(40.dp)
                .clip(RoundedCornerShape(CompanionShapes.BACK_BUTTON_DP.dp))
                .background(Color(palette.lineStrong.toInt()), RoundedCornerShape(CompanionShapes.BACK_BUTTON_DP.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_prev), contentDescription = null, modifier = Modifier.size(18.dp),
                    colorFilter = ColorFilter.tint(Color(palette.white.toInt())))
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color(palette.white.toInt()),
            modifier = Modifier.weight(1f).heightIn(min = 40.dp).padding(top = 8.dp))
    }
}

/** The hero lockup: white disc with the navy mark above the stacked uppercase wordmark. */
@Composable
fun BrandLockup(wordmarkStrong: String, wordmarkLight: String, modifier: Modifier = Modifier) {
    val palette = LocalCompanionPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(96.dp)
            .background(Color(palette.white.toInt()).copy(alpha = .10f), CircleShape)
            .padding(8.dp), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.brand_mark_hero), contentDescription = null, modifier = Modifier.size(80.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(wordmarkStrong.uppercase(Locale.ROOT), style = CompanionWordmarkStyle.copy(fontWeight = FontWeight.Bold),
                color = Color(palette.white.toInt()), textAlign = TextAlign.Center)
            Text(wordmarkLight.uppercase(Locale.ROOT), style = CompanionWordmarkStyle.copy(fontWeight = FontWeight.Light),
                color = Color(palette.textSoft.toInt()), textAlign = TextAlign.Center)
        }
    }
}

/** A label / mono-value fact row inside a slab, as the mockup's device table. */
@Composable
fun FactRow(label: String, value: String, divider: Boolean = true) {
    val palette = LocalCompanionPalette.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color(palette.text.toInt()))
            Text(value, style = CompanionMonoStyle, color = Color(palette.mono.toInt()), textAlign = TextAlign.End,
                modifier = Modifier.weight(1f))
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(palette.line.toInt())))
    }
}

/** Groups [FactRow]s on the slab surface. */
@Composable
fun FactTable(content: @Composable () -> Unit) {
    val palette = LocalCompanionPalette.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CompanionShapes.CARD_DP.dp))
        .background(Color(palette.slab.toInt()))
        .border(1.dp, Color(palette.line.toInt()), RoundedCornerShape(CompanionShapes.CARD_DP.dp))) { content() }
}
