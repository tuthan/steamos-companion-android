package io.github.tuthan.steamoscompanion.android.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.core.app.ApplicationProvider
import kotlin.math.abs
import kotlin.math.pow

/** Pixel and colour helpers for the tests that look at what was actually drawn. */
internal object PixelSupport {
    fun color(value: Long) = Color(value.toInt())

    fun SemanticsNodeInteraction.pixels(): PixelMap = captureToImage().toPixelMap()

    /** The colours a text node was laid out with: its base colour and any span colours. */
    fun SemanticsNodeInteraction.textColors(): Set<Color> {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val input = results.single().layoutInput
        return setOf(input.style.color) + input.text.spanStyles.map { it.item.color }
    }

    /** Channels as 0..255. */
    fun channels(color: Color): IntArray {
        val srgb = color.convert(ColorSpaces.Srgb)
        return intArrayOf((srgb.red * 255f + .5f).toInt(), (srgb.green * 255f + .5f).toInt(), (srgb.blue * 255f + .5f).toInt())
    }

    /** The largest per-channel difference, 0..255. */
    fun distance(first: Color, second: Color): Int {
        val a = channels(first)
        val b = channels(second)
        return (0..2).maxOf { abs(a[it] - b[it]) }
    }

    /** The pixel farthest from [background]: for a solid glyph on a flat fill, the text colour itself. */
    fun farthestFrom(map: PixelMap, background: Color, x0: Int = 0, y0: Int = 0, x1: Int = map.width, y1: Int = map.height): Color {
        var best = background
        var bestDistance = -1
        for (y in y0 until y1) for (x in x0 until x1) {
            val pixel = map[x, y]
            val d = distance(pixel, background)
            if (d > bestDistance) { best = pixel; bestDistance = d }
        }
        return best
    }

    fun luminance(color: Color): Double {
        fun channel(value: Float): Double =
            if (value <= .04045f) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        val srgb = color.convert(ColorSpaces.Srgb)
        return .2126 * channel(srgb.red) + .7152 * channel(srgb.green) + .0722 * channel(srgb.blue)
    }

    fun contrast(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    /** Turns system animations off (animator duration scale 0), which the app reads as reduce motion. */
    fun setReduceMotion(on: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, if (on) 0f else 1f)
    }
}
