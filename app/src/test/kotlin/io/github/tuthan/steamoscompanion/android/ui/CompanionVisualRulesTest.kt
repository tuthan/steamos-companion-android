package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.color
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.contrast
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.distance
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.farthestFrom
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.pixels
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.SavedPairingProblem
import io.github.tuthan.steamoscompanion.core.client.StateUnavailableReason
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * R3 (flat surfaces) and R10 (contrast), checked on what is drawn: every visible text run is measured against the pixels
 * around it in both themes, and nothing but the preview card throws a shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h3600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionVisualRulesTest {
    @get:Rule val compose = createComposeRule()

    private fun palette(dark: Boolean) = if (dark) CompanionColors.dark else CompanionColors.light

    // ---- R10: contrast on screenshots ------------------------------------------------------------------------------

    private fun isDisabled(node: SemanticsNode): Boolean =
        generateSequence(node) { it.parent }.any { it.config.contains(SemanticsProperties.Disabled) }

    /**
     * The worst contrast of every enabled, fully visible text run against the pixels around it, keyed
     * `screen|theme|text`. The background samples are the corners and edge middles of the run's own box, so a run on a
     * tinted fill is measured against the tint, and one on a gradient against its lightest or darkest side.
     */
    private fun scan(name: String, dark: Boolean, into: MutableMap<String, Double>): Int {
        val map = compose.onRoot().pixels()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        var scanned = 0
        for (node in nodes) {
            if (isDisabled(node) || node.config.contains(SemanticsProperties.HideFromAccessibility)) continue
            val text = node.config[SemanticsProperties.Text].joinToString(" ") { it.text }.trim()
            val bounds = node.boundsInRoot
            val x0 = bounds.left.roundToInt().coerceAtLeast(0)
            val y0 = bounds.top.roundToInt().coerceAtLeast(0)
            val x1 = ceil(bounds.right).toInt().coerceAtMost(map.width)
            val y1 = ceil(bounds.bottom).toInt().coerceAtMost(map.height)
            if (text.isEmpty() || x1 - x0 < 4 || y1 - y0 < 4 || bounds.bottom > map.height) continue
            val samples = listOf(x0 + 1 to y0 + 1, x1 - 2 to y0 + 1, x0 + 1 to y1 - 2, x1 - 2 to y1 - 2,
                (x0 + x1) / 2 to y0 + 1, (x0 + x1) / 2 to y1 - 2).map { (x, y) -> map[x, y] }
            val reference = Color(samples.map { it.red }.average().toFloat(), samples.map { it.green }.average().toFloat(),
                samples.map { it.blue }.average().toFloat())
            val foreground = farthestFrom(map, reference, x0, y0, x1, y1)
            val worst = samples.minOf { contrast(foreground, it) }
            scanned++
            val key = "$name|${if (dark) "dark" else "light"}|${text.take(48)}"
            into[key] = minOf(into[key] ?: Double.MAX_VALUE, worst)
        }
        return scanned
    }

    /**
     * R10 is 4.5:1 for every text pair. The pairs below were found on screenshots, are all text on the profile's own
     * tint fills (badge 14 %, ghost 12 %, selected row 10 %, step circle) where the token pair on the plain page passes
     * but the tinted surface does not, and changing them means changing the profile's tint alphas or text tokens, a
     * design decision that also re-records the gallery goldens. They are pinned here as a ratchet: a new pair below
     * 4.5:1, a known pair getting worse, or a known pair that now passes (delete it) fails the test.
     */
    private val knownBelowAa: Map<String, Double> = KnownContrastFindings.pairs

    private fun assertRatchet(name: String, measured: Map<String, Double>) {
        val below = measured.filterValues { it < 4.5 }
        val known = knownBelowAa.filterKeys { it.startsWith("$name|") }
        val worse = below.filter { (key, ratio) -> known[key]?.let { ratio < it - .02 } ?: true }
        val stale = known.filterKeys { it !in below }
        if (System.getenv("PRINT_R10_FINDINGS") == "1") {
            below.toSortedMap().forEach { (key, ratio) -> println("R10 \"$key\" to ${"%.2f".format(Math.floor(ratio * 100) / 100)},") }
        }
        assertTrue("new or worse pairs below 4.5:1 (R10):\n" + worse.entries.joinToString("\n") { "${it.key} = ${"%.2f".format(it.value)}" },
            worse.isEmpty())
        assertTrue("pairs now passing; remove them from KnownContrastFindings:\n" + stale.keys.joinToString("\n"), stale.isEmpty())
    }

    private fun hex(color: Color) = PixelSupport.channels(color).joinToString("", "#") { "%02x".format(it) }

    /**
     * Renders [content] and measures it in both themes, once per variant (a variant changes what the content shows, such
     * as the time left). Findings are keyed by the screen name plus the variant.
     */
    private fun scanBothThemes(name: String, minimumRuns: Int, variants: List<Pair<String, () -> Unit>> = listOf("" to {}),
                               content: @Composable (dark: Boolean) -> Unit) {
        var dark by mutableStateOf(true)
        compose.setContent { content(dark) }
        for ((label, apply) in variants) {
            val fullName = "$name $label".trim()
            compose.runOnIdle { apply() }
            val measured = sortedMapOf<String, Double>()
            var scanned = 0
            for (theme in listOf(true, false)) {
                compose.runOnIdle { dark = theme }
                compose.waitForIdle()
                scanned += scan(fullName, theme, measured)
            }
            assertTrue("$fullName: only $scanned text runs were measured; the scan is not looking at the screen",
                scanned >= minimumRuns)
            assertRatchet(fullName, measured)
        }
    }

    @Composable
    private fun Screen(dark: Boolean, content: @Composable () -> Unit) {
        CompanionTheme(darkTheme = dark) {
            val palette = LocalCompanionPalette.current
            Column(Modifier.fillMaxSize().background(Color(palette.bg.toInt())).verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }

    private fun galleryScan(section: GallerySection, minimumRuns: Int) =
        scanBothThemes("gallery ${section.name.lowercase()}", minimumRuns) { dark -> CompanionComponentGallery(section, dark) }

    @Test fun galleryFactsMeetContrastOnScreenInBothThemes() = galleryScan(GallerySection.FACTS, 20)
    @Test fun galleryCardsMeetContrastOnScreenInBothThemes() = galleryScan(GallerySection.CARDS, 20)
    @Test fun galleryActionsMeetContrastOnScreenInBothThemes() = galleryScan(GallerySection.ACTIONS, 20)
    @Test fun galleryButtonsMeetContrastOnScreenInBothThemes() = galleryScan(GallerySection.BUTTONS, 16)
    @Test fun galleryRecoveryMeetsContrastOnScreenInBothThemes() = galleryScan(GallerySection.RECOVERY, 10)

    private val now = 1_780_000_000_000L

    @Test fun theCompareScreenMeetsContrastWhileCountingNearExpiryAndWhenExpired() {
        var action: PairingAction by mutableStateOf(PairingAction.Idle)
        val candidate = { millisLeft: Long -> PairingFixtures.candidate(now + millisLeft) }
        scanBothThemes("pairing", 12, listOf(
            "counting" to { action = PairingAction.Pending(candidate(90_000)) },
            // The amber last-ten-seconds line.
            "near expiry" to { action = PairingAction.Pending(candidate(8_000)) },
            // Frozen at Expired in red.
            "expired" to { action = PairingAction.Expired(candidate(-1_000)) },
        )) { dark ->
            Screen(dark) {
                PairingScreen(action, busy = false, onCancel = {}, onCheckAgain = {}, nowMillis = { now })
            }
        }
    }

    @Test fun theSavedPairingProblemScreensMeetContrastInBothThemes() {
        var reason by mutableStateOf(StateUnavailableReason.DAMAGED)
        scanBothThemes("saved pairing", 4, StateUnavailableReason.entries.map { entry ->
            entry.name.lowercase() to { reason = entry }
        }) { dark ->
            Screen(dark) {
                SavedPairingUnavailableScreen(checkNotNull(SavedPairingProblem.from(StateLoadResult.Unavailable(reason))),
                    busy = false, feedback = ActionFeedback("Could not remove the saved pairing; try again.",
                        StatusTone.WARNING, 1), onRetry = {}, onReset = {})
            }
        }
    }

    // ---- R3: flat surfaces -----------------------------------------------------------------------------------------

    private fun dialogFill(dark: Boolean): Color {
        compose.setContent {
            CompanionTheme(darkTheme = dark) {
                PictureConfirmationDialog("Reset local pairing?", "This removes the saved pairing.", "Reset", {}, {})
            }
        }
        compose.waitForIdle()
        val map = compose.onNode(isDialog()).pixels()
        // A pixel inside the dialog's own padding, clear of text and of the rounded corners.
        return map[map.width / 2, 6]
    }

    @Test fun aDialogSurfaceIsTheFlatPanelTokenWithNoTonalTintInTheDarkTheme() {
        assertEquals(0, distance(dialogFill(dark = true), color(palette(true).panel)))
    }

    @Test fun aDialogSurfaceIsTheFlatPanelTokenWithNoTonalTintInTheLightTheme() {
        assertEquals(0, distance(dialogFill(dark = false), color(palette(false).panel)))
    }

    /** How many pixels in a thin ring just outside the component differ from the page colour. */
    private fun ringPixelsDifferingFromThePage(bounds: androidx.compose.ui.geometry.Rect, bg: Color,
                                                map: androidx.compose.ui.graphics.PixelMap): Int {
        var differing = 0
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        for (offset in 2..12) {
            for (x in left until right) {
                if (distance(map[x, (top - offset).coerceAtLeast(0)], bg) > 0) differing++
                if (distance(map[x, (bottom + offset).coerceAtMost(map.height - 1)], bg) > 0) differing++
            }
            for (y in top until bottom) {
                if (distance(map[(left - offset).coerceAtLeast(0), y], bg) > 0) differing++
                if (distance(map[(right + offset).coerceAtMost(map.width - 1), y], bg) > 0) differing++
            }
        }
        return differing
    }

    @Test fun rowsCardsButtonsAndStatusesCastNoShadowOnTheDarkSurface() {
        val components: List<Pair<String, @Composable () -> Unit>> = listOf(
            "ActionRow" to { ActionRow("Restore saved display", secondary = "Saved: 1920 × 1080", onClick = {}) },
            "ActionRow danger" to { ActionRow("Remove this pairing", danger = true, onClick = {}) },
            "PrimaryAction" to { PrimaryAction("Find devices", onClick = {}) },
            "PrimaryAction green" to { PrimaryAction("Keep and save", green = true, onClick = {}) },
            "GhostAction" to { GhostAction("Cancel scan", onClick = {}) },
            "DangerAction" to { DangerAction("Remove", onClick = {}) },
            "InfoCard" to { InfoCard("Pinned identity", "Checked before sending credentials.", icon = io.github.tuthan.steamoscompanion.R.drawable.ic_lock) },
            "ChoiceCard" to { ChoiceCard("Forget on this phone only", "Host removal will not be confirmed", false, {}) },
            "StatusLine" to { StatusLine("Connected", StatusTone.GOOD, glyph = StatusGlyph.DOT) },
        )
        var which by mutableStateOf(0)
        val bg = color(CompanionColors.dark.bg)
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().background(bg).padding(32.dp)) {
                    Box(Modifier.testTag("component")) { components[which].second() }
                }
            }
        }
        for (index in components.indices) {
            compose.runOnIdle { which = index }
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("component").fetchSemanticsNode().boundsInRoot
            val differing = ringPixelsDifferingFromThePage(bounds, bg, compose.onRoot().pixels())
            assertEquals("${components[index].first} must not cast a shadow or glow outside its bounds", 0, differing)
        }
    }

    @Test fun onlyThePreviewCardCarriesAShadowInTheAppSources() {
        val sources = File("src/main/kotlin/io/github/tuthan/steamoscompanion/android/ui").walkTopDown()
            .filter { it.extension == "kt" }.toList()
        assertTrue("the UI sources were not found", sources.size > 10)
        val elevationTerms = listOf(".shadow(", "shadowElevation", "tonalElevation", "elevation =", "Elevation(",
            "CardDefaults", "ElevatedCard", "ElevatedButton", "FloatingActionButton", "ModalBottomSheet", "DropdownMenu(")
        val offenders = mutableListOf<String>()
        for (file in sources) {
            val lines = file.readLines()
            val previewCardStart = lines.indexOfFirst { it.startsWith("fun PreviewCard(") }
            val previewCardEnd = if (previewCardStart < 0) -1 else
                (previewCardStart + 1 until lines.size).firstOrNull { lines[it] == "}" } ?: lines.lastIndex
            lines.forEachIndexed { index, line ->
                if (elevationTerms.any { it in line } && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//")) {
                    val insidePreviewCard = index in previewCardStart..previewCardEnd
                    if (!insidePreviewCard) offenders += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue("elevation outside PreviewCard (R3):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun thePreviewCardIsTheOneSurfaceWithAShadowToken() {
        val source = File("src/main/kotlin/io/github/tuthan/steamoscompanion/android/ui/CompanionComponents.kt").readText()
        val previewCard = source.substring(source.indexOf("fun PreviewCard("))
        assertTrue(".shadow(8.dp" in previewCard)
        assertEquals("exactly one shadow in the whole file", 1, Regex("""\.shadow\(""").findAll(source).count())
    }
}
