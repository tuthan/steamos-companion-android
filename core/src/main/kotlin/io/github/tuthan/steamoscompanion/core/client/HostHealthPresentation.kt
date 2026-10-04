package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.HostBridgeCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostCertificateCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HostListenerCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostProviderCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostSelfCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostTemperatureCheck
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.TemperatureSource
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import java.time.Instant
import java.time.ZoneOffset

/** Same four states as the Decky card: ok, warn, off, unknown. The UI conveys them by glyph and word, never colour alone. */
enum class HostHealthState { OK, WARNING, OFF, UNKNOWN }

enum class HostHealthItem { CERTIFICATE, LISTENER, STEAM_BRIDGE, TEMPERATURE, SUNSHINE, HOST }

/** What a row says, as facts. The app turns each into localized text; values are host-reported or derived here. */
sealed interface HostHealthDetail {
    /** The block, or this section of it, is missing or unusable. Nothing is claimed about the fact. */
    data object NotReported : HostHealthDetail

    data object CertificateNotReady : HostHealthDetail
    /** [day] is the UTC calendar day, `YYYY-MM-DD`. */
    data class CertificateExpired(val day: String, val fingerprintShort: String?) : HostHealthDetail
    data class CertificateNotYetValid(val day: String, val fingerprintShort: String?) : HostHealthDetail
    data class CertificateValidUntil(val day: String, val fingerprintShort: String?) : HostHealthDetail
    data class CertificateReady(val fingerprintShort: String?) : HostHealthDetail

    /** A null [address] means the host did not name one, so the UI says "all interfaces". */
    data class Listening(val address: String?, val port: Int) : HostHealthDetail
    data object NotListening : HostHealthDetail

    data class BridgeReady(val methods: List<String>) : HostHealthDetail
    data object BridgeNotReady : HostHealthDetail

    data object TemperatureFromSteam : HostHealthDetail
    data object TemperatureFromSystemSensors : HostHealthDetail
    data object TemperatureNoReading : HostHealthDetail
    /** A source name this app does not know; it is not guessed to mean "no reading". */
    data object TemperatureSourceUnrecognized : HostHealthDetail

    data object SunshineMonitoringOff : HostHealthDetail
    data class SunshineRunning(val stale: Boolean, val contractVersion: String?) : HostHealthDetail
    data class SunshineStopped(val stale: Boolean, val contractVersion: String?) : HostHealthDetail
    data class SunshineRestarting(val contractVersion: String?) : HostHealthDetail
    data class SunshineOwnerUnavailable(val contractVersion: String?) : HostHealthDetail
    data class SunshineStateUnknown(val contractVersion: String?) : HostHealthDetail

    /** A null [plugin] is "version unknown"; it is never filled in with a guess. */
    data class HostVersions(val plugin: String?, val protocol: Int?) : HostHealthDetail
}

data class HostHealthRow(val item: HostHealthItem, val state: HostHealthState, val detail: HostHealthDetail)

enum class HostProtocolNudgeKind { NOT_REPORTED, HOST_NEWER, HOST_OLDER, MATCHES }

/** How the host's protocol compares with the one this app speaks. [hostProtocol] is null only for [HostProtocolNudgeKind.NOT_REPORTED]. */
data class HostProtocolNudge(val kind: HostProtocolNudgeKind, val hostProtocol: Int?)

enum class HostHealthUnavailableReason { CHECKING, UNREACHABLE, OUT_OF_DATE }

sealed interface HostHealthView {
    /** No fresh authenticated observation: show one muted line and no host data at all. */
    data class Unavailable(val reason: HostHealthUnavailableReason) : HostHealthView

    /** [rows] is empty when the host did not report a usable block; [nudge] then says so. */
    data class Report(val rows: List<HostHealthRow>, val nudge: HostProtocolNudge) : HostHealthView
}

/**
 * Pure projection of the host's optional `self_check` block for the Connection details screen. It reads
 * only the observation the paired session already holds; it never contacts the host. Row logic mirrors
 * the Decky card (`selfCheckRows` and `selfCheckNudge` in `host/frontend/index.js`) so every client
 * agrees. One deliberate difference: a section the host did not usably report is [HostHealthDetail.NotReported],
 * not a warning, because the app cannot tell "broken" from "not sent".
 */
object HostHealthPresentation {
    fun project(host: ActiveHost, observation: StatusPollState, nowWallMillis: Long): HostHealthView {
        if (observation.unreachable) return HostHealthView.Unavailable(HostHealthUnavailableReason.UNREACHABLE)
        val read = observation.lastRead ?: return HostHealthView.Unavailable(HostHealthUnavailableReason.CHECKING)
        if (observation.stale || observation.source != StatusObservationSource.from(host) || read.status.hostId != host.hostId) {
            return HostHealthView.Unavailable(HostHealthUnavailableReason.OUT_OF_DATE)
        }
        val check = read.status.selfCheck
        return HostHealthView.Report(rows(check, nowWallMillis), nudge(check))
    }

    /** Certificate, Listener, Steam bridge, Temperature, Sunshine, Host; empty when there is no block. */
    fun rows(check: HostSelfCheck?, nowWallMillis: Long): List<HostHealthRow> {
        if (check == null) return emptyList()
        return listOf(
            certificate(check.certificate, nowWallMillis),
            listener(check.listener),
            bridge(check.bridge),
            temperature(check.temperature),
            sunshine(check.provider),
            host(check),
        )
    }

