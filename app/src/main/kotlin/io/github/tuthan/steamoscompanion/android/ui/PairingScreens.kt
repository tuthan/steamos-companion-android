package io.github.tuthan.steamoscompanion.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.android.scanner.CameraQrScanner
import io.github.tuthan.steamoscompanion.android.scanner.QrScanner
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanResult
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate

@Composable
fun DiscoveryScreen(flow: PairingFlowViewModel, onRefresh: () -> Unit, onCancel: () -> Unit,
                    onSelect: (UnverifiedDiscoveryCandidate) -> Unit,
                    onManual: () -> Unit) {
    DiscoveryContent(flow.scanning, flow.discovery, flow.message, onRefresh, onCancel, onSelect, onManual)
}

/** The discovery screen as a function of what the flow holds, so each outcome can be shown and tested on its own. */
@Composable
internal fun DiscoveryContent(scanning: Boolean, result: DiscoveryScanResult?, message: String?,
                              onRefresh: () -> Unit, onCancel: () -> Unit,
                              onSelect: (UnverifiedDiscoveryCandidate) -> Unit, onManual: () -> Unit) {
    Kicker(stringResource(R.string.ui_discovery_kicker))
    if (scanning) {
        StatusLine(stringResource(R.string.ui_discovery_searching), StatusTone.PENDING, glyph = StatusGlyph.RADAR)
        ProgressStrip(ProgressState.Indeterminate, reduceMotion = rememberReduceMotion())
    }
    if (result != null) {
        if (result.candidates.isEmpty()) {
            // An empty API 36 scan cannot prove the compatibility restriction: say so, and keep Find again and Enter address.
            if (result.qualifiedPermissionFailure) {
                StatusLine(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE, StatusTone.WARNING, glyph = StatusGlyph.INFO)
            } else StatusLine(stringResource(R.string.ui_discovery_no_results), StatusTone.WARNING)
        } else Kicker("Found")
        result.candidates.forEach { candidate ->
            ActionRow(candidate.target.endpoint.authority, monoTitle = true, icon = R.drawable.ic_lock, navigates = true,
                secondary = candidate.name ?: "${stringResource(R.string.ui_identity_prefix)} …${candidate.certificateFingerprint.takeLast(8)}",
                onClick = { onSelect(candidate) })
        }
        if (result.partial) StatusLine(stringResource(R.string.ui_discovery_partial_scan), StatusTone.WARNING)
    }
    message?.let { StatusLine(it, StatusTone.WARNING) }
    if (scanning) GhostAction(stringResource(R.string.ui_cancel_search), icon = R.drawable.ic_close, onClick = onCancel)
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
    Kicker("How pairing works", Modifier.padding(top = 8.dp))
    StepRail(PairingSteps.steps(PairingVariant.VERIFICATION), PairingSteps.activeIndex(PairingVariant.VERIFICATION, null))
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
                  onCheckAgain: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis) {
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
    // The countdown ticks once a second while the request is alive and freezes at Expired; it never goes negative.
    val expiresAt = candidate?.expiresAtMillis
    val counting = PairingExpiry.showsFor(action) && action !is PairingAction.Expired
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(expiresAt, counting) {
        now = nowMillis()
        while (counting && PairingExpiry.view(expiresAt, now) is PairingExpiryView.Counting) {
            delay(CompanionMotion.PREVIEW_COUNTDOWN_TICK_MILLIS.toLong())
            now = nowMillis()
        }
    }
    val expiry = when {
        action is PairingAction.Expired -> PairingExpiryView.Expired
        PairingExpiry.showsFor(action) -> PairingExpiry.view(expiresAt, now)
        else -> null
    }
    if (candidate?.variant == PairingVariant.VERIFICATION && !code.isNullOrBlank()) {
        Kicker(stringResource(R.string.ui_compare_code), Modifier.padding(top = 12.dp), accent = true)
        CodeDisplay(code)
        expiry?.let { PairingExpiryLine(it) }
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
    // The outcome (waiting, approved, declined, expired) is announced once through a polite live region.
    StateSwap(Triple(message, tone, glyph)) { (text, textTone, textGlyph) ->
        StatusLine(text, textTone, glyph = textGlyph, live = true)
    }
    if (candidate?.variant == PairingVariant.VERIFICATION && !code.isNullOrBlank()) {
        StatusLine(stringResource(R.string.ui_pairing_codes_differ), StatusTone.UNKNOWN, glyph = StatusGlyph.INFO)
    } else if (expiry != null) PairingExpiryLine(expiry)
    candidate?.requestedScopes?.takeIf { it.isNotEmpty() }?.let { scopes ->
        Kicker(stringResource(R.string.ui_requested_access), Modifier.padding(top = 8.dp))
        ScopeBadges(scopes)
    }
    // Below the code, which stays the largest thing on the screen with nothing but the identity above it.
    if (candidate != null) {
        Kicker("Pairing steps", Modifier.padding(top = 8.dp))
        StepRail(PairingSteps.steps(candidate.variant), PairingSteps.activeIndex(candidate.variant, action))
    }
    if (action is PairingAction.Pending || action is PairingAction.RateLimited) {
        ActionRow(stringResource(R.string.ui_check_status), enabled = !busy, icon = R.drawable.ic_replay, onClick = onCheckAgain)
    }
    GhostAction(stringResource(R.string.ui_cancel_pairing_request), enabled = !busy, icon = R.drawable.ic_close, onClick = onCancel)
}

/** "Expires in 1:57" under the code; amber for its last ten seconds, then frozen at Expired in red. */
@Composable
internal fun PairingExpiryLine(view: PairingExpiryView) {
    val palette = LocalCompanionPalette.current
    when (view) {
        is PairingExpiryView.Counting -> {
            val color = Color((if (view.urgent) palette.amber else palette.text).toInt())
            Text(countdownText(stringResource(R.string.ui_pairing_expiry, view.text), color),
                modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = color,
                textAlign = TextAlign.Center)
        }
        PairingExpiryView.Expired -> Text(stringResource(R.string.ui_pairing_expired_label),
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge,
            color = Color(palette.red.toInt()), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            textAlign = TextAlign.Center)
    }
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
    // The digits roll from the pale mono blue to the accent blue over 600 ms, once per code, then stay still. With
    // reduce motion on there is no roll: the digits are the accent blue from the first frame.
    val reduceMotion = rememberReduceMotion()
    val reveal = remember(safe) { Animatable(if (reduceMotion) CodeReveal.END else CodeReveal.START) }
    LaunchedEffect(safe, reduceMotion) {
        if (reduceMotion) reveal.snapTo(CodeReveal.END)
        else if (reveal.value < CodeReveal.END) {
            reveal.animateTo(CodeReveal.END, tween(CompanionMotion.CODE_REVEAL_MILLIS, easing = LinearEasing))
        }
    }
    val digitColor = CodeReveal.color(reveal.value, palette)
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = announced }) {
        val groups: @Composable () -> Unit = {
            Text(first, style = CompanionCodeStyle, color = digitColor,
                maxLines = 1, softWrap = false)
            Text(second, style = CompanionCodeStyle, color = digitColor,
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
                Text(first, style = CompanionCodeStyle, color = digitColor,
                    maxLines = 1, softWrap = false)
                Spacer(Modifier.width(16.dp))
                Text(second, style = CompanionCodeStyle, color = digitColor,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

/** The comparison-code reveal (`.q-code.rolling`): `mono` at the start, `blue` at the end, linear in between. */
internal object CodeReveal {
    const val START = 0f
    const val END = 1f

    fun color(progress: Float, palette: CompanionPalette): Color =
        lerp(Color(palette.mono.toInt()), Color(palette.blue.toInt()), progress.coerceIn(START, END))
}

/**
 * The QR scanner. A camera failure is shown here and never swallowed: the owner can try the camera again, or paste the
 * pairing payload below, which completes the same variant without the camera.
 */
@Composable
fun ScannerScreen(cameraGranted: Boolean, onRequestCamera: () -> Unit,
                  onPayload: (String) -> Unit, onFailure: (String) -> Unit = {},
                  scannerFactory: (Context, (String) -> Unit, (String) -> Unit) -> QrScanner =
                      { context, payload, failure -> CameraQrScanner(context, payload, failure) }) {
    val context = LocalContext.current
    val palette = LocalCompanionPalette.current
    var pastedPayload by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    Text(stringResource(R.string.ui_scan_qr_code), modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall, color = Color(palette.text.toInt()))
    val reportFailure: (String) -> Unit = { message -> failure = message; onFailure(message) }
    if (!cameraGranted) {
        StatusLine("Camera permission required to scan", StatusTone.WARNING, glyph = StatusGlyph.LOCK)
        PrimaryAction("Allow camera", icon = R.drawable.ic_phone, onClick = onRequestCamera)
    } else if (failure != null) {
        StatusLine("Couldn't scan with the camera", StatusTone.WARNING, glyph = StatusGlyph.INFO,
            detail = failure, live = true)
        GhostAction(stringResource(R.string.ui_try_again), icon = R.drawable.ic_replay,
            onClick = { failure = null; attempt++ })
    } else {
        val scanner = remember(context, attempt) { scannerFactory(context, onPayload, reportFailure) }
        DisposableEffect(scanner) { onDispose { scanner.close() } }
        AndroidView(factory = { viewContext ->
            SurfaceView(viewContext).also { view ->
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            runCatching { scanner.start(holder.surface) }.onFailure { reportFailure("Camera is unavailable") }
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
