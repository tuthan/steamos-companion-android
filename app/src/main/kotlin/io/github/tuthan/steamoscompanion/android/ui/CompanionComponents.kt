package io.github.tuthan.steamoscompanion.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tuthan.steamoscompanion.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Colour family of a state row. The word, the glyph, and the colour always travel together (R8). */
enum class StatusTone { GOOD, WARNING, BLOCKED, UNKNOWN, PENDING }

/** Glyph vocabulary from the design profile's state table. A tone picks a default; callers may override. */
enum class StatusGlyph(@DrawableRes val icon: Int) {
    DOT(R.drawable.ic_status_dot),
    CHECK(R.drawable.ic_check),
    CLOCK(R.drawable.ic_status_clock),
    BLOCKED(R.drawable.ic_status_blocked),
    UNKNOWN(R.drawable.ic_status_unknown),
    INFO(R.drawable.ic_status_info),
    PENDING(R.drawable.ic_status_pending),
    QUESTION(R.drawable.ic_status_question),
    LOCK(R.drawable.ic_lock),
    KEY(R.drawable.ic_key),
    FINGERPRINT(R.drawable.ic_fingerprint),
    RADAR(R.drawable.ic_radar),
}

internal fun StatusTone.color(palette: CompanionPalette): Long = when (this) {
    StatusTone.GOOD -> palette.green
    StatusTone.WARNING -> palette.amber
    StatusTone.BLOCKED -> palette.red
    StatusTone.UNKNOWN -> palette.textDim
    StatusTone.PENDING -> palette.blue
}

internal fun StatusTone.defaultGlyph(): StatusGlyph = when (this) {
    StatusTone.GOOD -> StatusGlyph.CHECK
    StatusTone.WARNING -> StatusGlyph.INFO
    StatusTone.BLOCKED -> StatusGlyph.BLOCKED
    StatusTone.UNKNOWN -> StatusGlyph.UNKNOWN
    StatusTone.PENDING -> StatusGlyph.PENDING
}

/** Decorative labels may cap at 1.3x font scale because a full-size line always sits beside them. */
@Composable
internal fun cappedLabelStyle(style: TextStyle): TextStyle {
    val scale = LocalDensity.current.fontScale
    if (scale <= CompanionType.DECORATIVE_SCALE_CAP) return style
    val ratio = CompanionType.DECORATIVE_SCALE_CAP / scale
    return style.copy(fontSize = style.fontSize * ratio, letterSpacing = style.letterSpacing * ratio)
}

@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val palette = LocalCompanionPalette.current
    Text(text.uppercase(Locale.ROOT), modifier = modifier,
        style = cappedLabelStyle(MaterialTheme.typography.labelMedium),
        color = Color((if (accent) palette.blue else palette.textDim).toInt()))
}

@Composable
fun MonoValue(text: String, modifier: Modifier = Modifier) {
    val palette = LocalCompanionPalette.current
    SelectionContainer {
        Text(text, modifier = modifier, style = CompanionMonoStyle,
            color = Color(palette.mono.toInt()), softWrap = true)
    }
}

/** A glyph drawn in a tone colour. The DOT form carries the connected glow; nothing else glows. */
@Composable
fun StatusGlyphIcon(glyph: StatusGlyph, tone: StatusTone, modifier: Modifier = Modifier, sizeDp: Int = 20) {
    val palette = LocalCompanionPalette.current
    val color = Color(tone.color(palette).toInt())
    if (glyph == StatusGlyph.DOT) {
        Box(modifier.size(sizeDp.dp).drawBehind {
            val centre = Offset(size.width / 2f, size.height / 2f)
            if (tone == StatusTone.GOOD) {
                drawCircle(color.copy(alpha = .16f), radius = size.minDimension / 2f, center = centre)
                drawCircle(color.copy(alpha = .30f), radius = size.minDimension / 3f, center = centre)
            }
            drawCircle(color, radius = size.minDimension / 5f, center = centre)
        })
    } else {
        Image(painterResource(glyph.icon), contentDescription = null,
            modifier = modifier.size(sizeDp.dp), colorFilter = ColorFilter.tint(color))
    }
}

/**
 * One fact per line. The headline takes the tone colour and glyph; [detail] is the dim qualifier
 * ("Checked 2 s ago"). The row is not a touch target, so it sizes to its text.
 */
@Composable
fun StatusLine(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    glyph: StatusGlyph? = null,
    detail: String? = null,
) {
    val palette = LocalCompanionPalette.current
    val headline = if (tone == StatusTone.UNKNOWN) palette.text else tone.color(palette)
    Row(modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusGlyphIcon(glyph ?: tone.defaultGlyph(), tone, Modifier.padding(top = 1.dp))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Color(headline.toInt()), fontWeight = FontWeight.Medium)) { append(text) }
            if (!detail.isNullOrBlank()) {
                withStyle(SpanStyle(color = Color(palette.textDim.toInt()))) { append(" · "); append(detail) }
            }
        }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Full-width touch row. [navigates] adds the chevron, [selected] adds the check plus the tinted
 * border the site uses for `.step.is-active`, [danger] applies the red row recipe, and a disabled
 * row stays readable and speaks its [reason].
 */
