package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.color
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.distance
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.farthestFrom
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.pixels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The four motion moments the profile budgets (R7), looked at in what is drawn rather than in the token numbers: the
 * comparison-code roll, the press brightness, the 380 ms state swap and the wake-packet dot, each with reduce motion on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionMotionTest {
    @get:Rule val compose = createComposeRule()

    private val dark = CompanionColors.dark

    /** The profile's numbers, written out here so a change to the constants in the app cannot also change the test. */
    private val PRESS_LIFT = .15f
    private val SLIDE_DP = 8
    private val DOT_GLOW_DP = 14

    /**
     * Moves the composition clock in 16 ms frames, letting the UI settle after each. A single large step is not the same
     * as the frames it spans: state writes and effects are picked up one frame at a time.
     */
    private fun advance(millis: Int) {
        var left = millis
        while (left > 0) {
            val step = minOf(16, left)
            compose.mainClock.advanceTimeBy(step.toLong())
            compose.waitForIdle()
            left -= step
        }
    }

    @After fun restoreAnimations() = PixelSupport.setReduceMotion(false)

    // ---- comparison code roll --------------------------------------------------------------------------------------

    private fun showCode(digits: String = PairingFixtures.CODE) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().background(color(dark.bg)).padding(16.dp)) { CodeDisplay(digits) }
            }
        }
    }

    private fun codeColor() = farthestFrom(compose.onRoot().pixels(), color(dark.bg))

    @Test fun theDigitsRollFromMonoToBlueOverSixHundredMillisecondsOnce() {
        compose.mainClock.autoAdvance = false
        showCode()
        advance(16)
        assertTrue("starts on mono: ${codeColor()}", distance(codeColor(), color(dark.mono)) <= 12)

        val distancesToBlue = mutableListOf<Int>()
        repeat(6) {
            advance(100)
            distancesToBlue += distance(codeColor(), color(dark.blue))
        }
        // 116 .. 616 ms: the colour keeps moving towards the accent blue and lands exactly on it.
        assertTrue("moves monotonically towards blue: $distancesToBlue", distancesToBlue.zipWithNext().all { (a, b) -> b <= a })
        assertTrue("it was not already blue at 216 ms: $distancesToBlue", distancesToBlue[1] > 0)
        assertEquals("settled on blue at 616 ms: $distancesToBlue", 0, distancesToBlue.last())

        advance(2_000)
        assertEquals("then it stays still", 0, distance(codeColor(), color(dark.blue)))
    }

    @Test fun theSameCodeNeverRollsAgainWhenTheScreenRecomposes() {
        compose.mainClock.autoAdvance = false
        var tick by mutableStateOf(0)
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().background(color(dark.bg)).padding(16.dp)) {
                    CodeDisplay(PairingFixtures.CODE)
                    Text(tick.toString(), Modifier.size(1.dp))
                }
            }
        }
        advance(1_000)
        assertEquals(0, distance(codeColor(), color(dark.blue)))
        compose.runOnIdle { tick++ }
        advance(32)
        assertEquals(0, distance(codeColor(), color(dark.blue)))
    }

    @Test fun aNewCodeRollsAgain() {
        compose.mainClock.autoAdvance = false
        var digits by mutableStateOf(PairingFixtures.CODE)
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Box(Modifier.fillMaxWidth().background(color(dark.bg)).padding(16.dp)) { CodeDisplay(digits) }
            }
        }
        advance(1_000)
        assertEquals(0, distance(codeColor(), color(dark.blue)))
        compose.runOnIdle { digits = "12345678" }
        advance(32)
        assertTrue("a new code starts from mono again", distance(codeColor(), color(dark.blue)) > 20)
    }

    @Test fun withReduceMotionTheDigitsAreBlueFromTheFirstFrame() {
        PixelSupport.setReduceMotion(true)
        compose.mainClock.autoAdvance = false
        showCode()
        advance(32)
        assertEquals(0, distance(codeColor(), color(dark.blue)))
    }

    // ---- press brightness ------------------------------------------------------------------------------------------

    private fun showPressTarget() {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                val source = remember { MutableInteractionSource() }
                val press = rememberPressBrightness(source)
                Box(Modifier.size(100.dp).background(color(dark.panel)).then(press)
                    .clickable(interactionSource = source, indication = null) {}.testTag("target"))
            }
        }
    }

    private fun targetColor() = compose.onNodeWithTag("target").pixels().let { it[it.width / 2, it.height / 2] }

    /** White at 15 % over the panel fill, in sRGB, as the site's `.btn:hover { filter: brightness }` reads. */
    private val pressed: androidx.compose.ui.graphics.Color get() {
        val base = PixelSupport.channels(color(dark.panel))
        val lifted = base.map { (it * (1 - PRESS_LIFT) + 255 * PRESS_LIFT).toInt() }
        return androidx.compose.ui.graphics.Color(lifted[0], lifted[1], lifted[2])
    }

    @Test fun aPressedRowBrightensByFifteenPercentOverOneFiftyMillisecondsAndEasesBack() {
        compose.mainClock.autoAdvance = false
        showPressTarget()
        advance(32)
        assertEquals("at rest the fill is untouched", 0, distance(targetColor(), color(dark.panel)))

        compose.onNodeWithTag("target").performTouchInput { down(center) }
        advance(64)
        val partway = distance(targetColor(), color(dark.panel))
        assertTrue("brightening has started but not finished: $partway", partway > 3 && partway < distance(pressed, color(dark.panel)))

        advance(200)
        assertTrue("fully lifted: ${targetColor()} vs $pressed", distance(targetColor(), pressed) <= 3)

        compose.onNodeWithTag("target").performTouchInput { up() }
        advance(300)
        assertEquals("released: back to rest", 0, distance(targetColor(), color(dark.panel)))
    }

    @Test fun withReduceMotionAPressIsACut() {
        PixelSupport.setReduceMotion(true)
        compose.mainClock.autoAdvance = false
        showPressTarget()
        advance(32)
        compose.onNodeWithTag("target").performTouchInput { down(center) }
        advance(32)
        assertTrue(distance(targetColor(), pressed) <= 3)
    }

    // ---- state swap ------------------------------------------------------------------------------------------------

    private var density = Density(1f)

    private fun showSwap(swapKey: () -> String) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                density = LocalDensity.current
                Column {
                    StateSwap(swapKey()) { key -> Text(key, Modifier.testTag("swap-$key")) }
                }
            }
        }
    }

    private fun yOf(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test fun aChangedStateCrossFadesAndSlidesUpEightDpOver380Milliseconds() {
        compose.mainClock.autoAdvance = false
        var key by mutableStateOf("A")
        showSwap { key }
        advance(32)
        compose.onAllNodesWithTag("swap-B").assertCountEquals(0)
        val rest = yOf("swap-A")

        compose.runOnIdle { key = "B" }
        advance(48)
        compose.onAllNodesWithTag("swap-A").assertCountEquals(1)
        compose.onAllNodesWithTag("swap-B").assertCountEquals(1)
        val slide = with(density) { SLIDE_DP.dp.toPx() }
        val early = yOf("swap-B") - rest
        assertTrue("the new state starts below and rises: $early of $slide", early > slide * .5f && early <= slide + .5f)

        advance(250)
        compose.onAllNodesWithTag("swap-A").assertCountEquals(1)
        assertTrue("still moving at 300 ms", yOf("swap-B") - rest in 0f..early)

        advance(150)
        compose.onAllNodesWithTag("swap-A").assertCountEquals(0)
        assertEquals("settled in place", rest, yOf("swap-B"), .5f)
    }

    @Test fun theSameStateNeverAnimates() {
        compose.mainClock.autoAdvance = false
        var key by mutableStateOf("A")
        showSwap { key }
        advance(32)
        compose.runOnIdle { key = "A" }
        advance(32)
        compose.onAllNodesWithTag("swap-A").assertCountEquals(1)
    }

    @Test fun withReduceMotionAStateChangeIsACut() {
        PixelSupport.setReduceMotion(true)
        compose.mainClock.autoAdvance = false
        var key by mutableStateOf("A")
        showSwap { key }
        advance(32)
        val rest = yOf("swap-A")
        compose.runOnIdle { key = "B" }
        advance(48)
        compose.onAllNodesWithTag("swap-A").assertCountEquals(0)
        assertEquals(rest, yOf("swap-B"), .5f)
    }

    // ---- wake packet -----------------------------------------------------------------------------------------------

    private fun showWake(sendKey: () -> Any) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                density = LocalDensity.current
                Box(Modifier.fillMaxWidth().background(color(dark.bg))) { WakePacketLink(sendKey()) }
            }
        }
    }

    private fun dotLeft() = compose.onNodeWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true)
        .fetchSemanticsNode().positionInRoot.x

    private fun dotRight() = compose.onNodeWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true)
        .fetchSemanticsNode().let { it.positionInRoot.x + it.size.width }

    @Test fun oneDotTravelsFromThePhoneToTheDeviceInOnePointOneFiveSecondsThenRests() {
        compose.mainClock.autoAdvance = false
        var send by mutableStateOf(1)
        showWake { send }
        advance(16)
        compose.onAllNodesWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true).assertCountEquals(1)
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width
        val iconsAndGaps = with(density) { (24 + 10).dp.toPx() }
        val startLeft = dotLeft()
        assertEquals("starts at the phone end of the line", iconsAndGaps, startLeft, 2f)
        assertEquals("the dot and its glow are 14 dp", with(density) { DOT_GLOW_DP.dp.toPx() }, dotRight() - startLeft, 1f)

        val glow = with(density) { DOT_GLOW_DP.dp.toPx() }
        val travel = rootWidth - iconsAndGaps - glow - startLeft
        advance(560)
        val middle = (dotLeft() - startLeft) / travel
        assertTrue("about halfway at 576 ms (linear): $middle", middle in .4f..0.6f)

        advance(700)
        assertEquals("arrives at the device end", rootWidth - iconsAndGaps, dotRight(), 2f)

        val resting = dotLeft()
        advance(5_000)
        assertEquals("rests there; it never loops", resting, dotLeft(), .5f)
    }

    @Test fun eachNewPacketSendsOneMoreDotFromTheStart() {
        compose.mainClock.autoAdvance = false
        var send by mutableStateOf(1)
        showWake { send }
        advance(2_000)
        val arrived = dotLeft()
        compose.runOnIdle { send = 2 }
        advance(48)
        assertTrue("a second packet starts again from the phone end", dotLeft() < arrived - 50f)
        advance(1_500)
        assertEquals(arrived, dotLeft(), .5f)
    }

    @Test fun theDotIsDecorativeAndNothingOfItReachesTalkBack() {
        compose.mainClock.autoAdvance = false
        showWake { 1 }
        advance(16)
        // Decorative: nothing of it reaches TalkBack.
        compose.onAllNodesWithTag(CompanionMotionSpec.WAKE_DOT_TAG).assertCountEquals(0)
    }

    @Test fun withReduceMotionTheWakeLinkDrawsNoDot() {
        PixelSupport.setReduceMotion(true)
        compose.mainClock.autoAdvance = false
        showWake { 1 }
        advance(16)
        compose.onAllNodesWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true).assertCountEquals(0)
    }
}
