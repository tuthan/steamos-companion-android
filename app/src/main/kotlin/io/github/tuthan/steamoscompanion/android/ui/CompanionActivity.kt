package io.github.tuthan.steamoscompanion.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import io.github.tuthan.steamoscompanion.R
import io.github.tuthan.steamoscompanion.android.CompanionApplication
import io.github.tuthan.steamoscompanion.android.CompanionRuntime
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPrompt
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.DisplayRecoveryPresentation
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.OperationLanePresentation
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusHeadline
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.PowerConfirmation
import io.github.tuthan.steamoscompanion.core.client.DisplaySelection
import io.github.tuthan.steamoscompanion.core.client.KeepPreviewConfirmation
import io.github.tuthan.steamoscompanion.core.client.SaveCurrentConfirmation
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.wake.WakeAttemptState

/** The single launch surface. Network actions are attached to this foreground graph. */
class CompanionActivity : ComponentActivity() {
    private val runtime: CompanionRuntime get() = (application as CompanionApplication).runtime
    private lateinit var navigation: CompanionNavigationViewModel
    private var host by mutableStateOf<ActiveHost?>(null)
    private var persisted by mutableStateOf<PersistentState?>(null)
    private var status by mutableStateOf(StatusPollState())
    private var wake by mutableStateOf<WakeAttemptState?>(null)
    private var pairedSession: PairedGraphSession? = null
    private lateinit var phase08: Phase08ActionViewModel
    private lateinit var displayActions: DisplayActionViewModel
    private lateinit var phase10: Phase10ActionViewModel
    private lateinit var pairingFlow: PairingFlowViewModel
    private var foreground = false
    private var cameraGranted by mutableStateOf(false)
    private var permissionMessage by mutableStateOf<String?>(null)
    private var pendingLanAction: (() -> Unit)? = null
    private val lanPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingLanAction
        pendingLanAction = null
        if (granted) action?.invoke() else permissionMessage = "Local network permission required"
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }
    private val diagnosticDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) phase10.export(uri, status)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = runtime.stateRepository.load() as? StateLoadResult.Ready
        persisted = loaded?.state
        host = loaded?.state?.activeHost
        navigation = ViewModelProvider(this, SavedStateViewModelFactory(application, this))
            .get(CompanionNavigationViewModel::class.java)
        phase08 = ViewModelProvider(this, Phase08ActionViewModel.Factory(runtime))
            .get(Phase08ActionViewModel::class.java)
        displayActions = ViewModelProvider(this, DisplayActionViewModel.Factory(runtime))
            .get(DisplayActionViewModel::class.java)
        phase10 = ViewModelProvider(this, Phase10ActionViewModel.Factory(runtime))
            .get(Phase10ActionViewModel::class.java)
        pairingFlow = ViewModelProvider(this, PairingFlowViewModel.Factory(runtime))
            .get(PairingFlowViewModel::class.java)
        cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        navigation.initialize(host != null)
        pairedSession = host?.let(::createPairedSession)
        if (loaded?.state?.candidate != null) pairingFlow.resume()
        setContent {
            CompanionTheme {
                val route = navigation.route
                LaunchedEffect(route.destination) {
                    if (route.destination == UiDestination.PAIRING || route.destination == UiDestination.SCANNER) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    if (route.destination != UiDestination.WAKE) pairedSession?.clearWakeObservation()
                    if (route.destination != UiDestination.PAIRING) pairingFlow.stopPolling()
                    else if (foreground) pairingFlow.startPolling()
                    if (route.destination != UiDestination.DISCOVERY) pairingFlow.cancelScan()
                }
                LaunchedEffect(pairingFlow.pairing) {
                    when (pairingFlow.pairing) {
                        is PairingAction.Approved -> {
                            reloadPersistentState()
                            navigation.paired()
                        }
                        is PairingAction.ReplacementReady -> {
                            reloadPersistentState()
                            navigation.paired()
                            navigation.navigate(UiDestination.CONNECTION_DETAILS)
                        }
                        is PairingAction.Pending, is PairingAction.RequestUnconfirmed,
                        is PairingAction.RateLimited -> navigation.showPairing()
                        is PairingAction.Cancelled -> if (navigation.route.destination == UiDestination.PAIRING) {
                            if (!navigation.back()) navigation.unpaired()
                        }
                        else -> Unit
                    }
                }
                LaunchedEffect(phase08.feedback?.revision) {
                    if (phase08.feedback != null) {
                        reloadPersistentState()
                        pairedSession?.refresh()
                    }
                }
                LaunchedEffect(displayActions.feedback?.revision) {
                    if (displayActions.feedback != null) {
                        reloadPersistentState()
                        pairedSession?.refresh()
                    }
                }
                LaunchedEffect(phase10.feedback?.revision) {
                    if (phase10.feedback != null) reloadPersistentState()
                }
                BackHandler(navigation.canGoBack) { navigation.back() }
                CompanionRoot(route.destination, host, persisted, status, wake, runtime,
                    busy = phase08.busy, feedback = phase08.feedback,
                    displayBusy = displayActions.busy, displayFeedback = displayActions.feedback,
                    phase10Busy = phase10.busy, phase10Feedback = phase10.feedback,
                    pairingFlow = pairingFlow, cameraGranted = cameraGranted,
                    permissionMessage = permissionMessage,
                    onBack = { navigation.back() },
                    onNavigate = { destination ->
                        if (destination == UiDestination.DISCOVERY) withLocalNetworkAccess {
                            navigation.navigate(destination)
                            pairingFlow.scan()
                        } else navigation.navigate(destination)
                    },
                    onPowerConfirm = { confirmation ->
                        host?.let { phase08.power(it, confirmation, status) }
                    },
                    onWake = {
                        host?.let { pairedHost ->
                            phase08.wake(pairedHost) { packet ->
                                if (foreground && navigation.route.destination == UiDestination.WAKE) {
                                    pairedSession?.beginWakeObservation(packet)
                                }
                            }
                        }
                    },
                    onSunshine = { host?.let { phase08.recoverSunshine(it, status) } },
                    onPreview = { selection -> host?.let { displayActions.preview(it, status, selection) } },
                    onRestore = { profileId -> host?.let { displayActions.restoreSaved(it, status, profileId) } },
                    onRevert = { host?.let { displayActions.revert(it, status) } },
                    onKeep = { confirmation -> host?.let { displayActions.keep(it, status, confirmation) } },
                    onSaveCurrent = { confirmation -> host?.let { displayActions.saveCurrent(it, status, confirmation) } },
                    onPairAnother = { navigation.navigate(UiDestination.ONBOARDING) },
                    onConfirmReplacement = { expected, generation -> phase10.confirmReplacement(expected, generation) },
                    onDiscardReplacement = { generation -> phase10.discardReplacement(generation) },
                    onRevoke = { host?.let(phase10::revoke) },
                    onForget = { host?.let(phase10::forgetOnThisDevice) },
                    onExport = { diagnosticDocument.launch("steamos-companion-diagnostics.json") },
                    onDiscoveryRefresh = { withLocalNetworkAccess { pairingFlow.scan() } },
                    onDiscoverySelect = { candidate ->
                        pairingFlow.select(candidate)
                        navigation.navigate(UiDestination.CANDIDATE_DETAILS)
                    },
                    onManualProbe = { endpoint -> withLocalNetworkAccess { pairingFlow.probeManual(endpoint) } },
                    onRequestPairing = { withLocalNetworkAccess { pairingFlow.startNonce() } },
                    onPairingCancel = { pairingFlow.cancelPairing() },
                    onPairingCheck = { pairingFlow.checkNow() },
                    onCheckStatus = { withLocalNetworkAccess { pairedSession?.refresh() } },
                    onCheckOperation = { pairedSession?.reviewOperations() },
                    onPayload = { payload -> withLocalNetworkAccess { pairingFlow.startPayload(payload) } },
                    onRequestCamera = { cameraPermission.launch(Manifest.permission.CAMERA) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        pairedSession?.onStart()
        if (::pairingFlow.isInitialized && navigation.route.destination == UiDestination.PAIRING) pairingFlow.startPolling()
    }

    override fun onStop() {
        foreground = false
        if (::pairingFlow.isInitialized) pairingFlow.stopPolling()
        pairedSession?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        pairedSession?.close()
        super.onDestroy()
    }

    private fun reloadPersistentState() {
        val state = (runtime.stateRepository.load() as? StateLoadResult.Ready)?.state
        persisted = state
        val current = host
        val next = state?.activeHost
        if (current == next) return
        val sameCredential = current != null && next != null &&
            StatusObservationSource.from(current) == StatusObservationSource.from(next)
        if (!sameCredential) {
            pairedSession?.close()
            pairedSession = next?.let(::createPairedSession)
            status = StatusPollState()
            wake = null
            if (foreground) pairedSession?.onStart()
            if (next == null) navigation.unpaired() else navigation.paired()
        }
        host = next
    }

    private fun createPairedSession(pairedHost: ActiveHost) = PairedGraphSession(this, pairedHost, runtime,
        onState = { status = it; reloadPersistentState() },
        onWakeInterfaceChanged = { reloadPersistentState() },
        onWakeState = { wake = it },
        onOperationReview = { _, _ -> reloadPersistentState() })

    private fun withLocalNetworkAccess(action: () -> Unit) {
        pendingLanAction = action
        val plan = runtime.localNetworkAccess.requestForUserInitiatedAction(
            requestPermission = { lanPermission.launch(it) },
            openAppSettings = {
                pendingLanAction = null
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            },
        )
        if (plan.prompt == LocalNetworkPermissionPrompt.None) {
            pendingLanAction = null
            action()
        }
    }
}

@Composable
private fun CompanionRoot(
    destination: UiDestination,
    host: ActiveHost?,
    persisted: PersistentState?,
    status: StatusPollState,
    wake: WakeAttemptState?,
    runtime: CompanionRuntime,
    busy: Boolean,
    feedback: ActionFeedback?,
    displayBusy: Boolean,
    displayFeedback: ActionFeedback?,
    phase10Busy: Boolean,
    phase10Feedback: ActionFeedback?,
    pairingFlow: PairingFlowViewModel,
    cameraGranted: Boolean,
    permissionMessage: String?,
    onBack: () -> Unit,
    onNavigate: (UiDestination) -> Unit,
    onPowerConfirm: (PowerConfirmation) -> Unit,
    onWake: () -> Unit,
    onSunshine: () -> Unit,
    onPreview: (DisplaySelection) -> Unit,
    onRestore: (String) -> Unit,
    onRevert: () -> Unit,
    onKeep: (KeepPreviewConfirmation) -> Unit,
    onSaveCurrent: (SaveCurrentConfirmation) -> Unit,
    onPairAnother: () -> Unit,
    onConfirmReplacement: (ActiveHost, Long) -> Unit,
    onDiscardReplacement: (Long) -> Unit,
    onRevoke: () -> Unit,
    onForget: () -> Unit,
    onExport: () -> Unit,
    onDiscoveryRefresh: () -> Unit,
    onDiscoverySelect: (UnverifiedDiscoveryCandidate) -> Unit,
    onManualProbe: (String) -> Unit,
    onRequestPairing: () -> Unit,
    onPairingCancel: () -> Unit,
    onPairingCheck: () -> Unit,
    onCheckStatus: () -> Unit,
    onCheckOperation: () -> Unit,
    onPayload: (String) -> Unit,
    onRequestCamera: () -> Unit,
) {
    val palette = LocalCompanionPalette.current
    val onboarding = destination == UiDestination.ONBOARDING ||
        (destination == UiDestination.REMOTE_OVERVIEW && host == null)
    // The onboarding backdrop is the hero navy; every other destination sits on the plain body colour.
    val backdrop = if (onboarding) Modifier.background(Color(palette.bg.toInt()))
        .background(Brush.verticalGradient(0f to Color(palette.navy.toInt()), .75f to Color(palette.bg.toInt())))
    else Modifier.background(Color(palette.bg.toInt()))
    Column(Modifier.fillMaxSize().then(backdrop)) {
        if (!onboarding) {
            CompanionAppBar(stringResource(R.string.app_name),
                onBack = if (destination == UiDestination.REMOTE_OVERVIEW) null else onBack)
        }
        val contentInsets = if (onboarding) WindowInsets.safeDrawing
            else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(contentInsets), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth().widthIn(max = CompanionSpacing.MAX_CONTENT_WIDTH_DP.dp)
            .verticalScroll(rememberScrollState())
            .padding(CompanionSpacing.SCREEN_GUTTER_DP.dp),
            verticalArrangement = Arrangement.spacedBy(CompanionSpacing.ROW_GROUP_DP.dp)) {
            when (destination) {
                UiDestination.ONBOARDING -> OnboardingScreen(onNavigate, permissionMessage)
                UiDestination.DISCOVERY -> DiscoveryScreen(pairingFlow, onDiscoveryRefresh,
                    onCancel = pairingFlow::cancelScan,
                    onSelect = onDiscoverySelect,
                    onManual = { onNavigate(UiDestination.MANUAL_ADDRESS) })
                UiDestination.MANUAL_ADDRESS -> ManualAddressScreen(pairingFlow, onManualProbe,
                    onFound = { onNavigate(UiDestination.CANDIDATE_DETAILS) })
                UiDestination.CANDIDATE_DETAILS -> CandidateDetailsScreen(pairingFlow.selected,
                    pairingFlow.busy, onRequestPairing)
                UiDestination.PAIRING -> PairingScreen(pairingFlow.pairing, pairingFlow.busy,
                    onPairingCancel, onPairingCheck)
                UiDestination.SCANNER -> ScannerScreen(cameraGranted, onRequestCamera, onPayload,
                    onFailure = { /* Scanner failure remains local; user can paste or retry. */ })
                UiDestination.REMOTE_OVERVIEW -> {
                    if (host == null) OnboardingScreen(onNavigate, permissionMessage)
                    else OverviewScreen(persisted?.activeHost ?: host, persisted, status, runtime,
                        displayBusy, onNavigate, onRestore, onRevert, onKeep, onCheckStatus,
                        onCheckOperation)
                }
                UiDestination.DISPLAY -> if (host != null) DisplayScreen(
                    persisted?.activeHost ?: host, persisted, status, runtime.clock.monotonicNanos(),
                    displayBusy, displayFeedback, onPreview, onRestore, onRevert, onKeep, onSaveCurrent,
                    onCheckOperation)
                UiDestination.POWER -> if (host != null) PowerScreen(host,
                    runtime.aliasStore.get(host) ?: host.endpoint, actionContext(host, persisted, status),
                    persisted, status, runtime.clock.monotonicNanos(), busy, feedback,
                    onPowerConfirm, onCheckOperation)
                UiDestination.WAKE -> if (host != null) WakeScreen(wake, host.wakeTarget.available, busy, onWake)
                UiDestination.SUNSHINE -> if (host != null) SunshineScreen(
                    status.lastRead?.status?.sunshineState, actionContext(host, persisted, status),
                    host, persisted, status, runtime.clock.monotonicNanos(), busy, feedback,
                    onSunshine, onCheckOperation)
                UiDestination.CONNECTION_DETAILS -> if (host != null) ConnectionDetailsScreen(
                    persisted?.activeHost ?: host, runtime.aliasStore.get(host) ?: host.endpoint,
                    persisted, status, phase10Busy, phase10Feedback, onPairAnother,
                    onConfirmReplacement, onDiscardReplacement, onRevoke, onForget, onExport)
                else -> {
                    Text(destination.wireName.replace('_', ' '), style = MaterialTheme.typography.headlineSmall,
                        color = Color(palette.text.toInt()))
                    StatusLine(if (status.reading) "Checking device…" else "Choose a device and check its status",
                        if (status.reading) StatusTone.PENDING else StatusTone.UNKNOWN)
                }
            }
        }
        }
    }
}

