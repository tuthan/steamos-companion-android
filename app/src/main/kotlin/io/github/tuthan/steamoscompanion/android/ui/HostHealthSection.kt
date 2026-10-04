package io.github.tuthan.steamoscompanion.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.core.client.HostHealthDetail
import io.github.tuthan.steamoscompanion.core.client.HostHealthItem
import io.github.tuthan.steamoscompanion.core.client.HostHealthRow
import io.github.tuthan.steamoscompanion.core.client.HostHealthState
import io.github.tuthan.steamoscompanion.core.client.HostHealthUnavailableReason
import io.github.tuthan.steamoscompanion.core.client.HostHealthView
import io.github.tuthan.steamoscompanion.core.client.HostProtocolNudge
import io.github.tuthan.steamoscompanion.core.client.HostProtocolNudgeKind

/**
 * The "Host health" group of Connection details: what the host says about its own certificate,
 * listener, Steam bridge, temperature source, Sunshine and versions. [view] comes from the status
 * observation the paired session already holds; without a fresh one only a single muted line shows,
 * never an older reading. Each row is one readable unit (label, value) that also speaks its state,
 * and its state is drawn as a glyph as well as a colour.
 */
@Composable
fun HostHealthSection(view: HostHealthView) {
    Kicker(stringResource(R.string.ui_host_health), Modifier.padding(top = 8.dp))
    when (view) {
        is HostHealthView.Unavailable -> MutedLine(stringResource(when (view.reason) {
            HostHealthUnavailableReason.CHECKING -> R.string.ui_host_health_checking
            HostHealthUnavailableReason.UNREACHABLE -> R.string.ui_host_health_unreachable
            HostHealthUnavailableReason.OUT_OF_DATE -> R.string.ui_host_health_out_of_date
        }))
        is HostHealthView.Report -> Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CompanionSpacing.KICKER_GAP_DP.dp)) {
            view.rows.forEach { HealthRow(it) }
            HealthLine(nudgeText(view.nudge), nudgeState(view.nudge), detail = null)
        }
    }
}

@Composable
private fun MutedLine(text: String) {
    val palette = LocalCompanionPalette.current
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
        color = Color(palette.textDim.toInt()))
}

@Composable
private fun HealthRow(row: HostHealthRow) =
    HealthLine(stringResource(row.item.label()), row.state, detailText(row.detail))

/** [StatusLine] already merges its text into one node; the state word is added for screen readers. */
@Composable
private fun HealthLine(text: String, state: HostHealthState, detail: String?) {
    val stateWord = stringResource(state.word())
    StatusLine(text, state.tone(), Modifier.semantics { stateDescription = stateWord },
        glyph = state.glyph(), detail = detail)
}

@StringRes
private fun HostHealthItem.label(): Int = when (this) {
    HostHealthItem.CERTIFICATE -> R.string.ui_health_certificate
    HostHealthItem.LISTENER -> R.string.ui_health_listener
    HostHealthItem.STEAM_BRIDGE -> R.string.ui_health_steam_bridge
    HostHealthItem.TEMPERATURE -> R.string.ui_health_temperature
    HostHealthItem.SUNSHINE -> R.string.ui_health_sunshine
    HostHealthItem.HOST -> R.string.ui_health_host
}

@StringRes
private fun HostHealthState.word(): Int = when (this) {
    HostHealthState.OK -> R.string.ui_health_state_ok
    HostHealthState.WARNING -> R.string.ui_health_state_warning
    HostHealthState.OFF -> R.string.ui_health_state_off
    HostHealthState.UNKNOWN -> R.string.ui_health_state_unknown
}

private fun HostHealthState.tone(): StatusTone = when (this) {
    HostHealthState.OK -> StatusTone.GOOD
    HostHealthState.WARNING -> StatusTone.WARNING
    HostHealthState.OFF, HostHealthState.UNKNOWN -> StatusTone.UNKNOWN
}

/** Four different shapes, so no state depends on its colour: check, "i", empty ring, question mark. */
private fun HostHealthState.glyph(): StatusGlyph = when (this) {
    HostHealthState.OK -> StatusGlyph.CHECK
    HostHealthState.WARNING -> StatusGlyph.INFO
    HostHealthState.OFF -> StatusGlyph.UNKNOWN
    HostHealthState.UNKNOWN -> StatusGlyph.QUESTION
}

private fun nudgeState(nudge: HostProtocolNudge): HostHealthState = when (nudge.kind) {
    HostProtocolNudgeKind.MATCHES -> HostHealthState.OK
    HostProtocolNudgeKind.HOST_NEWER, HostProtocolNudgeKind.HOST_OLDER -> HostHealthState.WARNING
    HostProtocolNudgeKind.NOT_REPORTED -> HostHealthState.UNKNOWN
}

