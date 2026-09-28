package io.github.tuthan.steamoscompanion.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.android.scanner.CameraQrScanner
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate

@Composable
fun DiscoveryScreen(flow: PairingFlowViewModel, onRefresh: () -> Unit, onCancel: () -> Unit,
                    onSelect: (UnverifiedDiscoveryCandidate) -> Unit,
                    onManual: () -> Unit) {
    val context = LocalContext.current
    Kicker(stringResource(R.string.ui_discovery_kicker))
    if (flow.scanning) {
        StatusLine(stringResource(R.string.ui_discovery_searching), StatusTone.PENDING, glyph = StatusGlyph.RADAR)
        val reducedMotion = runCatching { Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
        ProgressStrip(ProgressState.Indeterminate, reduceMotion = reducedMotion)
    }
    val result = flow.discovery
    if (result != null) {
        if (result.candidates.isEmpty()) StatusLine(stringResource(R.string.ui_discovery_no_results), StatusTone.WARNING)
        else Kicker("Found")
        result.candidates.forEach { candidate ->
            ActionRow(candidate.target.endpoint.authority, monoTitle = true, icon = R.drawable.ic_lock, navigates = true,
                secondary = candidate.name ?: "${stringResource(R.string.ui_identity_prefix)} …${candidate.certificateFingerprint.takeLast(8)}",
                onClick = { onSelect(candidate) })
        }
        if (result.partial) StatusLine(stringResource(R.string.ui_discovery_partial_scan), StatusTone.WARNING)
    }
    flow.message?.let { StatusLine(it, StatusTone.WARNING) }
    if (flow.scanning) GhostAction(stringResource(R.string.ui_cancel_search), icon = R.drawable.ic_close, onClick = onCancel)
    else GhostAction(stringResource(R.string.ui_find_again), icon = R.drawable.ic_radar, onClick = onRefresh)
    ActionRow(stringResource(R.string.ui_enter_address), navigates = true, onClick = onManual)
}

@Composable
fun ManualAddressScreen(flow: PairingFlowViewModel, onCheck: (String) -> Unit,
                        onFound: () -> Unit) {
    val palette = LocalCompanionPalette.current
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("18443") }
    Text(stringResource(R.string.ui_enter_address), style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    OutlinedTextField(host, onValueChange = { host = it.take(253) },
        label = { Text(stringResource(R.string.ui_manual_host_label)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
        label = { Text(stringResource(R.string.ui_manual_port_label)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    PrimaryAction(stringResource(R.string.ui_manual_check_device), enabled = !flow.busy &&
        host.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true,
        onClick = { onCheck("${host.trim()}:${port.trim()}") })
    flow.message?.let { StatusLine(it, StatusTone.WARNING) }
    val selected = flow.selected
    if (selected != null) {
        InfoCard("Device found", selected.name ?: selected.target.endpoint.authority, icon = R.drawable.ic_check)
        ActionRow("Review device", navigates = true, onClick = onFound)
    }
}

@Composable
fun CandidateDetailsScreen(candidate: UnverifiedDiscoveryCandidate?, busy: Boolean,
                           onRequest: () -> Unit) {
    val palette = LocalCompanionPalette.current
    Text("Device details", style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    if (candidate == null) {
        StatusLine("Check the device again", StatusTone.WARNING)
        return
    }
    InfoCard(candidate.name ?: "SteamOS Companion device", "", icon = R.drawable.ic_lock) {
        MonoValue(candidate.target.endpoint.authority)
    }
    Kicker("Pinned identity")
    MonoValue(candidate.certificateFingerprint)
    Kicker(stringResource(R.string.ui_requested_access))
    ScopeBadges(listOf("status.read", "power.control", "display.control"))
    PrimaryAction(stringResource(R.string.ui_request_pairing), enabled = !busy, icon = R.drawable.ic_key, onClick = onRequest)
}

/** Granted or requested scopes as the mockup's uppercase chips; unknown scopes keep their wire name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScopeBadges(scopes: List<String>, granted: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        scopes.forEach { scope ->
            val label = when (scope) {
                "status.read" -> stringResource(R.string.ui_permission_read_status)
                "power.control" -> stringResource(R.string.ui_permission_control_power)
                "display.control" -> stringResource(R.string.ui_permission_change_display)
                else -> scope
            }
            Badge(label, if (granted) StatusTone.GOOD else StatusTone.PENDING,
                icon = if (granted) R.drawable.ic_check else null)
        }
    }
}

@Composable
fun PairingScreen(action: PairingAction, busy: Boolean, onCancel: () -> Unit,
                  onCheckAgain: () -> Unit) {
    val palette = LocalCompanionPalette.current
    Kicker(stringResource(R.string.ui_pairing_with))
    val candidate = when (action) {
        is PairingAction.Ready -> action.candidate
        is PairingAction.Pending -> action.candidate
        is PairingAction.RequestUnconfirmed -> action.candidate
        is PairingAction.Retryable -> action.candidate
        is PairingAction.RateLimited -> action.candidate
        is PairingAction.Rejected -> action.candidate
        is PairingAction.Expired -> action.candidate
        is PairingAction.NeedsAttention -> action.candidate
        is PairingAction.InProgress -> action.candidate
        else -> null
    }
    candidate?.let { MonoValue(it.endpoint) }
    val code = candidate?.verificationCode
    if (candidate?.variant == PairingVariant.VERIFICATION && !code.isNullOrBlank()) {
        Kicker(stringResource(R.string.ui_compare_code), Modifier.padding(top = 12.dp), accent = true)
        CodeDisplay(code)
        Text(stringResource(R.string.ui_pairing_instruction), style = MaterialTheme.typography.bodyLarge,
            color = Color(palette.textSoft.toInt()), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }
    val (message, tone, glyph) = when (action) {
        is PairingAction.Pending, is PairingAction.Ready, is PairingAction.InProgress ->
            Triple(stringResource(R.string.ui_waiting_for_approval), StatusTone.PENDING, StatusGlyph.PENDING)
        is PairingAction.RateLimited -> Triple("Please wait before checking again", StatusTone.UNKNOWN, StatusGlyph.CLOCK)
        is PairingAction.RequestUnconfirmed -> Triple(stringResource(R.string.ui_pairing_not_confirmed), StatusTone.WARNING, StatusGlyph.QUESTION)
        is PairingAction.Rejected -> Triple(stringResource(R.string.ui_pairing_declined), StatusTone.BLOCKED, StatusGlyph.BLOCKED)
        is PairingAction.Expired -> Triple(stringResource(R.string.ui_pairing_expired), StatusTone.BLOCKED, StatusGlyph.CLOCK)
        is PairingAction.ReplacementReady -> Triple("New pairing approved; review replacement", StatusTone.GOOD, StatusGlyph.CHECK)
        is PairingAction.Approved -> Triple("Pairing approved", StatusTone.GOOD, StatusGlyph.CHECK)
        else -> Triple("Pairing needs attention", StatusTone.WARNING, StatusGlyph.INFO)
    }
    StatusLine(message, tone, glyph = glyph)
    if (candidate?.variant == PairingVariant.VERIFICATION && !code.isNullOrBlank()) {
        StatusLine(stringResource(R.string.ui_pairing_codes_differ), StatusTone.UNKNOWN, glyph = StatusGlyph.INFO)
    }
    candidate?.requestedScopes?.takeIf { it.isNotEmpty() }?.let { scopes ->
        Kicker(stringResource(R.string.ui_requested_access), Modifier.padding(top = 8.dp))
        ScopeBadges(scopes)
    }
    if (action is PairingAction.Pending || action is PairingAction.RateLimited) {
        ActionRow(stringResource(R.string.ui_check_status), enabled = !busy, icon = R.drawable.ic_replay, onClick = onCheckAgain)
    }
    GhostAction(stringResource(R.string.ui_cancel_pairing_request), enabled = !busy, icon = R.drawable.ic_close, onClick = onCancel)
}

@Composable
fun CodeDisplay(digits: String) {
    val palette = LocalCompanionPalette.current
    val safe = digits.filter(Char::isDigit).take(8)
    if (safe.length != 8) return
    val first = safe.take(4)
    val second = safe.takeLast(4)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val announced = safe.chunked(4).joinToString(", ") { group -> group.toList().joinToString(" ") }
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = announced }) {
        val groups: @Composable () -> Unit = {
            Text(first, style = CompanionCodeStyle, color = Color(palette.blue.toInt()),
                maxLines = 1, softWrap = false)
            Text(second, style = CompanionCodeStyle, color = Color(palette.blue.toInt()),
                maxLines = 1, softWrap = false)
        }
        val firstWidth = textMeasurer.measure(AnnotatedString(first), style = CompanionCodeStyle,
            maxLines = 1, softWrap = false).size.width
        val secondWidth = textMeasurer.measure(AnnotatedString(second), style = CompanionCodeStyle,
            maxLines = 1, softWrap = false).size.width
        val availableWidth = with(density) { maxWidth.toPx() }
        val gap = with(density) { 16.dp.toPx() }
        // Robolectric and some font resolvers report unscaled intrinsic widths for variable fonts.
        // Keep the conservative 44 sp envelope so 200% type never stays on a clipped single row.
        val scaledCodeEnvelope = with(density) { (250f * fontScale).dp.toPx() }
        if (maxOf(firstWidth + secondWidth + gap, scaledCodeEnvelope) > availableWidth) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) { groups() }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(first, style = CompanionCodeStyle, color = Color(palette.blue.toInt()),
                    maxLines = 1, softWrap = false)
                Spacer(Modifier.width(16.dp))
                Text(second, style = CompanionCodeStyle, color = Color(palette.blue.toInt()),
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
fun ScannerScreen(cameraGranted: Boolean, onRequestCamera: () -> Unit,
                  onPayload: (String) -> Unit, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val palette = LocalCompanionPalette.current
    var pastedPayload by remember { mutableStateOf("") }
    Text(stringResource(R.string.ui_scan_qr_code), style = MaterialTheme.typography.headlineSmall,
        color = Color(palette.text.toInt()))
    if (!cameraGranted) {
        StatusLine("Camera permission required to scan", StatusTone.WARNING, glyph = StatusGlyph.LOCK)
        PrimaryAction("Allow camera", icon = R.drawable.ic_phone, onClick = onRequestCamera)
    } else {
        val scanner = remember(context) { CameraQrScanner(context, onPayload, onFailure) }
        DisposableEffect(scanner) { onDispose { scanner.close() } }
        AndroidView(factory = { viewContext ->
            SurfaceView(viewContext).also { view ->
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            runCatching { scanner.start(holder.surface) }.onFailure { onFailure("Camera is unavailable") }
                        }
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            }
        }, modifier = Modifier.fillMaxWidth().height(240.dp))
    }
    Kicker("Or paste a pairing payload")
    OutlinedTextField(pastedPayload, onValueChange = { pastedPayload = it.take(4_096) },
        label = { Text("Pairing payload") }, modifier = Modifier.fillMaxWidth())
    ActionRow("Use pairing payload", enabled = pastedPayload.isNotBlank(), icon = R.drawable.ic_key,
        reason = "Paste a pairing payload first",
        onClick = { val value = pastedPayload; pastedPayload = ""; onPayload(value) })
}