@Composable
fun ActionRow(
    title: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    trailing: String? = null,
    enabled: Boolean = true,
    reason: String? = null,
    @DrawableRes icon: Int? = null,
    navigates: Boolean = false,
    selected: Boolean = false,
    danger: Boolean = false,
    badge: String? = null,
    badgeTone: StatusTone = StatusTone.UNKNOWN,
    monoTitle: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.ACTION_ROW_DP.dp)
    val showReason = !enabled && !reason.isNullOrBlank() && reason != secondary
    val description = buildString {
        append(title)
        if (selected) append(", selected")
        if (!enabled && !reason.isNullOrBlank()) append(". Unavailable: $reason")
    }
    val borderColor = when {
        danger -> Color(palette.red.toInt()).copy(alpha = CompanionAlpha.DANGER_BORDER)
        selected -> Color(palette.lineStrong.toInt())
        else -> Color(palette.line.toInt())
    }
    val fillColor = when {
        danger -> Color(palette.red.toInt()).copy(alpha = CompanionAlpha.DANGER_FILL)
        selected -> Color(palette.blue.toInt()).copy(alpha = CompanionAlpha.SELECTED_FILL)
        else -> Color(palette.field.toInt())
    }
    val titleColor = when {
        !enabled -> palette.textDim
        danger -> palette.red
        else -> palette.text
    }
    val iconColor = when {
        !enabled -> palette.textDim
        danger -> palette.red
        else -> palette.blue
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(fillColor, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (selected) this.selected = true
            }
            .heightIn(min = CompanionSpacing.MIN_TOUCH_TARGET_DP.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(Color(iconColor.toInt())))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (monoTitle) Text(title, style = CompanionMonoStyle.copy(fontSize = CompanionType.MODE_ROW_SP.sp),
                color = Color((if (enabled) palette.mono else palette.textDim).toInt()))
            else Text(title, style = MaterialTheme.typography.titleMedium, color = Color(titleColor.toInt()))
            if (!secondary.isNullOrBlank()) Text(secondary, style = MaterialTheme.typography.bodyMedium,
                color = Color(palette.textDim.toInt()))
            if (showReason) Text(reason!!, style = MaterialTheme.typography.bodyMedium,
                color = Color(palette.textDim.toInt()))
        }
        if (badge != null) Badge(badge, badgeTone)
        if (trailing != null) {
            Text(trailing, style = CompanionMonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = Color(palette.mono.toInt()))
        }
        if (selected) {
            Image(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(Color(palette.blue.toInt())))
        } else if (navigates) {
            Image(painterResource(R.drawable.ic_chevron), contentDescription = null, modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(Color(palette.textDim.toInt())))
        }
    }
}

/**
 * The site's `-60deg` button gradient: the light stop sits at the lower-right corner and the dark
 * stop at the upper-left, with the 1 px inset highlight along the top edge.
 */
internal fun Modifier.brandGradient(start: Long, end: Long, radiusDp: Float, highlight: Long? = null): Modifier =
    drawBehind {
        val angle = Math.toRadians(-60.0)
        val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
        val length = abs(size.width * direction.x) + abs(size.height * direction.y)
        val centre = Offset(size.width / 2f, size.height / 2f)
        val half = Offset(direction.x * length / 2f, direction.y * length / 2f)
        val radius = CornerRadius(radiusDp.dp.toPx())
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color(start.toInt()), Color(end.toInt())),
                start = centre - half, end = centre + half),
            cornerRadius = radius,
        )
        if (highlight != null) {
            val inset = radius.x
            drawLine(Color(highlight.toInt()).copy(alpha = .16f), Offset(inset, .5f), Offset(size.width - inset, .5f),
                strokeWidth = 1f)
        }
    }

@Composable
private fun ActionLabel(label: String, @DrawableRes icon: Int?, color: Long, weight: FontWeight) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(Color(color.toInt())))
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = weight),
            color = Color(color.toInt()))
    }
}

/** The one gradient primary per screen. [green] is the Keep-and-save form with its dark label. */
@Composable
fun PrimaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                  green: Boolean = false, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.ACTION_ROW_DP.dp)
    val start = if (green) palette.gradGreenStart else palette.gradPrimaryStart
    val end = if (green) palette.gradGreenEnd else palette.gradPrimaryEnd
    val foreground = if (green) palette.gradGreenLabel else palette.white
    Box(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)
        .clip(shape)
        .brandGradient(start, end, CompanionShapes.ACTION_ROW_DP, highlight = palette.white)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = CompanionSpacing.MIN_TOUCH_TARGET_DP.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        ActionLabel(label, icon, foreground, FontWeight.SemiBold)
    }
}

@Composable
fun GhostAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.ACTION_ROW_DP.dp)
    val blue = Color(palette.blue.toInt())
    Box(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)
        .clip(shape)
        .border(1.dp, blue.copy(alpha = CompanionAlpha.GHOST_BORDER), shape)
        .background(blue.copy(alpha = CompanionAlpha.GHOST_FILL), shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = CompanionSpacing.MIN_TOUCH_TARGET_DP.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        ActionLabel(label, icon, palette.blue, FontWeight.SemiBold)
    }
}

