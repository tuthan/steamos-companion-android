package io.github.tuthan.steamoscompanion.android.ui

import com.github.takahirom.roborazzi.RoborazziComposeOptions
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.fontScale
import com.github.takahirom.roborazzi.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Captures the production catalogue components for dark/light and 100/200 percent type. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalRoborazziApi::class)
class CompanionComponentGalleryCaptureTest {
    @Test fun captureGallery() {
        val recording = System.getenv("PHASE06_RECORD") == "1"
        val directory = File(if (recording) "build/outputs/roborazzi/phase06/gallery"
            else "src/test/screenshots/golden/phase06/gallery")
        if (recording) directory.mkdirs()
        val options = RoborazziOptions(taskType = if (recording) RoborazziTaskType.Record
            else RoborazziTaskType.Verify)
        for (section in GallerySection.entries) {
            for (dark in listOf(true, false)) {
                for (scale in listOf(1f, 2f)) {
                    val theme = if (dark) "dark" else "light"
                    val suffix = if (scale == 1f) "100" else "200"
                    val output = File(directory, "${section.name.lowercase()}-$theme-$suffix.png")
                    captureRoboImage(
                        filePath = output.absolutePath,
                        roborazziOptions = options,
                        roborazziComposeOptions = RoborazziComposeOptions {
                            size(360, 640)
                            fontScale(scale)
                        },
                    ) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                            check(LocalDensity.current.fontScale == scale)
                            CompanionComponentGallery(section, dark)
                        }
                    }
                }
            }
        }
    }
}
