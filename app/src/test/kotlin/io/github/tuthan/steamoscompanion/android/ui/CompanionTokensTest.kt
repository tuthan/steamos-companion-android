package io.github.tuthan.steamoscompanion.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTokensTest {
    @Test
    fun documentedBodyAndStateColorsMeetNormalTextContrastInBothThemes() {
        assertContrast(CompanionColors.dark.text, CompanionColors.dark.bg, 4.5)
        assertContrast(CompanionColors.dark.textDim, CompanionColors.dark.bg, 4.5)
        assertContrast(CompanionColors.dark.blue, CompanionColors.dark.bg, 4.5)
        assertContrast(CompanionColors.dark.green, CompanionColors.dark.bg, 4.5)
        assertContrast(CompanionColors.dark.red, CompanionColors.dark.bg, 4.5)
        assertContrast(CompanionColors.dark.amber, CompanionColors.dark.bg, 4.5)
        assertContrast(CompanionColors.dark.mono, CompanionColors.dark.slab, 4.5)

        assertContrast(CompanionColors.light.text, CompanionColors.light.bg, 4.5)
        assertContrast(CompanionColors.light.textDim, CompanionColors.light.bg, 4.5)
        assertContrast(CompanionColors.light.blue, CompanionColors.light.bg, 4.5)
        assertContrast(CompanionColors.light.green, CompanionColors.light.bg, 4.5)
        assertContrast(CompanionColors.light.red, CompanionColors.light.bg, 4.5)
        assertContrast(CompanionColors.light.amber, CompanionColors.light.bg, 4.5)
        assertContrast(CompanionColors.light.mono, CompanionColors.light.bg, 4.5)
    }

    @Test
    fun gradientLabelsPassAtTheLightestStopsInTheProfile() {
        listOf(CompanionColors.dark, CompanionColors.light).forEach { palette ->
            assertContrast(palette.white, palette.gradPrimaryStart, 4.5)
            assertContrast(palette.white, palette.gradPrimaryEnd, 4.5)
            assertContrast(CompanionColors.dark.bg, palette.gradGreenStart, 4.5)
            assertContrast(CompanionColors.dark.bg, palette.gradGreenEnd, 4.5)
        }
    }

    @Test
    fun reducedMotionDisablesEveryRequestedAnimationDuration() {
        listOf(
            CompanionMotion.PRESS_MILLIS,
            CompanionMotion.STATE_CHANGE_MILLIS,
            CompanionMotion.FIRST_SECTION_REVEAL_MILLIS,
            CompanionMotion.DISCOVERY_SWEEP_MILLIS,
            CompanionMotion.CODE_REVEAL_MILLIS,
            CompanionMotion.WAKE_PACKET_MILLIS,
        ).forEach { duration ->
            assertEquals(0, CompanionMotion.duration(duration, reduceMotion = true))
            assertEquals(duration, CompanionMotion.duration(duration, reduceMotion = false))
        }
    }

    private fun assertContrast(foreground: Long, background: Long, minimum: Double) {
        assertTrue(
            "${contrast(foreground, background)}:1 is below ${minimum}:1",
            contrast(foreground, background) >= minimum,
        )
    }

    private fun contrast(first: Long, second: Long): Double {
        val luminance = maxOf(relativeLuminance(first), relativeLuminance(second))
        val dark = minOf(relativeLuminance(first), relativeLuminance(second))
        return (luminance + 0.05) / (dark + 0.05)
    }

    private fun relativeLuminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val srgb = ((color shr shift) and 0xFF).toDouble() / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
