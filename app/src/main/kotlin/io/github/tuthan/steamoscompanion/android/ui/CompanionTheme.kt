package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.tuthan.steamoscompanion.R

private val figtree = FontFamily(Font(R.font.figtree_wght))
private val jetbrainsMono = FontFamily(Font(R.font.jetbrains_mono_wght))

val LocalCompanionPalette = compositionLocalOf { CompanionColors.dark }

val CompanionTypography = Typography(
    headlineSmall = TextStyle(fontFamily = figtree, fontWeight = FontWeight.Medium,
        fontSize = CompanionType.PAGE_TITLE_SP.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = figtree, fontWeight = FontWeight.SemiBold,
        fontSize = CompanionType.ROW_TITLE_SP.sp),
    bodyLarge = TextStyle(fontFamily = figtree, fontWeight = FontWeight.Normal,
        fontSize = CompanionType.BODY_SP.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = figtree, fontWeight = FontWeight.Normal,
        fontSize = CompanionType.SECONDARY_SP.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontFamily = figtree, fontWeight = FontWeight.Bold,
        fontSize = CompanionType.KICKER_SP.sp, letterSpacing = 1.68.sp),
    labelSmall = TextStyle(fontFamily = figtree, fontWeight = FontWeight.Bold,
        fontSize = CompanionType.BADGE_SP.sp, letterSpacing = 1.32.sp),
)

val CompanionMonoStyle = TextStyle(
    fontFamily = jetbrainsMono,
    fontWeight = FontWeight.Normal,
    fontSize = CompanionType.MONO_VALUE_SP.sp,
    fontFeatureSettings = "tnum",
)

val CompanionWordmarkStyle = TextStyle(
    fontFamily = figtree,
    fontWeight = FontWeight.Bold,
    fontSize = CompanionType.WORDMARK_SP.sp,
    letterSpacing = 2.24.sp,
    lineHeight = 34.sp,
)

val CompanionCodeStyle = TextStyle(
    fontFamily = jetbrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = CompanionType.COMPARISON_CODE_SP.sp,
    letterSpacing = 1.76.sp,
    fontFeatureSettings = "tnum",
)

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) CompanionColors.dark else CompanionColors.light
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(palette.blue.toInt()),
            onPrimary = Color(palette.bg.toInt()),
            background = Color(palette.bg.toInt()),
            onBackground = Color(palette.text.toInt()),
            surface = Color(palette.panel.toInt()),
            onSurface = Color(palette.text.toInt()),
            error = Color(palette.red.toInt()),
        )
    } else {
        lightColorScheme(
            primary = Color(palette.blue.toInt()),
            onPrimary = Color(palette.white.toInt()),
            background = Color(palette.bg.toInt()),
            onBackground = Color(palette.text.toInt()),
            surface = Color(palette.panel.toInt()),
            onSurface = Color(palette.text.toInt()),
            error = Color(palette.red.toInt()),
        )
    }
    CompositionLocalProvider(LocalCompanionPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = CompanionTypography, content = content)
    }
}