    fun nudge(check: HostSelfCheck?): HostProtocolNudge {
        val protocol = check?.versions?.protocol
        val supported = ProtocolCodec.SUPPORTED_PROTOCOL_VERSION
        val kind = when {
            protocol == null -> HostProtocolNudgeKind.NOT_REPORTED
            protocol > supported -> HostProtocolNudgeKind.HOST_NEWER
            protocol < supported -> HostProtocolNudgeKind.HOST_OLDER
            else -> HostProtocolNudgeKind.MATCHES
        }
        return HostProtocolNudge(kind, protocol)
    }

    private fun row(item: HostHealthItem, state: HostHealthState, detail: HostHealthDetail) =
        HostHealthRow(item, state, detail)

    private fun notReported(item: HostHealthItem) = row(item, HostHealthState.UNKNOWN, HostHealthDetail.NotReported)

    private fun certificate(check: HostCertificateCheck?, now: Long): HostHealthRow {
        val item = HostHealthItem.CERTIFICATE
        if (check == null) return notReported(item)
        val short = check.fingerprintShort
        val notBefore = check.notBefore
        val notAfter = check.notAfter
        return when {
            !check.ready -> row(item, HostHealthState.WARNING, HostHealthDetail.CertificateNotReady)
            notAfter != null && now > notAfter.toEpochMilli() ->
                row(item, HostHealthState.WARNING, HostHealthDetail.CertificateExpired(day(notAfter), short))
            notBefore != null && now < notBefore.toEpochMilli() ->
                row(item, HostHealthState.WARNING, HostHealthDetail.CertificateNotYetValid(day(notBefore), short))
            notAfter != null -> row(item, HostHealthState.OK, HostHealthDetail.CertificateValidUntil(day(notAfter), short))
            else -> row(item, HostHealthState.OK, HostHealthDetail.CertificateReady(short))
        }
    }

    private fun listener(check: HostListenerCheck?): HostHealthRow {
        val item = HostHealthItem.LISTENER
        return when {
            check == null -> notReported(item)
            check.bound -> row(item, HostHealthState.OK,
                HostHealthDetail.Listening(check.address, check.port ?: HostEndpoint.DEFAULT_PORT))
            else -> row(item, HostHealthState.OFF, HostHealthDetail.NotListening)
        }
    }

    private fun bridge(check: HostBridgeCheck?): HostHealthRow {
        val item = HostHealthItem.STEAM_BRIDGE
        return when {
            check == null -> notReported(item)
            check.ready -> row(item, HostHealthState.OK, HostHealthDetail.BridgeReady(check.methods))
            else -> row(item, HostHealthState.WARNING, HostHealthDetail.BridgeNotReady)
        }
    }

    private fun temperature(check: HostTemperatureCheck?): HostHealthRow {
        val item = HostHealthItem.TEMPERATURE
        if (check == null) return notReported(item)
        return when (check.source) {
            WireValue.Known(TemperatureSource.STEAM) -> row(item, HostHealthState.OK, HostHealthDetail.TemperatureFromSteam)
            WireValue.Known(TemperatureSource.HWMON) ->
                row(item, HostHealthState.OK, HostHealthDetail.TemperatureFromSystemSensors)
            WireValue.Known(TemperatureSource.UNAVAILABLE) ->
                row(item, HostHealthState.OFF, HostHealthDetail.TemperatureNoReading)
            else -> row(item, HostHealthState.UNKNOWN, HostHealthDetail.TemperatureSourceUnrecognized)
        }
    }

    private fun sunshine(check: HostProviderCheck?): HostHealthRow {
        val item = HostHealthItem.SUNSHINE
        if (check == null) return notReported(item)
        if (!check.monitorEnabled) return row(item, HostHealthState.OFF, HostHealthDetail.SunshineMonitoringOff)
        val contract = check.contractVersion
        return when (check.state) {
            // A stale "running" is the host's old observation, not a confirmed healthy state.
            WireValue.Known(SunshineState.RUNNING) -> row(item,
                if (check.stale) HostHealthState.UNKNOWN else HostHealthState.OK,
                HostHealthDetail.SunshineRunning(check.stale, contract))
            WireValue.Known(SunshineState.STOPPED) ->
                row(item, HostHealthState.WARNING, HostHealthDetail.SunshineStopped(check.stale, contract))
            WireValue.Known(SunshineState.RESTARTING) ->
                row(item, HostHealthState.WARNING, HostHealthDetail.SunshineRestarting(contract))
            WireValue.Known(SunshineState.UNAVAILABLE) ->
                row(item, HostHealthState.UNKNOWN, HostHealthDetail.SunshineOwnerUnavailable(contract))
            else -> row(item, HostHealthState.UNKNOWN, HostHealthDetail.SunshineStateUnknown(contract))
        }
    }

    private fun host(check: HostSelfCheck): HostHealthRow {
        val plugin = check.versions?.plugin
        return row(HostHealthItem.HOST,
            if (plugin == null) HostHealthState.UNKNOWN else HostHealthState.OK,
            HostHealthDetail.HostVersions(plugin, check.versions?.protocol))
    }

    private fun day(instant: Instant): String = instant.atOffset(ZoneOffset.UTC).toLocalDate().toString()
}
