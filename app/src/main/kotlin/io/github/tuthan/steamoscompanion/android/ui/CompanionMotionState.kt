package io.github.tuthan.steamoscompanion.android.ui

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** True when the owner turned system animations off (animator duration scale 0); every motion then becomes a cut. */
internal fun reduceMotionRequested(resolver: ContentResolver): Boolean =
    runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        .getOrDefault(false)

/** Read on every composition, so a settings change is honoured the next time the screen recomposes. */
@Composable
internal fun rememberReduceMotion(): Boolean = reduceMotionRequested(LocalContext.current.contentResolver)