@Composable
private fun nudgeText(nudge: HostProtocolNudge): String {
    val protocol = nudge.hostProtocol?.toString().orEmpty()
    return when (nudge.kind) {
        HostProtocolNudgeKind.NOT_REPORTED -> stringResource(R.string.ui_health_nudge_not_reported)
        HostProtocolNudgeKind.HOST_NEWER -> stringResource(R.string.ui_health_nudge_newer, protocol)
        HostProtocolNudgeKind.HOST_OLDER -> stringResource(R.string.ui_health_nudge_older, protocol)
        HostProtocolNudgeKind.MATCHES -> stringResource(R.string.ui_health_nudge_matches, protocol)
    }
}

@Composable
private fun detailText(detail: HostHealthDetail): String = when (detail) {
    HostHealthDetail.NotReported -> stringResource(R.string.ui_health_not_reported)
    HostHealthDetail.CertificateNotReady -> stringResource(R.string.ui_health_cert_not_ready)
    is HostHealthDetail.CertificateExpired ->
        joined(stringResource(R.string.ui_health_cert_expired, detail.day), fingerprint(detail.fingerprintShort))
    is HostHealthDetail.CertificateNotYetValid ->
        joined(stringResource(R.string.ui_health_cert_not_yet_valid, detail.day), fingerprint(detail.fingerprintShort))
    is HostHealthDetail.CertificateValidUntil ->
        joined(stringResource(R.string.ui_health_cert_valid_until, detail.day), fingerprint(detail.fingerprintShort))
    is HostHealthDetail.CertificateReady ->
        joined(stringResource(R.string.ui_health_cert_ready), fingerprint(detail.fingerprintShort))

    is HostHealthDetail.Listening -> stringResource(R.string.ui_health_listening_on,
        detail.address?.let { if (':' in it) "[$it]" else it } ?: stringResource(R.string.ui_health_all_interfaces),
        detail.port)
    HostHealthDetail.NotListening -> stringResource(R.string.ui_health_not_listening)

    is HostHealthDetail.BridgeReady ->
        if (detail.methods.isEmpty()) stringResource(R.string.ui_health_bridge_ready)
        else stringResource(R.string.ui_health_bridge_ready_methods,
            detail.methods.joinToString(stringResource(R.string.ui_health_list_separator)))
    HostHealthDetail.BridgeNotReady -> stringResource(R.string.ui_health_bridge_not_ready)

    HostHealthDetail.TemperatureFromSteam -> stringResource(R.string.ui_health_temperature_steam)
    HostHealthDetail.TemperatureFromSystemSensors -> stringResource(R.string.ui_health_temperature_sensors)
    HostHealthDetail.TemperatureNoReading -> stringResource(R.string.ui_health_temperature_none)
    HostHealthDetail.TemperatureSourceUnrecognized -> stringResource(R.string.ui_health_temperature_unrecognized)

    HostHealthDetail.SunshineMonitoringOff -> stringResource(R.string.ui_health_sunshine_off)
    is HostHealthDetail.SunshineRunning -> joined(stringResource(
        if (detail.stale) R.string.ui_health_sunshine_running_stale else R.string.ui_health_sunshine_running),
        detail.contractVersion)
    is HostHealthDetail.SunshineStopped -> joined(stringResource(
        if (detail.stale) R.string.ui_health_sunshine_stopped_stale else R.string.ui_health_sunshine_stopped),
        detail.contractVersion)
    is HostHealthDetail.SunshineRestarting ->
        joined(stringResource(R.string.ui_health_sunshine_restarting), detail.contractVersion)
    is HostHealthDetail.SunshineOwnerUnavailable ->
        joined(stringResource(R.string.ui_health_sunshine_owner_unavailable), detail.contractVersion)
    is HostHealthDetail.SunshineStateUnknown ->
        joined(stringResource(R.string.ui_health_sunshine_unknown), detail.contractVersion)

    is HostHealthDetail.HostVersions -> stringResource(R.string.ui_health_host_versions,
        detail.plugin?.let { stringResource(R.string.ui_health_plugin_version, it) }
            ?: stringResource(R.string.ui_health_plugin_unknown),
        detail.protocol?.toString() ?: stringResource(R.string.ui_health_protocol_unknown))
}

@Composable
private fun fingerprint(short: String?): String? =
    short?.let { stringResource(R.string.ui_health_fingerprint_short, it) }

/** "first · second · third", skipping parts the host did not report. */
@Composable
private fun joined(first: String, vararg more: String?): String =
    more.filterNotNull().fold(first) { text, part -> stringResource(R.string.ui_health_join, text, part) }
