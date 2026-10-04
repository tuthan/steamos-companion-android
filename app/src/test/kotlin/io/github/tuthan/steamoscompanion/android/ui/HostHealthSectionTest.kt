package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import io.github.tuthan.steamoscompanion.core.client.HostHealthDetail
import io.github.tuthan.steamoscompanion.core.client.HostHealthItem
import io.github.tuthan.steamoscompanion.core.client.HostHealthPresentation
import io.github.tuthan.steamoscompanion.core.client.HostHealthRow
import io.github.tuthan.steamoscompanion.core.client.HostHealthState
import io.github.tuthan.steamoscompanion.core.client.HostHealthUnavailableReason
import io.github.tuthan.steamoscompanion.core.client.HostHealthView
import io.github.tuthan.steamoscompanion.core.client.HostProtocolNudge
import io.github.tuthan.steamoscompanion.core.client.HostProtocolNudgeKind
import io.github.tuthan.steamoscompanion.core.protocol.HostBridgeCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostCertificateCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostListenerCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostProviderCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostSelfCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostTemperatureCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostVersionsCheck
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.TemperatureSource
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the real composable under Robolectric and reads it the way TalkBack does: merged nodes and state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostHealthSectionTest {
    @get:Rule val compose = createComposeRule()

    private fun show(view: HostHealthView) {
        compose.setContent { CompanionTheme(darkTheme = true) { Column { HostHealthSection(view) } } }
    }

    private fun HostHealthState.stateWord() = when (this) {
        HostHealthState.OK -> "OK"
        HostHealthState.WARNING -> "Needs attention"
        HostHealthState.OFF -> "Off"
        HostHealthState.UNKNOWN -> "Not confirmed"
    }

    private fun assertRow(text: String, state: HostHealthState) {
        compose.onNodeWithText(text).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state.stateWord()))
    }

    @Test fun healthyReportReadsAsOneUnitPerRowAndSpeaksItsState() {
        val check = HostSelfCheck(
            certificate = HostCertificateCheck(true, "aaaaaaaa", Instant.parse("2026-09-13T11:59:00Z"),
                Instant.parse("2036-09-10T11:59:00Z")),
            listener = HostListenerCheck(true, "0.0.0.0", 18443),
            bridge = HostBridgeCheck(true, listOf("display", "restart")),
            temperature = HostTemperatureCheck(WireValue.Known(TemperatureSource.STEAM)),
            provider = HostProviderCheck(true, WireValue.Known(SunshineState.RUNNING), false, "1"),
            versions = HostVersionsCheck(1, "0.5.19"),
        )
        val now = Instant.parse("2026-10-04T00:00:00Z").toEpochMilli()
        show(HostHealthView.Report(HostHealthPresentation.rows(check, now), HostHealthPresentation.nudge(check)))

        compose.onNodeWithText("HOST HEALTH").assertExists()
        assertRow("Certificate · Valid until 2036-09-10 · …aaaaaaaa", HostHealthState.OK)
        assertRow("Listener · Listening on 0.0.0.0:18443", HostHealthState.OK)
        assertRow("Steam bridge · Ready · display, restart", HostHealthState.OK)
        assertRow("Temperature · Read from Steam", HostHealthState.OK)
        assertRow("Sunshine · Running · 1", HostHealthState.OK)
        assertRow("Host · Plugin v0.5.19 · protocol 1", HostHealthState.OK)
        assertRow("Host protocol 1 matches this app.", HostHealthState.OK)
    }

    @Test fun everyDetailHasWordingMatchingTheDeckCardAndANonColourState() {
        val rows = listOf(
            Triple(HostHealthItem.CERTIFICATE, HostHealthState.UNKNOWN, HostHealthDetail.NotReported) to
                "Certificate · Not reported by the host",
            Triple(HostHealthItem.CERTIFICATE, HostHealthState.WARNING, HostHealthDetail.CertificateNotReady) to
                "Certificate · Not ready — pairing is unavailable",
            Triple(HostHealthItem.CERTIFICATE, HostHealthState.WARNING, HostHealthDetail.CertificateExpired("2026-09-13", "aaaaaaaa")) to
                "Certificate · Expired 2026-09-13 · …aaaaaaaa",
            Triple(HostHealthItem.CERTIFICATE, HostHealthState.WARNING, HostHealthDetail.CertificateNotYetValid("2036-01-01", null)) to
                "Certificate · Not valid until 2036-01-01 — check this device's clock",
            Triple(HostHealthItem.CERTIFICATE, HostHealthState.OK, HostHealthDetail.CertificateReady(null)) to
                "Certificate · Ready",
            Triple(HostHealthItem.LISTENER, HostHealthState.OK, HostHealthDetail.Listening(null, 18443)) to
                "Listener · Listening on all interfaces:18443",
            Triple(HostHealthItem.LISTENER, HostHealthState.OK, HostHealthDetail.Listening("fd00::1", 9000)) to
                "Listener · Listening on [fd00::1]:9000",
            Triple(HostHealthItem.LISTENER, HostHealthState.OFF, HostHealthDetail.NotListening) to
                "Listener · Not listening",
            Triple(HostHealthItem.STEAM_BRIDGE, HostHealthState.OK, HostHealthDetail.BridgeReady(emptyList())) to
                "Steam bridge · Ready",
            Triple(HostHealthItem.STEAM_BRIDGE, HostHealthState.WARNING, HostHealthDetail.BridgeNotReady) to
                "Steam bridge · Not ready — power and display control are unavailable",
            Triple(HostHealthItem.TEMPERATURE, HostHealthState.OK, HostHealthDetail.TemperatureFromSystemSensors) to
                "Temperature · Read from system sensors",
            Triple(HostHealthItem.TEMPERATURE, HostHealthState.OFF, HostHealthDetail.TemperatureNoReading) to
                "Temperature · No sensor reading",
            Triple(HostHealthItem.TEMPERATURE, HostHealthState.UNKNOWN, HostHealthDetail.TemperatureSourceUnrecognized) to
                "Temperature · Source not recognized",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.OFF, HostHealthDetail.SunshineMonitoringOff) to
                "Sunshine · Monitoring off",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.UNKNOWN, HostHealthDetail.SunshineRunning(true, "1")) to
                "Sunshine · Running (stale) · 1",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.WARNING, HostHealthDetail.SunshineStopped(false, null)) to
                "Sunshine · Stopped",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.WARNING, HostHealthDetail.SunshineStopped(true, "1")) to
                "Sunshine · Stopped (stale) · 1",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.WARNING, HostHealthDetail.SunshineRestarting("1")) to
                "Sunshine · Restarting · 1",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.UNKNOWN, HostHealthDetail.SunshineOwnerUnavailable(null)) to
                "Sunshine · Owner unavailable",
            Triple(HostHealthItem.SUNSHINE, HostHealthState.UNKNOWN, HostHealthDetail.SunshineStateUnknown("1")) to
                "Sunshine · State unknown · 1",
            Triple(HostHealthItem.HOST, HostHealthState.UNKNOWN, HostHealthDetail.HostVersions(null, 1)) to
                "Host · Plugin version unknown · protocol 1",
            Triple(HostHealthItem.HOST, HostHealthState.UNKNOWN, HostHealthDetail.HostVersions(null, null)) to
                "Host · Plugin version unknown · protocol unknown",
        )
        show(HostHealthView.Report(rows.map { (row, _) -> HostHealthRow(row.first, row.second, row.third) },
            HostProtocolNudge(HostProtocolNudgeKind.MATCHES, 1)))
        rows.forEach { (row, text) -> assertRow(text, row.second) }
    }

    @Test fun protocolNudgeWordingAndState() {
        val cases = listOf(
            HostProtocolNudge(HostProtocolNudgeKind.NOT_REPORTED, null) to
                ("This host does not report its health. Update the Decky plugin on the host to see it here." to HostHealthState.UNKNOWN),
            HostProtocolNudge(HostProtocolNudgeKind.HOST_NEWER, 2) to
                ("Host protocol 2 is newer than this app supports — some actions may be unavailable. Update this app." to HostHealthState.WARNING),
            HostProtocolNudge(HostProtocolNudgeKind.HOST_OLDER, 0) to
                ("Host protocol 0 is older than this app — update the plugin on the host." to HostHealthState.WARNING),
            HostProtocolNudge(HostProtocolNudgeKind.MATCHES, 1) to
                ("Host protocol 1 matches this app." to HostHealthState.OK),
        )
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column { cases.forEach { (nudge, _) -> HostHealthSection(HostHealthView.Report(emptyList(), nudge)) } }
            }
        }
        cases.forEach { (_, expected) -> assertRow(expected.first, expected.second) }
    }

    @Test fun withoutAFreshObservationOnlyOneMutedLineShowsAndNoHostData() {
        val expected = mapOf(
            HostHealthUnavailableReason.CHECKING to "Checking host health…",
            HostHealthUnavailableReason.UNREACHABLE to "Host health isn't shown while the device can't be reached.",
            HostHealthUnavailableReason.OUT_OF_DATE to "Host health isn't shown until the device's status is up to date.",
        )
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column { expected.keys.forEach { HostHealthSection(HostHealthView.Unavailable(it)) } }
            }
        }
        compose.onAllNodesWithText("HOST HEALTH").assertCountEquals(expected.size)
        expected.values.forEach { compose.onNodeWithText(it).assertExists() }
        // Nothing but the heading and the one line per section: no row, no protocol hint.
        compose.onAllNodesWithText("Certificate", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("protocol", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Plugin", substring = true).assertCountEquals(0)
    }
}
