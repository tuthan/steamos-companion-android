package io.github.tuthan.steamoscompanion.android.ui

import android.content.Context
import android.provider.Settings
import android.view.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.tuthan.steamoscompanion.android.scanner.QrScanner
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.textColors
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanResult
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 04 pairing screens: the expiry countdown, the step rail, the scanner failure state and the API 36 discovery text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingScreensTest {
    @get:Rule val compose = createComposeRule()

    private val start = 1_780_000_000_000L
    private var wall = start
    private val dark = CompanionColors.dark
    private fun color(value: Long) = Color(value.toInt())

    private fun showPairing(action: PairingAction) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PairingScreen(action, busy = false, onCancel = {}, onCheckAgain = {}, nowMillis = { wall })
                }
            }
        }
    }

    private fun pending(remainingMillis: Long?, variant: PairingVariant = PairingVariant.VERIFICATION) =
        PairingAction.Pending(PairingFixtures.candidate(remainingMillis?.let { start + it }, variant))

    /** Wall time and the composition clock move together, one tick at a time. */
    private fun tick(count: Int = 1) {
        repeat(count) {
            wall += 1_000
            compose.mainClock.advanceTimeBy(1_000)
        }
    }

    private val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    // ---- expiry countdown ------------------------------------------------------------------------------------------

    @Test fun theCodeIsFollowedByAnExpiresInLineThatRoundsDown() {
        showPairing(pending(117_500))
        compose.onNodeWithText("Expires in 1:57").assertExists()
        compose.onAllNodesWithText("Expires in", substring = true).assertCountEquals(1)
    }

    @Test fun theCountdownTurnsAmberInTheLastTenSecondsOnly() {
        showPairing(pending(11_999))
        compose.onNodeWithText("Expires in 0:11").assertExists()
        assertEquals(setOf(color(dark.text)), compose.onNodeWithText("Expires in 0:11").textColors())
        compose.mainClock.autoAdvance = false
        tick()
        // 10.999 s left still reads 0:10 and is not yet inside the last ten seconds.
        compose.onNodeWithText("Expires in 0:10").assertExists()
        assertEquals(setOf(color(dark.text)), compose.onNodeWithText("Expires in 0:10").textColors())
        tick()
        compose.onNodeWithText("Expires in 0:09").assertExists()
        assertEquals(setOf(color(dark.amber)), compose.onNodeWithText("Expires in 0:09").textColors())
    }

    @Test fun theCountdownTicksOncePerSecondThenFreezesAtExpiredInRed() {
        showPairing(pending(2_500))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Expires in 0:02").assertExists()
        tick()
        compose.onNodeWithText("Expires in 0:01").assertExists()
        tick()
        compose.onNodeWithText("Expires in 0:00").assertExists()
        tick()
        compose.onNodeWithText("Expired").assertExists()
        assertEquals(setOf(color(dark.red)), compose.onNodeWithText("Expired").textColors())
        compose.onAllNodesWithText("Expires in", substring = true).assertCountEquals(0)

        tick(5)
        compose.onNodeWithText("Expired").assertExists()
        compose.onAllNodesWithText("Expires in", substring = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher("shows a negative time") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("""-\d""").containsMatchIn(it.text) } == true
        }).assertCountEquals(0)
    }

    @Test fun anExpiredRequestShowsExpiredEvenBeforeTheClockReachesTheDeadline() {
        showPairing(PairingAction.Expired(PairingFixtures.candidate(start + 60_000, phase = CandidatePhase.EXPIRED)))
        compose.onNodeWithText("Expired").assertExists()
        compose.onNodeWithText("This pairing request expired.").assertExists()
        compose.onAllNodesWithText("Expires in", substring = true).assertCountEquals(0)
    }

    @Test fun theCountdownIsQuietForTalkBackButTheOutcomeIsAnnouncedOnce() {
        showPairing(pending(60_000))
        compose.onNode(hasText("Expires in", substring = true) and live).assertDoesNotExist()
        compose.onNode(hasText("Waiting for approval", substring = true) and live).assertExists()
    }

    @Test fun aFinishedRequestShowsNoTimer() {
        val candidate = PairingFixtures.candidate(start + 60_000)
        showPairing(PairingAction.Rejected(candidate, "denied", "The device declined"))
        compose.onAllNodesWithText("Expires in", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Expired").assertCountEquals(0)
    }

    @Test fun aCandidateWithoutADeadlineShowsNoTimer() {
        showPairing(pending(null))
        compose.onAllNodesWithText("Expires in", substring = true).assertCountEquals(0)
    }

    @Test fun thePayloadVariantHasNoCodeSoTheTimerSitsUnderTheStatus() {
        showPairing(pending(90_000, PairingVariant.ADVANCED_PAYLOAD))
        compose.onAllNodesWithText("Expires in 1:30").assertCountEquals(1)
    }

    // ---- step rail -------------------------------------------------------------------------------------------------

    /** The state a rail row announces; a row is the one node that has both the step's label and a state. */
    private fun stepState(label: String): String? = compose.onNode(
        hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun theRailNamesEachStepsStateInWordsAsWellAsGlyphAndColour() {
        showPairing(pending(60_000))
        compose.onNodeWithText("Pairing steps", ignoreCase = true).assertExists()
        assertEquals("Done", stepState("Review the device"))
        assertEquals("Done", stepState("Request pairing"))
        assertEquals("Current step", stepState("Compare the code"))
        assertEquals("Not started", stepState("Approve on the other device"))
    }

    @Test fun thePayloadRailSkipsTheComparison() {
        showPairing(pending(60_000, PairingVariant.ADVANCED_PAYLOAD))
        compose.onAllNodesWithText("Compare the code").assertCountEquals(0)
        assertEquals("Current step", stepState("Approve on the other device"))
    }

    @Test fun reviewingADeviceExplainsHowPairingWorksBeforeAnythingIsSent() {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CandidateDetailsScreen(
                        io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate(
                            io.github.tuthan.steamoscompanion.core.transport.TransportTarget(
                                io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint.parse("192.0.2.5:18443"),
                                io.github.tuthan.steamoscompanion.core.ports.LanPath("w", null, emptyList(), emptyList())),
                            "host-fixture0001", "Steam Deck", "sha256:" + "a".repeat(64)),
                        busy = false, onRequest = {})
                }
            }
        }
        compose.onNodeWithText("How pairing works", ignoreCase = true).performScrollTo().assertExists()
        assertEquals("Current step", stepState("Review the device"))
        assertEquals("Not started", stepState("Request pairing"))
    }

    // ---- scanner ---------------------------------------------------------------------------------------------------

    private class FakeScanner(val failure: (String) -> Unit) : QrScanner {
        var closed = false
        override fun start(preview: Surface) = Unit
        override fun close() { closed = true }
    }

    private fun showScanner(cameraGranted: Boolean, scanners: MutableList<FakeScanner>,
                            failures: MutableList<String>, requests: IntArray = IntArray(1)) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ScannerScreen(cameraGranted, onRequestCamera = { requests[0]++ }, onPayload = {},
                        onFailure = { failures += it },
                        scannerFactory = { _, _, failure -> FakeScanner(failure).also(scanners::add) })
                }
            }
        }
    }

    @Test fun withoutCameraPermissionNoScannerIsStartedAndAllowCameraAsksForIt() {
        val scanners = mutableListOf<FakeScanner>()
        val requests = IntArray(1)
        showScanner(false, scanners, mutableListOf(), requests)
        compose.onNodeWithText("Camera permission required to scan").assertExists()
        compose.onNodeWithText("Allow camera").performScrollTo().performClick()
        assertEquals(1, requests[0])
        assertTrue(scanners.isEmpty())
    }

    @Test fun aCameraFailureIsShownAnnouncedAndNeverSwallowedAndTheOwnerCanTryAgain() {
        val scanners = mutableListOf<FakeScanner>()
        val failures = mutableListOf<String>()
        showScanner(true, scanners, failures)
        compose.waitForIdle()
        assertEquals(1, scanners.size)

        compose.runOnUiThread { scanners.single().failure("Camera is unavailable") }
        compose.waitForIdle()
        assertEquals(listOf("Camera is unavailable"), failures)
        compose.onNode(hasText("Couldn't scan with the camera", substring = true) and live).assertExists()
        compose.onNode(hasText("Camera is unavailable", substring = true)).assertExists()
        assertTrue("the failed scanner is released", scanners.single().closed)
        // The camera is not retried silently, and the paste fallback stays available.
        assertEquals(1, scanners.size)
        compose.onNodeWithText("Or paste a pairing payload", ignoreCase = true).performScrollTo().assertExists()

        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("a fresh scanner after Try again", 2, scanners.size)
        compose.onAllNodesWithText("Couldn't scan with the camera", substring = true).assertCountEquals(0)
    }

    // ---- discovery -------------------------------------------------------------------------------------------------

    private fun scan(permission: LocalNetworkPermissionState, timedOut: Boolean = false) = DiscoveryScanResult(
        generation = 1, candidates = emptyList(), addressesProbed = 254, skippedSubnets = emptyList(), truncated = false,
        cancelled = false, timedOut = timedOut, failedProbes = 254, localNetworkPermissionState = permission)

    private fun showDiscovery(result: DiscoveryScanResult?) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DiscoveryContent(scanning = false, result = result, message = null, onRefresh = {}, onCancel = {},
                        onSelect = {}, onManual = {})
                }
            }
        }
    }

    @Test fun anEmptyScanOnApi36NamesTheCompatibilityRestrictionAndKeepsTheOtherWays() {
        val qualified = scan(LocalNetworkPermissionState.LegacyRestrictionUnknown)
        assertTrue("fixture must be the qualified case", qualified.qualifiedPermissionFailure)
        showDiscovery(qualified)
        compose.onNodeWithText(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE).assertExists()
        compose.onAllNodesWithText("No devices found", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Find again").assertExists()
        compose.onNodeWithText("Enter address").assertExists()
    }

    @Test fun anEmptyScanElsewhereSaysNoDevicesWereFound() {
        val ordinary = scan(LocalNetworkPermissionState.Allowed)
        assertTrue(!ordinary.qualifiedPermissionFailure)
        showDiscovery(ordinary)
        compose.onNode(hasText("No devices found", substring = true)).assertExists()
        compose.onAllNodesWithText(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE).assertCountEquals(0)
    }

    // ---- reduce motion ---------------------------------------------------------------------------------------------

    @Test fun reduceMotionIsReadFromTheSystemAnimationScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertEquals(false, reduceMotionRequested(context.contentResolver))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(true, reduceMotionRequested(context.contentResolver))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, .5f)
        assertEquals(false, reduceMotionRequested(context.contentResolver))
    }
}