private fun actionContext(host: ActiveHost, state: PersistentState?, status: StatusPollState): ActionContext {
    val read = status.lastRead?.takeIf { status.source == StatusObservationSource.from(host) }
    val preview = read?.outputs?.preview
    val owner = when {
        read?.outputs == null -> PreviewOwnership.UNKNOWN
        preview == null -> PreviewOwnership.NONE
        state?.let { OperationLanePresentation.ownsPreview(host, it, preview.id) } == true -> PreviewOwnership.OWNED_HERE
        else -> PreviewOwnership.OTHER_CLIENT
    }
    val freshness = when {
        status.unreachable -> StatusFreshness.UNREACHABLE
        status.stale || (status.lastRead != null && read == null) -> StatusFreshness.STALE
        read != null -> StatusFreshness.FRESH
        else -> StatusFreshness.NEVER_CHECKED
    }
    return ActionContext(
        hasActiveHost = true,
        grantedScopes = host.grantedScopes.toSet(),
        statusFreshness = freshness,
        capabilities = read?.status?.capabilities.orEmpty(),
        wakeTargetConfigured = host.wakeTarget.available,
        savedRecoveryProfileAvailable = host.cachedRecoveryProfiles?.isNotEmpty() == true,
        previewOwnership = owner,
        anotherOperationInProgress = state?.let {
            if (owner == PreviewOwnership.OWNED_HERE && preview != null) {
                OperationLanePresentation.hasUnresolvedOtherThanPreview(host, it, preview.id)
            } else OperationLanePresentation.hasUnresolved(host, it)
        } == true,
        hasUnresolvedOperation = state?.let { OperationLanePresentation.hasUnresolved(host, it) } == true,
        sunshineState = (read?.status?.sunshineState as? WireValue.Known<SunshineState>)?.value,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingScreen(onNavigate: (UiDestination) -> Unit, permissionMessage: String?) {
    val palette = LocalCompanionPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CompanionSpacing.SECTION_GROUP_DP.dp)) {
        Spacer(Modifier.height(CompanionSpacing.SECTION_GROUP_DP.dp))
        BrandLockup(stringResource(R.string.ui_wordmark_strong), stringResource(R.string.ui_wordmark_light))
        Text(stringResource(R.string.ui_connect_remote_device),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = Color(palette.text.toInt()), textAlign = TextAlign.Center)
        Text(stringResource(R.string.ui_connect_remote_device_instructions),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Light),
            color = Color(palette.textSoft.toInt()), textAlign = TextAlign.Center)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.ui_pill_lan_only))
            Pill(stringResource(R.string.ui_pill_pinned_tls))
            Pill(stringResource(R.string.ui_pill_code_never_sent), StatusTone.GOOD)
        }
        Spacer(Modifier.height(CompanionSpacing.SECTION_GROUP_DP.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CompanionSpacing.ROW_GROUP_DP.dp)) {
            permissionMessage?.let { StatusLine(it, StatusTone.WARNING, glyph = StatusGlyph.LOCK) }
            PrimaryAction(stringResource(R.string.ui_find_devices), icon = R.drawable.ic_radar,
                onClick = { onNavigate(UiDestination.DISCOVERY) })
            GhostAction(stringResource(R.string.ui_scan_qr_code), icon = R.drawable.ic_phone,
                onClick = { onNavigate(UiDestination.SCANNER) })
            ActionRow(stringResource(R.string.ui_enter_address), navigates = true,
                onClick = { onNavigate(UiDestination.MANUAL_ADDRESS) })
        }
    }
}

