package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.LinkAdvisory
import io.github.tuthan.steamoscompanion.core.client.LinkAdvisoryNotice
import io.github.tuthan.steamoscompanion.core.client.LinkFactsReading
import io.github.tuthan.steamoscompanion.core.client.LinkFactsTopology
import io.github.tuthan.steamoscompanion.core.client.LinkLine
import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real link-facts composables, and the real Display screen around them, under Robolectric and
 * reads them the way TalkBack does: merged nodes and state. The facts come from the vendored host fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkFactsViewsTest {
    @get:Rule val compose = createComposeRule()

    private val advisoryText = "Heads up · 3440 × 1440 isn't in the display link's mode list. It may still work, " +
        "or the display may show no signal. The preview reverts on its own unless you keep it."

    // --- the two views on their own --------------------------------------------------------------------------

    @Test fun theAdvisoryReadsAsOneUnitSaysItMayStillWorkAndSpeaksItsState() {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column { LinkAdvisoryRow(LinkAdvisoryNotice(connector(), "3440x1440")) }
            }
        }
        compose.onNodeWithText(advisoryText).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Advisory"))
        compose.onNodeWithText("may still work", substring = true).assertExists()
        // It is a warning about a list, never a verdict on the mode.
        listOf("invalid", "unsupported", "not supported", "can't", "cannot", "error").forEach {
            compose.onAllNodesWithText(it, substring = true, ignoreCase = true).assertCountEquals(0)
        }
    }

    @Test fun theAdvisoryNamesTheResolutionItWasGiven() {
        compose.setContent {
            CompanionTheme(darkTheme = true) { Column { LinkAdvisoryRow(LinkAdvisoryNotice(connector(), "7680x4320")) } }
        }
        compose.onNodeWithText("Heads up · 7680 × 4320 isn't in the display link's mode list.", substring = true).assertExists()
    }

    @Test fun theLinkLineIsOneMutedLineWithOnlyTheFactsTheHostReported() {
        val lines = listOf(
            LinkLine("HDMI-A-1", "card0", "LG TV SSCR2", "GSM", 49780) to "Link: HDMI-A-1 · card0 · LG TV SSCR2 · GSM model 49780",
            LinkLine("HDMI-A-2", "card0", null, null, null) to "Link: HDMI-A-2 · card0",
            LinkLine("DP-1", "card1", null, "DEL", 41340) to "Link: DP-1 · card1 · DEL model 41340",
            LinkLine("DP-1", "card0", "DELL U2723QE", null, null) to "Link: DP-1 · card0 · DELL U2723QE",
        )
        compose.setContent { CompanionTheme(darkTheme = true) { Column { lines.forEach { LinkFactsLine(it.first) } } } }
        lines.forEach { (_, text) -> compose.onNodeWithText(text).assertExists() }
        // Facts only: nothing about modes, refresh rates, or whether anything works.
        compose.onAllNodesWithText("Hz", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("work", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    // --- inside the Display screen ---------------------------------------------------------------------------

    private class Harness {
        var status by mutableStateOf(StatusPollState())
        var reading by mutableStateOf<LinkFactsReading?>(null)
        val wanted = mutableListOf<Pair<ActiveHost, String>>()
    }

    private fun show(harness: Harness, host: ActiveHost) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    DisplayScreen(host, null, harness.status, 2L, false, null, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {},
                        linkFacts = harness.reading, onLinkFactsWanted = { h, key -> harness.wanted += h to key })
                }
            }
        }
    }

    /** Output "1" is the vendored display-outputs fixture (3440x1440 current, 3840x2160 and 1920x1080) plus a 3440x1440 @ 100 Hz mode. */
    private fun statusWithOutputs(host: ActiveHost): StatusPollState {
        val outputs = outputsFixture()
        val output = outputs.outputs.single()
        val extra = DisplayModeSummary("9", 3440, 1440, "100")
        val read = PairedStatusRead(statusFixture(), outputs.copy(outputs = listOf(output.copy(modes = output.modes + extra))), null)
        return StatusPollState(lastRead = read, lastSuccessMonotonicNanos = 1, source = StatusObservationSource.from(host))
    }

    private fun topology(status: StatusPollState) = checkNotNull(LinkFactsTopology.key(status.lastRead?.outputs))

    @Test fun theScreenAsksForTheFactsOnceAndNotOnEveryStatusPoll() {
        val host = host()
        val harness = Harness().apply { status = statusWithOutputs(host) }
        show(harness, host)
        compose.waitForIdle()
        assertEquals(listOf(host to topology(harness.status)), harness.wanted)
        // Status polls keep arriving with fresh, equal observations: no further request.
        repeat(5) { poll ->
            harness.status = statusWithOutputs(host).copy(lastSuccessMonotonicNanos = 2L + poll)
            compose.waitForIdle()
        }
        assertEquals(1, harness.wanted.size)
        // Another topology (an output re-enumerated) is one more request, not one per poll.
        val read = checkNotNull(harness.status.lastRead)
        val outputs = checkNotNull(read.outputs)
        harness.status = harness.status.copy(lastRead = read.copy(outputs = outputs.copy(generation = 99)))
        compose.waitForIdle()
        repeat(3) { compose.waitForIdle() }
        assertEquals(2, harness.wanted.size)
        assertTrue(harness.wanted[1].second != harness.wanted[0].second)
    }

    @Test fun noFactsMeansNoLineAndNoAdvisoryEvenForAnUnlistedMode() {
        val host = host()
        val harness = Harness().apply { status = statusWithOutputs(host) }
        show(harness, host)
        choose("3440 × 1440 · 100 Hz")
        compose.onNodeWithText("Preview 3440 × 1440 · 100 Hz").assertExists()
        compose.onAllNodesWithText("Heads up", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Link:", substring = true).assertCountEquals(0)
    }

    @Test fun anUnlistedModeShowsTheLinkLineAndTheAdvisoryDirectlyAbovePreview() {
        val host = host()
        val harness = Harness().apply { status = statusWithOutputs(host) }
        show(harness, host)
        harness.reading = LinkFactsReading(StatusObservationSource.from(host), topology(harness.status), facts("available"))
        compose.waitForIdle()

        // The line sits with the output info; the current mode is selected, so nothing is chosen to warn about.
        compose.onNodeWithText("Link: HDMI-A-1 · card0 · LG TV SSCR2 · GSM model 49780").assertExists()
        compose.onAllNodesWithText("Heads up", substring = true).assertCountEquals(0)

        choose("3440 × 1440 · 100 Hz")
        compose.onNodeWithText(advisoryText).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Advisory"))
        val advisory = compose.onNodeWithText(advisoryText).fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithText("Preview 3440 × 1440 · 100 Hz").fetchSemanticsNode().boundsInRoot
        val chosenRow = compose.onNodeWithText("3440 × 1440 · 100 Hz").fetchSemanticsNode().boundsInRoot
        assertTrue("advisory must sit above Preview", advisory.bottom <= preview.top)
        assertTrue("advisory must sit below the chosen mode row", advisory.top >= chosenRow.bottom)
        // Directly above: nothing but the advisory between the mode row and the button.
        assertTrue("gap ${preview.top - advisory.bottom}", preview.top - advisory.bottom < 24 * 4f)

        // The host's list does contain these two, so choosing them stays quiet; the link line stays.
        choose("3840 × 2160 · 60 Hz")
        compose.onAllNodesWithText("Heads up", substring = true).assertCountEquals(0)
        choose("1920 × 1080 · 60 Hz")
        compose.onAllNodesWithText("Heads up", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Link: HDMI-A-1 · card0 · LG TV SSCR2 · GSM model 49780").assertExists()
    }

    @Test fun aReadingForAnotherTopologyOrPairingIsNeverShown() {
        val host = host()
        val harness = Harness().apply { status = statusWithOutputs(host) }
        show(harness, host)
        choose("3440 × 1440 · 100 Hz")
        val source = StatusObservationSource.from(host)
        val stale = listOf(
            LinkFactsReading(source, topology(harness.status) + "|changed", facts("available")),
            LinkFactsReading(StatusObservationSource.from(host.copy(bearerToken = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx")),
                topology(harness.status), facts("available")),
            LinkFactsReading(source, topology(harness.status), null),
        )
        stale.forEach { reading ->
            harness.reading = reading
            compose.waitForIdle()
            compose.onAllNodesWithText("Heads up", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("Link:", substring = true).assertCountEquals(0)
        }
        // Control: the matching reading does show it.
        harness.reading = LinkFactsReading(source, topology(harness.status), facts("available"))
        compose.waitForIdle()
        compose.onNodeWithText(advisoryText).assertExists()
    }

    @Test fun anOutputTheHostDidNotJoinGetsNeitherTheLineNorTheAdvisory() {
        val host = host()
        val harness = Harness().apply { status = statusWithOutputs(host) }
        show(harness, host)
        choose("3440 × 1440 · 100 Hz")
        // Same output id "1", but the host joined nothing (bridge never reported / ambiguous connector).
        harness.reading = LinkFactsReading(StatusObservationSource.from(host), topology(harness.status), facts("bridge-unavailable"))
        compose.waitForIdle()
        compose.onAllNodesWithText("Heads up", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Link:", substring = true).assertCountEquals(0)
        assertTrue(LinkAdvisory.connectorFor(facts("bridge-unavailable"), "1") == null)
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun choose(label: String) {
        compose.waitForIdle()
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun connector() = checkNotNull(facts("available").connectors.first { it.connector == "HDMI-A-1" })

    private fun host() = ActiveHost(
        hostId = "host-fixture0001xxxxxxxxxxxxx", clientId = "client-android", endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        grantedScopes = listOf("status.read", "power.control", "display.control"),
        wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"), pairedAtMillis = 1_780_000_000_000,
    )

    private fun fixtureBody(path: String) =
        BoundedJson.parseObject(File("../protocol/fixtures/contract/$path.json").also {
            check(it.isFile) { "vendored fixture not found at ${it.absolutePath}" }
        }.readBytes())["response"].asObject("response")["body"].asObject("body")

    private fun decode(route: ApiRoute, path: String): VersionedPayload {
        val reply = ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(fixtureBody(path))))
        return (reply as ApiReply.Versioned).payload
    }

    private fun facts(name: String): LinkFacts = checkNotNull((decode(ApiRoute.DRM, "drm/$name") as VersionedPayload.Drm).facts)

    private fun outputsFixture() = decode(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/available") as VersionedPayload.DisplayOutputs

    private fun statusFixture() = decode(ApiRoute.STATUS, "status/sunshine-running") as VersionedPayload.Status
}
