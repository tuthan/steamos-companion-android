package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostBridgeCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostCertificateCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostListenerCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostProviderCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostSelfCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostTemperatureCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostVersionsCheck
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.TemperatureSource
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostHealthPresentationTest {
    private class FixedClock(private val wallMillis: Long) : Clock {
        override fun wallTimeMillis() = wallMillis
        override fun monotonicNanos() = 0L
    }

    /** The fixtures' certificate is valid from 2026-09-13T11:59:00Z to 2036-09-10T11:59:00Z. */
    private val clock = FixedClock(Instant.parse("2026-10-04T00:00:00Z").toEpochMilli())
    private val now get() = clock.wallTimeMillis()
    private val host = testActiveHost()

    // --- rows ------------------------------------------------------------------------------

    @Test fun healthyHostShowsSixRowsInTheDeckCardOrder() {
        val rows = HostHealthPresentation.rows(check("sunshine-disabled"), now)
        assertEquals(
            listOf(
                HostHealthRow(HostHealthItem.CERTIFICATE, HostHealthState.OK,
                    HostHealthDetail.CertificateValidUntil("2036-09-10", "aaaaaaaa")),
                HostHealthRow(HostHealthItem.LISTENER, HostHealthState.OK, HostHealthDetail.Listening("0.0.0.0", 18443)),
                HostHealthRow(HostHealthItem.STEAM_BRIDGE, HostHealthState.OK,
                    HostHealthDetail.BridgeReady(listOf("display", "restart", "shutdown", "suspend"))),
                HostHealthRow(HostHealthItem.TEMPERATURE, HostHealthState.OK, HostHealthDetail.TemperatureFromSystemSensors),
                HostHealthRow(HostHealthItem.SUNSHINE, HostHealthState.OFF, HostHealthDetail.SunshineMonitoringOff),
                HostHealthRow(HostHealthItem.HOST, HostHealthState.OK, HostHealthDetail.HostVersions("0.0.0-fixture", 1)),
            ),
            rows,
        )
    }

    @Test fun noBlockMeansNoRows() {
        assertEquals(emptyList<HostHealthRow>(), HostHealthPresentation.rows(null, now))
    }

    @Test fun certificateThatIsNotReadyIsAWarning() {
        val row = HostHealthPresentation.rows(check("self-check-certificate-unusable"), now).first()
        assertEquals(HostHealthRow(HostHealthItem.CERTIFICATE, HostHealthState.WARNING, HostHealthDetail.CertificateNotReady), row)
    }

    @Test fun expiredCertificateIsAWarningUsingTheInjectedClock() {
        val check = check("sunshine-disabled")
        val after = Instant.parse("2036-09-11T00:00:00Z").toEpochMilli()
        assertEquals(
            HostHealthRow(HostHealthItem.CERTIFICATE, HostHealthState.WARNING,
                HostHealthDetail.CertificateExpired("2036-09-10", "aaaaaaaa")),
            HostHealthPresentation.rows(check, after).first(),
        )
        // The same block is fine today: only the injected time differs.
        assertEquals(HostHealthState.OK, HostHealthPresentation.rows(check, now).first().state)
    }

    @Test fun certificateIsExpiredOnlyStrictlyAfterNotAfter() {
        val check = check("sunshine-disabled")
        val notAfter = Instant.parse("2036-09-10T11:59:00Z").toEpochMilli()
        assertEquals(HostHealthState.OK, HostHealthPresentation.rows(check, notAfter).first().state)
        assertEquals(HostHealthState.WARNING, HostHealthPresentation.rows(check, notAfter + 1).first().state)
    }

    @Test fun certificateNotYetValidPointsAtThisDevicesClock() {
        val early = Instant.parse("2026-09-12T00:00:00Z").toEpochMilli()
        assertEquals(
            HostHealthRow(HostHealthItem.CERTIFICATE, HostHealthState.WARNING,
                HostHealthDetail.CertificateNotYetValid("2026-09-13", "aaaaaaaa")),
            HostHealthPresentation.rows(check("sunshine-disabled"), early).first(),
        )
    }

    @Test fun readyCertificateWithoutDatesIsJustReady() {
        val bare = check("sunshine-disabled").copy(certificate = HostCertificateCheck(true, "bbbbbbbb", null, null))
        assertEquals(HostHealthDetail.CertificateReady("bbbbbbbb"), HostHealthPresentation.rows(bare, now).first().detail)
        val anonymous = bare.copy(certificate = HostCertificateCheck(true, null, null, null))
        assertEquals(HostHealthRow(HostHealthItem.CERTIFICATE, HostHealthState.OK, HostHealthDetail.CertificateReady(null)),
            HostHealthPresentation.rows(anonymous, now).first())
    }

    @Test fun listenerRows() {
        val base = check("sunshine-disabled")
        val unnamed = base.copy(listener = HostListenerCheck(true, null, null))
        assertEquals(HostHealthRow(HostHealthItem.LISTENER, HostHealthState.OK, HostHealthDetail.Listening(null, 18443)),
            HostHealthPresentation.rows(unnamed, now)[1])
        val closed = base.copy(listener = HostListenerCheck(false, null, null))
        assertEquals(HostHealthRow(HostHealthItem.LISTENER, HostHealthState.OFF, HostHealthDetail.NotListening),
            HostHealthPresentation.rows(closed, now)[1])
    }

    @Test fun bridgeRows() {
        assertEquals(HostHealthRow(HostHealthItem.STEAM_BRIDGE, HostHealthState.WARNING, HostHealthDetail.BridgeNotReady),
            HostHealthPresentation.rows(check("steam-bridge-not-ready"), now)[2])
        assertEquals(HostHealthRow(HostHealthItem.STEAM_BRIDGE, HostHealthState.WARNING, HostHealthDetail.BridgeNotReady),
            HostHealthPresentation.rows(check("steam-bridge-unavailable"), now)[2])
        val noMethods = check("sunshine-disabled").copy(bridge = HostBridgeCheck(true, emptyList()))
        assertEquals(HostHealthRow(HostHealthItem.STEAM_BRIDGE, HostHealthState.OK, HostHealthDetail.BridgeReady(emptyList())),
            HostHealthPresentation.rows(noMethods, now)[2])
    }

    @Test fun temperatureRows() {
        assertEquals(HostHealthRow(HostHealthItem.TEMPERATURE, HostHealthState.OK, HostHealthDetail.TemperatureFromSteam),
            HostHealthPresentation.rows(check("self-check-steam-temperature"), now)[3])
        val base = check("sunshine-disabled")
        fun source(value: WireValue<TemperatureSource>) =
            HostHealthPresentation.rows(base.copy(temperature = HostTemperatureCheck(value)), now)[3]
        assertEquals(HostHealthRow(HostHealthItem.TEMPERATURE, HostHealthState.OFF, HostHealthDetail.TemperatureNoReading),
            source(WireValue.Known(TemperatureSource.UNAVAILABLE)))
        // A source this app does not know is not claimed to mean "no sensor".
        assertEquals(HostHealthRow(HostHealthItem.TEMPERATURE, HostHealthState.UNKNOWN,
            HostHealthDetail.TemperatureSourceUnrecognized), source(WireValue.Unknown("acpi")))
    }

    @Test fun sunshineRows() {
        fun sunshine(state: WireValue<SunshineState>, enabled: Boolean = true, stale: Boolean = false, contract: String? = "1") =
            HostHealthPresentation.rows(
                check("sunshine-running").copy(provider = HostProviderCheck(enabled, state, stale, contract)), now)[4]
        fun row(state: HostHealthState, detail: HostHealthDetail) = HostHealthRow(HostHealthItem.SUNSHINE, state, detail)

        assertEquals(row(HostHealthState.OFF, HostHealthDetail.SunshineMonitoringOff),
            sunshine(WireValue.Known(SunshineState.RUNNING), enabled = false))
        assertEquals(row(HostHealthState.OK, HostHealthDetail.SunshineRunning(false, "1")),
            sunshine(WireValue.Known(SunshineState.RUNNING)))
        assertEquals(row(HostHealthState.WARNING, HostHealthDetail.SunshineStopped(false, "1")),
            sunshine(WireValue.Known(SunshineState.STOPPED)))
        assertEquals(row(HostHealthState.WARNING, HostHealthDetail.SunshineStopped(true, null)),
            sunshine(WireValue.Known(SunshineState.STOPPED), stale = true, contract = null))
        assertEquals(row(HostHealthState.WARNING, HostHealthDetail.SunshineRestarting("1")),
            sunshine(WireValue.Known(SunshineState.RESTARTING)))
        assertEquals(row(HostHealthState.UNKNOWN, HostHealthDetail.SunshineOwnerUnavailable("1")),
            sunshine(WireValue.Known(SunshineState.UNAVAILABLE)))
        assertEquals(row(HostHealthState.UNKNOWN, HostHealthDetail.SunshineStateUnknown("1")),
            sunshine(WireValue.Known(SunshineState.UNKNOWN)))
        assertEquals(row(HostHealthState.UNKNOWN, HostHealthDetail.SunshineStateUnknown("1")),
            sunshine(WireValue.Known(SunshineState.DISABLED)))
        assertEquals(row(HostHealthState.UNKNOWN, HostHealthDetail.SunshineStateUnknown("1")),
            sunshine(WireValue.Unknown("paused")))
    }

    @Test fun aStaleRunningSunshineIsNotAConfirmedHealthyState() {
        val stale = HostHealthPresentation.rows(check("sunshine-running").copy(
            provider = HostProviderCheck(true, WireValue.Known(SunshineState.RUNNING), true, "1")), now)[4]
        assertEquals(HostHealthState.UNKNOWN, stale.state)
        assertEquals(HostHealthDetail.SunshineRunning(true, "1"), stale.detail)
    }

    @Test fun fixtureSunshineStatesProjectAsTheirRows() {
        assertEquals(HostHealthState.OK, HostHealthPresentation.rows(check("sunshine-running"), now)[4].state)
        assertEquals(HostHealthState.WARNING, HostHealthPresentation.rows(check("sunshine-stopped"), now)[4].state)
        assertEquals(HostHealthState.WARNING, HostHealthPresentation.rows(check("sunshine-restarting"), now)[4].state)
        assertEquals(HostHealthState.WARNING, HostHealthPresentation.rows(check("sunshine-owner-unavailable"), now)[4].state)
        assertEquals(HostHealthState.UNKNOWN, HostHealthPresentation.rows(check("sunshine-unknown"), now)[4].state)
    }

    @Test fun hostRowNeverGuessesAnUnknownPluginVersion() {
        assertEquals(HostHealthRow(HostHealthItem.HOST, HostHealthState.UNKNOWN, HostHealthDetail.HostVersions(null, 1)),
            HostHealthPresentation.rows(check("self-check-plugin-version-unknown"), now)[5])
        val noProtocol = check("sunshine-disabled").copy(versions = HostVersionsCheck(null, "1.2.3"))
        assertEquals(HostHealthRow(HostHealthItem.HOST, HostHealthState.OK, HostHealthDetail.HostVersions("1.2.3", null)),
            HostHealthPresentation.rows(noProtocol, now)[5])
    }

    @Test fun sectionsTheHostDidNotUsablyReportClaimNothing() {
        val onlyVersions = HostSelfCheck(null, null, null, null, null, HostVersionsCheck(1, "1.2.3"))
        val rows = HostHealthPresentation.rows(onlyVersions, now)
        assertEquals(6, rows.size)
        assertEquals(
            listOf(HostHealthItem.CERTIFICATE, HostHealthItem.LISTENER, HostHealthItem.STEAM_BRIDGE,
                HostHealthItem.TEMPERATURE, HostHealthItem.SUNSHINE),
            rows.take(5).map { it.item },
        )
        rows.take(5).forEach {
            assertEquals(HostHealthState.UNKNOWN, it.state)
            assertEquals(HostHealthDetail.NotReported, it.detail)
        }
        assertEquals(HostHealthState.OK, rows[5].state)
    }

    // --- protocol nudge --------------------------------------------------------------------

    @Test fun nudgeComparesTheHostProtocolWithTheOneThisAppSpeaks() {
        assertEquals(1, ProtocolCodec.SUPPORTED_PROTOCOL_VERSION)
        fun nudge(protocol: Int?) = HostHealthPresentation.nudge(
            check("sunshine-disabled").copy(versions = HostVersionsCheck(protocol, "1.0.0")))
        assertEquals(HostProtocolNudge(HostProtocolNudgeKind.MATCHES, 1), nudge(1))
        assertEquals(HostProtocolNudge(HostProtocolNudgeKind.HOST_NEWER, 2), nudge(2))
        assertEquals(HostProtocolNudge(HostProtocolNudgeKind.HOST_OLDER, 0), nudge(0))
        assertEquals(HostProtocolNudge(HostProtocolNudgeKind.NOT_REPORTED, null), nudge(null))
    }

    @Test fun absentBlockOrVersionsSectionMeansTheHostDoesNotReportItsHealth() {
        val notReported = HostProtocolNudge(HostProtocolNudgeKind.NOT_REPORTED, null)
        assertEquals(notReported, HostHealthPresentation.nudge(null))
        assertEquals(notReported, HostHealthPresentation.nudge(check("sunshine-disabled").copy(versions = null)))
    }

    // --- freshness: never stale-as-fresh ----------------------------------------------------

    @Test fun freshObservationProjectsTheReport() {
        val view = HostHealthPresentation.project(host, observation(status("sunshine-running")), now)
        view as HostHealthView.Report
        assertEquals(6, view.rows.size)
        assertEquals(HostProtocolNudgeKind.MATCHES, view.nudge.kind)
    }

    @Test fun hostWithoutTheBlockStillGetsTheUpdateHintAndNoRows() {
        val bare = status("sunshine-running").copy(selfCheck = null)
        val view = HostHealthPresentation.project(host, observation(bare), now) as HostHealthView.Report
        assertEquals(emptyList<HostHealthRow>(), view.rows)
        assertEquals(HostProtocolNudgeKind.NOT_REPORTED, view.nudge.kind)
    }

    @Test fun unreachableHostShowsNoDataEvenWhenAnOldReadIsStillHeld() {
        val held = observation(status("sunshine-running"))
        assertEquals(HostHealthView.Unavailable(HostHealthUnavailableReason.UNREACHABLE),
            HostHealthPresentation.project(host, held.copy(unreachable = true), now))
        assertEquals(HostHealthView.Unavailable(HostHealthUnavailableReason.UNREACHABLE),
            HostHealthPresentation.project(host, StatusPollState(unreachable = true), now))
    }

    @Test fun noObservationYetIsChecking() {
        assertEquals(HostHealthView.Unavailable(HostHealthUnavailableReason.CHECKING),
            HostHealthPresentation.project(host, StatusPollState(), now))
        assertEquals(HostHealthView.Unavailable(HostHealthUnavailableReason.CHECKING),
            HostHealthPresentation.project(host, StatusPollState(reading = true), now))
    }

    @Test fun staleOrForeignObservationIsOutOfDate() {
        val held = observation(status("sunshine-running"))
        val outOfDate = HostHealthView.Unavailable(HostHealthUnavailableReason.OUT_OF_DATE)
        assertEquals(outOfDate, HostHealthPresentation.project(host, held.copy(stale = true), now))
        assertEquals(outOfDate, HostHealthPresentation.project(host, held.copy(source = null), now))
        val otherHost = host.copy(bearerToken = "another-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        assertEquals(outOfDate, HostHealthPresentation.project(otherHost, held, now))
        val otherId = host.copy(hostId = "host-other0002xxxxxxxxxxxxx")
        assertEquals(outOfDate, HostHealthPresentation.project(otherId, held.copy(source = StatusObservationSource.from(otherId)), now))
        assertTrue(HostHealthPresentation.project(host, held, now) is HostHealthView.Report)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun status(name: String): VersionedPayload.Status {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/status/$name.json"))
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(ApiRoute.STATUS,
            HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload as VersionedPayload.Status
    }

    private fun check(name: String): HostSelfCheck = checkNotNull(status(name).selfCheck)

    private fun observation(status: VersionedPayload.Status) = StatusPollState(
        lastRead = PairedStatusRead(status, null, null), lastSuccessMonotonicNanos = 1_000_000_000L,
        source = StatusObservationSource.from(host),
    )
}
