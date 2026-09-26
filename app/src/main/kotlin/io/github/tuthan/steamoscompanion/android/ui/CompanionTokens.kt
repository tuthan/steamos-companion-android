package io.github.tuthan.steamoscompanion.android.ui

/** Color tokens kept outside screen code so Compose components never embed literal colors. */
data class CompanionPalette(
    val bg: Long,
    val bgDeep: Long,
    val panel: Long,
    val panel2: Long,
    val field: Long,
    val slab: Long,
    val line: Long,
    val lineStrong: Long,
    val text: Long,
    val textSoft: Long,
    val textDim: Long,
    val white: Long,
    val blue: Long,
    val blueBright: Long,
    val blueDeep: Long,
    val mono: Long,
    val green: Long,
    val red: Long,
    val amber: Long,
    val navy: Long,
    val gradPrimaryStart: Long,
    val gradPrimaryEnd: Long,
    val gradGreenStart: Long,
    val gradGreenEnd: Long,
    val gradProgressStart: Long,
    val gradProgressEnd: Long,
)

/** Fixed brand palettes. Dynamic Material color is intentionally not part of the app. */
object CompanionColors {
    val dark = CompanionPalette(
        bg = 0xFF1B2838,
        bgDeep = 0xFF171A21,
        panel = 0xFF16202D,
        panel2 = 0xFF1C2B3B,
        field = 0xFF1B2531,
        slab = 0xFF0F1822,
        line = 0x14FFFFFF,
        lineStrong = 0x26FFFFFF,
        text = 0xFFC6D4DF,
        textSoft = 0xFFDFE6EC,
        textDim = 0xFF8F98A0,
        white = 0xFFFFFFFF,
        blue = 0xFF66C0F4,
        blueBright = 0xFF1A9FFF,
        blueDeep = 0xFF417A9B,
        mono = 0xFF9FD3F5,
        green = 0xFFA1CD44,
        red = 0xFFE57373,
        amber = 0xFFFFB321,
        navy = 0xFF0D2C5E,
        gradPrimaryStart = 0xFF3579AB,
        gradPrimaryEnd = 0xFF254F6E,
        gradGreenStart = 0xFFA1CD44,
        gradGreenEnd = 0xFF7FA82A,
        gradProgressStart = 0xFF67C1F5,
        gradProgressEnd = 0xFF1A9FFF,
    )

    val light = CompanionPalette(
        bg = 0xFFF2F5F8,
        bgDeep = 0xFF1B2838,
        panel = 0xFFFFFFFF,
        panel2 = 0xFFF7F9FB,
        field = 0xFFE9EEF3,
        slab = 0xFFE9EEF3,
        line = 0x1A000000,
        lineStrong = 0x2E000000,
        text = 0xFF1B2838,
        textSoft = 0xFF253746,
        textDim = 0xFF5C6873,
        white = 0xFFFFFFFF,
        blue = 0xFF2A6F97,
        blueBright = 0xFF1A9FFF,
        blueDeep = 0xFF417A9B,
        mono = 0xFF1F5F85,
        green = 0xFF4F7A10,
        red = 0xFFB3261E,
        amber = 0xFF8A5A00,
        navy = 0xFF0D2C5E,
        gradPrimaryStart = 0xFF3579AB,
        gradPrimaryEnd = 0xFF254F6E,
        gradGreenStart = 0xFFA1CD44,
        gradGreenEnd = 0xFF7FA82A,
        gradProgressStart = 0xFF67C1F5,
        gradProgressEnd = 0xFF1A9FFF,
    )
}

/** Typography values use sp, and all roles except decorative labels follow system scaling. */
object CompanionType {
    const val WORDMARK_SP = 28f
    const val PAGE_TITLE_SP = 24f
    const val ROW_TITLE_SP = 18f
    const val BODY_SP = 16f
    const val SECONDARY_SP = 14f
    const val KICKER_SP = 12f
    const val BADGE_SP = 11f
    const val COMPARISON_CODE_SP = 44f
    const val MONO_VALUE_SP = 13f
    const val MODE_ROW_SP = 16f
    const val DECORATIVE_SCALE_CAP = 1.3f
}

object CompanionShapes {
    const val ACTION_ROW_DP = 6f
    const val CARD_DP = 12f
    const val DEVICE_FRAME_DP = 14f
    const val PILL_DP = 999f
    const val BACK_BUTTON_DP = 8f
    const val ICON_WELL_DP = 9f
}

object CompanionSpacing {
    const val GRID_DP = 4f
    const val SCREEN_GUTTER_DP = 16f
    const val ROW_GROUP_DP = 12f
    const val SECTION_GROUP_DP = 24f
    const val CARD_PADDING_DP = 22f
    const val KICKER_GAP_DP = 8f
    const val MAX_CONTENT_WIDTH_DP = 600f
    const val MIN_TOUCH_TARGET_DP = 48f
    const val DENSE_DIALOG_TOUCH_TARGET_DP = 44f
}

/** Finite motion budgets; reduce-motion maps every animation to an immediate state change. */
object CompanionMotion {
    const val PRESS_MILLIS = 150
    const val STATE_CHANGE_MILLIS = 380
    const val FIRST_SECTION_REVEAL_MILLIS = 700
    const val DISCOVERY_SWEEP_MILLIS = 2_600
    const val CODE_REVEAL_MILLIS = 600
    const val WAKE_PACKET_MILLIS = 1_150
    const val PREVIEW_COUNTDOWN_TICK_MILLIS = 1_000
    const val PREVIEW_AMBER_LAST_SECONDS = 5
    const val PAIRING_AMBER_LAST_SECONDS = 10

    fun duration(requestedMillis: Int, reduceMotion: Boolean): Int =
        if (reduceMotion) 0 else requestedMillis.coerceAtLeast(0)
}
