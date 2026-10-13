package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.color
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.distance
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.farthestFrom
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.pixels
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.PowerConfirmation
import io.github.tuthan.steamoscompanion.core.client.PowerVerb
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.wake.WakeAttemptState
import io.github.tuthan.steamoscompanion.core.wake.WakeSendFailure
import io.github.tuthan.steamoscompanion.core.wake.WakeSendResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress

/** Phase 08: the power confirmation's buttons, the capability wording on disabled controls, and the Wake packet link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h900dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Phase08ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val dark = CompanionColors.dark

    @After fun restoreAnimations() = PixelSupport.setReduceMotion(false)

    private val host = ActiveHost("host-fixture0001xxxxxxxxxxxxx", "client-android", "deck.local:18443",
        "sha256:" + "ab".repeat(32), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        listOf("status.read", "power.control", "display.control"),
        StoredWakeTarget(true, "e45f01aabbcc", "eth0", "192.0.2.9", null), 1_780_000_000_000)

    private fun context(capabilities: Map<String, WireValue<CapabilityState>>) = ActionContext(
        hasActiveHost = true, grantedScopes = setOf("status.read", "power.control", "display.control"),
        statusFreshness = StatusFreshness.FRESH, capabilities = capabilities, wakeTargetConfigured = true)

    private fun available() = mapOf(
        "suspend" to WireValue.Known(CapabilityState.AVAILABLE),
        "restart" to WireValue.Known(CapabilityState.AVAILABLE),
        "shutdown" to WireValue.Known(CapabilityState.AVAILABLE),
    )

    private fun showPower(context: ActionContext, onConfirm: (PowerConfirmation) -> Unit = {}) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PowerScreen(host, "Living-room Deck", context, state = null, status = StatusPollState(),
                        nowMonotonicNanos = 0, busy = false, feedback = null, onConfirm = onConfirm, onCheckOperation = {})
                }
            }
        }
    }

    // ---- "{Action} isn't available on this device" -----------------------------------------------------------------

    private fun hasDescriptionContaining(text: String) = SemanticsMatcher("description contains \"$text\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true
    }

    @Test fun anUnavailableOrUnverifiedCapabilityNamesTheActionThatIsUnavailable() {
        showPower(context(mapOf(
            "suspend" to WireValue.Known(CapabilityState.UNSUPPORTED),
            "restart" to WireValue.Known(CapabilityState.UNAVAILABLE),
            // Shut down has no reading at all: unverified is worded the same way.
        )))
        for (action in listOf("Suspend", "Restart", "Shut down")) {
            compose.onNode(hasDescriptionContaining("Unavailable: $action isn't available on this device")).assertExists()
        }
    }

    @Test fun anAvailableCapabilityShowsNoUnavailabilityReason() {
        showPower(context(available()))
        compose.onNode(hasDescriptionContaining("isn't available on this device")).assertDoesNotExist()
    }

    // ---- the confirmation's buttons --------------------------------------------------------------------------------

    private fun confirmationButton(label: String) =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))

    private fun open(verb: String, confirmLabel: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasText(verb) and hasDescriptionContaining(verb)).performScrollTo().performClick()
        compose.waitForIdle()
        return confirmationButton(confirmLabel).also { it.assertExists() }
    }

    @Test fun shutDownConfirmsWithTheDangerRecipeBesideCancel() {
        showPower(context(available()))
        val confirm = open("Shut down", "Shut down device")
        val map = confirm.pixels()
        // Danger: the panel with a 6 % red tint, red 40 % border, red label.
        val edge = map[10, map.height / 2]
        val panel = color(dark.panel)
        assertTrue("the fill stays close to the dialog panel: ${distance(edge, panel)}", distance(edge, panel) < 24)
        val label = farthestFrom(map, edge, 24, 8, map.width - 24, map.height - 8)
        assertTrue("the label is the red token: $label", distance(label, color(dark.red)) <= 14)
        assertBesideCancel(confirm)
    }

    @Test fun restartConfirmsWithThePrimaryGradientAndAWhiteLabel() {
        val calls = mutableListOf<PowerConfirmation>()
        showPower(context(available()), calls::add)
        val confirm = open("Restart", "Restart device")
        assertPrimaryGradient(confirm)
        assertBesideCancel(confirm)
        confirm.performClick()
        compose.waitForIdle()
        assertEquals(PowerVerb.RESTART, calls.single().verb)
    }

    @Test fun suspendConfirmsWithThePrimaryGradientAndSendsOnlyAfterTheOwnerConfirms() {
        val calls = mutableListOf<PowerConfirmation>()
        showPower(context(available()), calls::add)
        val confirm = open("Suspend", "Suspend device")
        assertPrimaryGradient(confirm)
        assertBesideCancel(confirm)
        assertTrue("nothing is sent by opening the dialog", calls.isEmpty())
        confirm.performClick()
        compose.waitForIdle()
        assertEquals(PowerVerb.SUSPEND, calls.single().verb)
    }

    private fun assertPrimaryGradient(button: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val map = button.pixels()
        val left = map[10, map.height / 2]
        val start = color(dark.gradPrimaryStart)
        val end = color(dark.gradPrimaryEnd)
        // The gradient runs between its two stops, so the fill is blue and far from the dialog panel.
        assertTrue("fill is the primary gradient, not the panel: $left",
            distance(left, color(dark.panel)) > 25 && left.blue > left.red + .15f)
        assertTrue("fill sits between the gradient stops: $left", distance(left, start) <= 40 || distance(left, end) <= 40)
        val label = farthestFrom(map, left, 24, 8, map.width - 24, map.height - 8)
        assertTrue("the label is white: $label", distance(label, Color.White) <= 12)
    }

    private fun assertBesideCancel(confirm: androidx.compose.ui.test.SemanticsNodeInteraction) {
        val confirmBounds = confirm.fetchSemanticsNode().boundsInRoot
        val cancelBounds = compose.onNode(hasText("Cancel") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).fetchSemanticsNode().boundsInRoot
        assertTrue("the confirm button shares Cancel's row", kotlin.math.abs(confirmBounds.center.y - cancelBounds.center.y) <
            confirmBounds.height / 2)
        assertTrue("and sits after it, not filling a row of its own: $confirmBounds vs $cancelBounds",
            confirmBounds.left >= cancelBounds.right - 1 && confirmBounds.width < 600f)
    }

    @Test fun dismissingTheConfirmationSendsNothing() {
        val calls = mutableListOf<PowerConfirmation>()
        showPower(context(available()), calls::add)
        open("Shut down", "Shut down device")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    // ---- Wake packet link ------------------------------------------------------------------------------------------

    private fun showWake(attempt: WakeAttemptState?) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WakeScreen(attempt, configured = true, busy = false, onSend = {}, target = host.wakeTarget)
                }
            }
        }
    }

    private val destination: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 255.toByte()))

    @Test fun aSentPacketDrawsTheLinkWithItsDotAndTheSentenceStillCarriesTheFact() {
        showWake(WakeAttemptState(WakeSendResult.Sent(listOf(destination))))
        compose.onNodeWithText("Wake packet sent", substring = true).assertExists()
        compose.onAllNodesWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun aFailedSendDrawsNoDotBecauseNothingWasSent() {
        showWake(WakeAttemptState(WakeSendResult.Failed(WakeSendFailure.SEND_FAILED, "sendto failed: EPERM")))
        compose.onAllNodesWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun withReduceMotionTheSentenceIsTheOnlyIndicator() {
        PixelSupport.setReduceMotion(true)
        showWake(WakeAttemptState(WakeSendResult.Sent(listOf(destination))))
        compose.onNodeWithText("Wake packet sent", substring = true).assertExists()
        compose.onAllNodesWithTag(CompanionMotionSpec.WAKE_DOT_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun theDeviceResponseLineIsAnnouncedOnce() {
        showWake(WakeAttemptState(WakeSendResult.Sent(listOf(destination)), authenticatedResponse = true))
        compose.onNode(hasText("Waiting for", substring = true).not() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }
}