/** The site's `.q-btn.danger`: red text, red 40 % border, red 6 % fill. Reject, Remove, Forget. */
@Composable
fun DangerAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                 @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.ACTION_ROW_DP.dp)
    val red = Color(palette.red.toInt())
    Box(modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)
        .clip(shape)
        .border(1.dp, red.copy(alpha = CompanionAlpha.DANGER_BORDER), shape)
        .background(red.copy(alpha = CompanionAlpha.DANGER_FILL), shape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = CompanionSpacing.MIN_TOUCH_TARGET_DP.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        ActionLabel(label, icon, palette.red, FontWeight.SemiBold)
    }
}

/** The site's `.card` with its `.ico` well: gradient panel, hairline, icon in a blue 12 % well. */
@Composable
fun InfoCard(title: String, body: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null,
             content: (@Composable () -> Unit)? = null) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.CARD_DP.dp)
    Row(modifier.fillMaxWidth().clip(shape).border(1.dp, Color(palette.line.toInt()), shape)
        .background(Brush.verticalGradient(listOf(Color(palette.panel2.toInt()), Color(palette.panel.toInt()))), shape)
        .padding(CompanionSpacing.CARD_PADDING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (icon != null) {
            Box(Modifier.size(40.dp)
                .background(Color(palette.blue.toInt()).copy(alpha = CompanionAlpha.GHOST_FILL),
                    RoundedCornerShape(CompanionShapes.ICON_WELL_DP.dp)),
                contentAlignment = Alignment.Center) {
                Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(Color(palette.blue.toInt())))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color(palette.heading.toInt()))
            if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyLarge,
                color = Color(palette.textSoft.toInt()))
            content?.invoke()
        }
    }
}

/** Top of the paired overview: kicker, name in white, endpoint in mono, then the reachability fact. */
@Composable
fun DeviceHeader(name: String, endpoint: String, status: String, tone: StatusTone,
                 modifier: Modifier = Modifier, kicker: String? = null, glyph: StatusGlyph? = null,
                 detail: String? = null) {
    val palette = LocalCompanionPalette.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (kicker != null) Kicker(kicker, Modifier.padding(bottom = 4.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, color = Color(palette.heading.toInt()))
        MonoValue(endpoint)
        StatusLine(status, tone, Modifier.padding(top = 6.dp), glyph = glyph, detail = detail)
    }
}

/** Bold, tabular digits inside a countdown sentence such as "Reverts in 12 seconds". */
private fun countdownText(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    val digits = Regex("\\d+")
    for (match in digits.findAll(text)) {
        append(text.substring(index, match.range.first))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")) {
            append(match.value)
        }
        index = match.range.last + 1
    }
    append(text.substring(index))
}

/**
 * The floating preview card: the only surface with a shadow. A thin strip along its top drains
 * with the host deadline when [totalSeconds] is known; the countdown turns amber for its last
 * five seconds, and Keep and save is the green primary with its dark label.
 */
@Composable
fun PreviewCard(mode: String, remainingSeconds: Long?, status: String,
                canRevert: Boolean, canKeep: Boolean, onRevert: () -> Unit, onKeep: () -> Unit,
                modifier: Modifier = Modifier, deviceName: String? = null, question: String? = null,
                totalSeconds: Long? = null, revertReason: String? = null) {
    val palette = LocalCompanionPalette.current
    val shape = RoundedCornerShape(CompanionShapes.DEVICE_FRAME_DP.dp)
    val urgent = remainingSeconds != null && remainingSeconds <= CompanionMotion.PREVIEW_AMBER_LAST_SECONDS
    val countdownColor = Color((if (urgent) palette.amber else palette.text).toInt())
    Column(modifier.fillMaxWidth()
        .shadow(8.dp, shape, ambientColor = Color(palette.bgDeep.toInt()), spotColor = Color(palette.bgDeep.toInt()))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(palette.panel2.toInt()), Color(palette.panel.toInt()))), shape)
        .border(1.dp, Color(palette.line.toInt()), shape)) {
        if (remainingSeconds != null && totalSeconds != null && totalSeconds > 0) {
            ProgressStrip(ProgressState.Determinate((remainingSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)),
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(CompanionSpacing.CARD_PADDING_DP.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Kicker(if (deviceName.isNullOrBlank()) "Display preview" else "Display preview · $deviceName", accent = true)
            Text(mode, style = MaterialTheme.typography.bodyMedium, color = Color(palette.textDim.toInt()))
            if (!question.isNullOrBlank()) {
                Text(question, style = MaterialTheme.typography.titleMedium, color = Color(palette.heading.toInt()))
            }
            Text(countdownText(status, countdownColor), style = MaterialTheme.typography.bodyLarge,
                color = countdownColor)
            ActionRow("Revert now", enabled = canRevert,
                reason = revertReason ?: if (canRevert) null else "Preview ownership is unconfirmed", onClick = onRevert)
            PrimaryAction("Keep and save", enabled = canKeep, green = true, icon = R.drawable.ic_check, onClick = onKeep)
        }
    }
}