@Composable
private fun OverviewScreen(host: ActiveHost, persisted: PersistentState?, status: StatusPollState,
                           runtime: CompanionRuntime, busy: Boolean,
                           onNavigate: (UiDestination) -> Unit,
                           onRestore: (String) -> Unit,
                           onRevert: () -> Unit,
                           onKeep: (KeepPreviewConfirmation) -> Unit,
                           onCheckStatus: () -> Unit,
                           onCheckOperation: () -> Unit) {
    val palette = LocalCompanionPalette.current
    var keepConfirmation by remember(StatusObservationSource.from(host)) {
        mutableStateOf<KeepPreviewConfirmation?>(null)
    }
    val name = runtime.aliasStore.get(host) ?: host.endpoint
    val (headline, tone, glyph) = when (status.headline) {
        StatusHeadline.CONNECTED -> Triple(stringResource(R.string.ui_status_connected_now), StatusTone.GOOD, StatusGlyph.DOT)
        StatusHeadline.STALE -> Triple("Status is out of date", StatusTone.UNKNOWN, StatusGlyph.CLOCK)
        StatusHeadline.UNREACHABLE -> Triple("Can't reach $name", StatusTone.WARNING, StatusGlyph.BLOCKED)
        StatusHeadline.CHECKING -> Triple(stringResource(R.string.ui_checking_device), StatusTone.PENDING, StatusGlyph.PENDING)
    }
    val headlineParts = headline.split(" · ", limit = 2)
    DeviceHeader(name, host.endpoint, headlineParts[0], tone, kicker = stringResource(R.string.ui_remote_device),
        glyph = glyph, detail = headlineParts.getOrNull(1))
    val recovery = persisted?.let {
        DisplayRecoveryPresentation.project(host, it, status, runtime.clock.monotonicNanos())
    } ?: PreviewPresentation.None
    when (recovery) {
        is PreviewPresentation.Active -> PreviewCard(
            mode = "${recovery.preview.targetMode.width} × ${recovery.preview.targetMode.height}",
            remainingSeconds = recovery.remainingSeconds,
            status = recovery.remainingSeconds?.let { "Reverts in $it seconds" } ?: "Checking restoration…",
            canRevert = recovery.revertAvailable && !busy,
            canKeep = recovery.keepAvailable && !busy,
            onRevert = onRevert,
            onKeep = { keepConfirmation = KeepPreviewConfirmation.forPreview(host, recovery.preview) },
            deviceName = name,
            question = stringResource(R.string.ui_preview_question, name))
        is PreviewPresentation.Pending -> {
            StatusLine(stringResource(R.string.ui_result_not_confirmed), StatusTone.WARNING,
                glyph = StatusGlyph.QUESTION, detail = "Check the device")
            val operationId = persisted?.journal?.firstOrNull { it.localGeneration == recovery.localGeneration }?.operationId
            ActionRow("Check result", enabled = operationId != null,
                reason = if (operationId == null) "No operation ID was returned; review the host" else null,
                onClick = onCheckOperation)
        }
        PreviewPresentation.None -> Kicker(stringResource(R.string.ui_recovery))
    }
    val profile = host.cachedRecoveryProfiles?.firstOrNull()
    ActionRow(stringResource(R.string.ui_restore_saved_display),
        secondary = profile?.mode?.let { "${it.width} × ${it.height} · ${it.refreshHz ?: "?"} Hz" },
        enabled = profile != null && !busy && (recovery == PreviewPresentation.None ||
            recovery is PreviewPresentation.Active && recovery.revertAvailable),
        reason = if (profile == null) "No recovery mode saved" else "Resolve the preview or operation first",
        icon = R.drawable.ic_replay, onClick = { profile?.id?.let(onRestore) })
    val recoveryBlockReason = when (recovery) {
        is PreviewPresentation.Active -> "Waiting for this preview to be kept or reverted"
        is PreviewPresentation.Pending -> "Check the unconfirmed display result first"
        PreviewPresentation.None -> null
    }
    ActionRow(stringResource(R.string.ui_wake_device), icon = R.drawable.ic_external, navigates = true,
        enabled = host.wakeTarget.available,
        reason = if (host.wakeTarget.available) null else stringResource(R.string.ui_wake_not_configured),
        onClick = { onNavigate(UiDestination.WAKE) })
    ActionRow("Check device status", icon = R.drawable.ic_radar, onClick = onCheckStatus)
    ActionRow(stringResource(R.string.ui_display_settings), icon = R.drawable.ic_list, navigates = true,
        onClick = { onNavigate(UiDestination.DISPLAY) })
    ActionRow(stringResource(R.string.ui_power_options), icon = R.drawable.ic_play, navigates = true,
        secondary = "Suspend, restart, shut down",
        enabled = recovery == PreviewPresentation.None,
        reason = recoveryBlockReason,
        onClick = { onNavigate(UiDestination.POWER) })
    ActionRow("Sunshine", icon = R.drawable.ic_journal, navigates = true,
        onClick = { onNavigate(UiDestination.SUNSHINE) })
    ActionRow(stringResource(R.string.ui_connection_details), icon = R.drawable.ic_fingerprint, navigates = true,
        secondary = "Identity, permissions, diagnostics",
        onClick = { onNavigate(UiDestination.CONNECTION_DETAILS) })
    keepConfirmation?.let { captured ->
        PictureConfirmationDialog(
            title = "Keep this display preview?",
            body = "Is the picture on $name's display correct right now? The preview deadline still applies.",
            confirmLabel = stringResource(R.string.ui_keep_and_save),
            onDismiss = { keepConfirmation = null },
            onConfirm = { keepConfirmation = null; onKeep(captured) },
        )
    }
}
