package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import kotlin.math.roundToInt

/** The four motions the design profile budgets besides the discovery strip and the countdown. */
internal object CompanionMotionSpec {
    /** Row press: brightness +15 % over 150 ms, as the site's `.btn` hover. */
    const val PRESS_BRIGHTNESS = .15f

    /** A screen or card state swap: 380 ms fade plus an 8 dp upward slide. */
    const val STATE_SWAP_SLIDE_DP = 8

    /** The wake-packet dot's size and glow radius. */
    const val WAKE_DOT_DP = 8
    const val WAKE_DOT_GLOW_DP = 14

    const val WAKE_DOT_TAG = "wake-packet-dot"
}

/**
 * Brightens whatever it wraps by [CompanionMotionSpec.PRESS_BRIGHTNESS] while [source] is pressed, easing over 150 ms;
 * a cut with reduce motion. At rest it draws nothing, so a resting row is pixel-identical to one without it. Apply it
 * inside the row's clip so the brightening follows the shape.
 */
@Composable
internal fun rememberPressBrightness(source: InteractionSource): Modifier {
    val palette = LocalCompanionPalette.current
    val reduceMotion = rememberReduceMotion()
    val pressed = source.collectIsPressedAsState()
    val brightness = animateFloatAsState(
        targetValue = if (pressed.value) CompanionMotionSpec.PRESS_BRIGHTNESS else 0f,
        animationSpec = tween(CompanionMotion.duration(CompanionMotion.PRESS_MILLIS, reduceMotion)),
        label = "press brightness",
    )
    val lift = Color(palette.white.toInt())
    return Modifier.drawWithContent {
        drawContent()
        val amount = brightness.value
        if (amount > 0f) drawRect(lift.copy(alpha = amount))
    }
}

/**
 * A state swap: the old state fades out while the new one fades in and slides up 8 dp, over 380 ms. Reduce motion makes
 * it a cut. Key it on what the owner reads (a headline), never on a ticking value, so it never animates per poll.
 */
@Composable
internal fun <T> StateSwap(state: T, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val millis = CompanionMotion.duration(CompanionMotion.STATE_CHANGE_MILLIS, reduceMotion)
    val slidePx = with(density) { CompanionMotionSpec.STATE_SWAP_SLIDE_DP.dp.roundToPx() }
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            val swap: ContentTransform = if (millis == 0) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(millis)) + slideInVertically(tween(millis)) { slidePx })
                .togetherWith(fadeOut(tween(millis)))
            swap.using(SizeTransform(clip = false) { _, _ -> snap() })
        },
        label = "state swap",
    ) { target -> content(target) }
}

/**
 * The wake-packet link: one dot travels from this phone to the device along the link line for 1.15 s each time a packet
 * is sent, then rests at the device. [sendKey] identifies the send. The animation is decorative, so it carries no
 * semantics; with reduce motion nothing is drawn and the status line's "Wake packet sent" carries the fact alone.
 */
@Composable
internal fun WakePacketLink(sendKey: Any, modifier: Modifier = Modifier) {
    val palette = LocalCompanionPalette.current
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return
    val travelled = remember(sendKey) { Animatable(0f) }
    LaunchedEffect(sendKey) {
        travelled.snapTo(0f)
        travelled.animateTo(1f, tween(CompanionMotion.WAKE_PACKET_MILLIS, easing = LinearEasing))
    }
    val blue = Color(palette.blue.toInt())
    Row(modifier.fillMaxWidth().clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(painterResource(R.drawable.ic_phone), contentDescription = null, modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(Color(palette.textDim.toInt())))
        BoxWithConstraints(Modifier.weight(1f).height(CompanionMotionSpec.WAKE_DOT_GLOW_DP.dp * 2)) {
            val glow = CompanionMotionSpec.WAKE_DOT_GLOW_DP.dp
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(1.dp)
                .background(Color(palette.lineStrong.toInt())))
            val dotTravel = with(LocalDensity.current) { (maxWidth - glow).toPx() }
            // The blue glow belongs to this packet indicator only; the dot sits in its centre.
            Box(Modifier.align(Alignment.CenterStart)
                .offset { IntOffset((dotTravel * travelled.value).roundToInt(), 0) }
                .testTag(CompanionMotionSpec.WAKE_DOT_TAG)
                .size(glow).background(blue.copy(alpha = CompanionAlpha.GLOW / 2f), CircleShape),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(CompanionMotionSpec.WAKE_DOT_DP.dp).background(blue, CircleShape))
            }
        }
        Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(Color(palette.textDim.toInt())))
    }
}
