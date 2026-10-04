package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayRecoveryProfile
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Power and Display controls follow the pairing's current scopes: the host's owner can allow or withdraw each at any
 * time, so a pairing without a scope sees that control disabled with the owner's reason while the screen, the status and
 * the journal stay. Rendered by the real screens; the scopes come from the vendored host fixtures through the real codec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostScopeGatingTest {
    @get:Rule val compose = createComposeRule()

    private val power = "The host owner has not allowed Power for this pairing."
    private val display = "The host owner has not allowed Display for this pairing."

    private val full = reported("sunshine-stopped")
    private val viewOnly = reported("view-only-credential")
    private val displayOnly = reported("scopes-changed-by-owner")
    private val noPower = listOf("status.read", "display.control")
    private val noDisplay = listOf("status.read", "power.control")

    // --- Power ---------------------------------------------------------------------------------------------------

    private fun showPower(scopes: List<String>) {
        val host = host(scopes)
        val status = observed(host, status("sunshine-stopped"), outputs("available"))
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PowerScreen(host, "Deck", actionContext(host, null, status), null, status, NOW, busy = false,
                        feedback = null, onConfirm = {}, onCheckOperation = {})
                }
            }
        }
    }

    private fun assertPowerWithdrawn(scopes: List<String>) {
        showPower(scopes)
        for (title in listOf("Suspend", "Restart", "Shut down")) {
            compose.onNodeWithContentDescription("$title. Unavailable: $power").assertIsNotEnabled()
        }
        compose.onAllNodesWithText(power).assertCountEquals(3)
        // The screen itself stays: only the controls are disabled.
        compose.onNodeWithText("POWER OPTIONS", ignoreCase = true).assertExists()
    }

    @Test fun powerActionsNeedPowerControlAndSayWhoDecidedForAViewOnlyPairing() = assertPowerWithdrawn(viewOnly)

    @Test fun powerActionsNeedPowerControlWhenOnlyDisplayWasAllowed() = assertPowerWithdrawn(noPower)

    @Test fun powerActionsNeedPowerControlWhenTheHostSaidNothingIsAllowed() = assertPowerWithdrawn(emptyList())

    @Test fun powerActionsAreEnabledWhenThePairingHoldsPowerControl() {
        showPower(full)
        for (title in listOf("Suspend", "Restart", "Shut down")) compose.onNodeWithContentDescription(title).assertIsEnabled()
        compose.onAllNodesWithText(power).assertCountEquals(0)
    }

    @Test fun powerWithoutDisplayControlStillWorks() {
        showPower(noDisplay)
        compose.onNodeWithContentDescription("Suspend").assertIsEnabled()
    }

    private fun showSunshine(scopes: List<String>) {
        val host = host(scopes)
        val read = status("sunshine-stopped")
        val status = observed(host, read, outputs("available"))
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SunshineScreen(read.sunshineState, actionContext(host, null, status), host, null, status, NOW,
                        busy = false, feedback = null, onRecover = {}, onCheckOperation = {})
                }
            }
        }
    }

    @Test fun sunshineRecoveryNeedsPowerControlToo() {
        showSunshine(viewOnly)
        compose.onNodeWithContentDescription("Recover Sunshine. Unavailable: $power").assertIsNotEnabled()
    }

    @Test fun sunshineRecoveryIsEnabledWithPowerControl() {
        showSunshine(full)
        compose.onNodeWithContentDescription("Recover Sunshine").assertIsEnabled()
    }

    // --- Display -------------------------------------------------------------------------------------------------

    private fun showDisplay(
        scopes: List<String>, outputs: VersionedPayload.DisplayOutputs = outputs("with-profile"),
        order: VersionedPayload.DisplayOrder? = null, state: PersistentState? = null,
        timing: ResponseTiming? = null, profiles: List<DisplayRecoveryProfile>? = null,
    ): ActiveHost {
        val host = host(scopes, profiles)
        val status = observed(host, status("sunshine-stopped"), outputs, order, timing)
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DisplayScreen(host, state, status, NOW, busy = false, feedback = null, onPreview = {}, onRestore = {},
                        onRevert = {}, onKeep = {}, onSaveCurrent = {}, onSaveOrder = { _, _ -> }, onSaveOrderAndRestart = {},
                        onResetOrder = {}, onCheckOperation = {})
                }
            }
        }
        return host
    }

    private fun chooseTargetMode() {
        compose.onNodeWithText("3840 × 2160 · 60 Hz").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertPreviewWithdrawn(scopes: List<String>) {
        showDisplay(scopes)
        chooseTargetMode()
        compose.onNodeWithText("Preview 3840 × 2160 · 60 Hz").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(display).performScrollTo().assertExists()
    }

    @Test fun previewNeedsDisplayControlForAViewOnlyPairing() = assertPreviewWithdrawn(viewOnly)

    @Test fun previewNeedsDisplayControlWhenOnlyPowerWasAllowed() = assertPreviewWithdrawn(noDisplay)

    @Test fun previewIsEnabledWithDisplayControlWhateverPowerControlIs() {
        showDisplay(noPower)
        chooseTargetMode()
        compose.onNodeWithText("Preview 3840 × 2160 · 60 Hz").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText(display).assertCountEquals(0)
    }

    @Test fun savedModeRestoreAndSaveCurrentNeedDisplayControl() {
        showDisplay(viewOnly)
        compose.onNodeWithText("SAVED").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Restore saved display. Unavailable: $display").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Save current display as recovery mode. Unavailable: $display")
            .performScrollTo().assertIsNotEnabled()
    }

    @Test fun savedModeRestoreAndSaveCurrentAreEnabledWithDisplayControl() {
        showDisplay(displayOnly)
        compose.onNodeWithText("SAVED").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Restore saved display").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Save current display as recovery mode").performScrollTo().assertIsEnabled()
    }

    @Test fun everyOrderActionNeedsDisplayControl() {
        showDisplay(viewOnly, order = order("saved-order"))
        compose.onNodeWithText("DISPLAY ORDER").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(display).performScrollTo().assertExists()
        compose.onNodeWithText("Save for next session").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save and restart Gaming Mode").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Use automatic order").performScrollTo().assertIsNotEnabled()
    }

    @Test fun everyOrderActionIsEnabledWithDisplayControl() {
        showDisplay(displayOnly, order = order("saved-order"))
        compose.onNodeWithText("DISPLAY ORDER").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(display).assertCountEquals(0)
        compose.onNodeWithText("Save for next session").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Save and restart Gaming Mode").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Use automatic order").performScrollTo().assertIsEnabled()
    }

    // --- an active preview: Keep and Revert are display requests too ------------------------------------------------

    private fun ownedPreview(host: ActiveHost): PersistentState {
        val preview = checkNotNull(outputs("with-active-preview").preview)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-1"), "output_id" to JsonValue.StringValue(preview.outputId),
                "mode_id" to JsonValue.StringValue(preview.targetMode.id),
                "generation" to JsonValue.NumberValue(preview.generation.toString())), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(checkNotNull(request.body)), 100)
            .copy(phase = JournalPhase.OBSERVED_RETURN, operationId = preview.id)
        return PersistentState(activeHost = host, journal = listOf(entry))
    }

    /** A Date header 20 seconds before the preview's host deadline, so Keep has time left. */
    private fun previewTiming(): ResponseTiming {
        val deadline = checkNotNull(outputs("with-active-preview").preview).deadlineSeconds.substringBefore('.').toLong()
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(deadline - 20).atZone(ZoneOffset.UTC))
        return ResponseTiming(1_000_000_000L, 1_100_000_000L, date)
    }

    @Test fun keepAndRevertAreDisabledForAPreviewThePairingCanNoLongerConfirm() {
        val host = host(viewOnly)
        showDisplay(viewOnly, outputs = outputs("with-active-preview"), state = ownedPreview(host), timing = previewTiming())
        compose.onNodeWithContentDescription("Revert now. Unavailable: $display").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Keep and save").performScrollTo().assertIsNotEnabled()
        // Both controls say why: the Revert row and the line under Keep.
        compose.onAllNodesWithText(display).assertCountEquals(2)
    }

    @Test fun keepAndRevertAreEnabledForAnOwnedPreviewWithDisplayControl() {
        val host = host(displayOnly)
        showDisplay(displayOnly, outputs = outputs("with-active-preview"), state = ownedPreview(host), timing = previewTiming())
        compose.onNodeWithContentDescription("Revert now").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Keep and save").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText(display).assertCountEquals(0)
    }

    // --- the overview -------------------------------------------------------------------------------------------

    private fun showOverview(scopes: List<String>, outputs: VersionedPayload.DisplayOutputs = outputs("available"),
                             owned: Boolean = false) {
        val profile = DisplayRecoveryProfile("profile-fixture0001xxxxxxxxxxxxx", "1", DisplayModeSummary("2", 3840, 2160, "60"))
        val host = host(scopes, listOf(profile))
        val state = if (owned) ownedPreview(host) else PersistentState(activeHost = host)
        val status = observed(host, status("sunshine-stopped"), outputs, timing = if (owned) previewTiming() else null)
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OverviewScreen(host, state, status, "Deck", NOW, busy = false, onNavigate = {}, onRestore = {},
                        onRevert = {}, onKeep = {}, onCheckStatus = {}, onCheckOperation = {})
                }
            }
        }
    }

    @Test fun theOverviewRestoreRowNeedsDisplayControlButTheScreenStays() {
        showOverview(viewOnly)
        compose.onNodeWithContentDescription("Restore saved display. Unavailable: $display").assertIsNotEnabled()
        // Nothing is hidden: the other rows, and Connection details with its journal, remain reachable.
        compose.onNodeWithContentDescription("Power options").assertIsEnabled()
        compose.onNodeWithContentDescription("Display settings").assertIsEnabled()
        compose.onNodeWithContentDescription("Connection details").assertIsEnabled()
    }

    @Test fun theOverviewRestoreRowIsEnabledWithDisplayControl() {
        showOverview(displayOnly)
        compose.onNodeWithContentDescription("Restore saved display").assertIsEnabled()
    }

    @Test fun theOverviewPreviewCardNeedsDisplayControl() {
        showOverview(viewOnly, outputs("with-active-preview"), owned = true)
        compose.onNodeWithContentDescription("Revert now. Unavailable: $display").assertIsNotEnabled()
        compose.onNodeWithText("Keep and save").assertIsNotEnabled()
    }

    @Test fun theOverviewPreviewCardIsEnabledWithDisplayControl() {
        showOverview(displayOnly, outputs("with-active-preview"), owned = true)
        compose.onNodeWithContentDescription("Revert now").assertIsEnabled()
        compose.onNodeWithText("Keep and save").assertIsEnabled()
    }

    // --- a host that cannot be reached is not a removed pairing ------------------------------------------------------

    @Test fun aRefusedOrTimedOutConnectionKeepsItsWordingAndNeverSaysThePairingWasRemoved() {
        // The owner locking remote access closes the host's listener: this looks exactly like a host that is off.
        val host = host(full)
        val unreachable = StatusPollState(unreachable = true, error = "/v1/status: CONNECT")
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OverviewScreen(host, PersistentState(activeHost = host), unreachable, "Deck", NOW, busy = false,
                        onNavigate = {}, onRestore = {}, onRevert = {}, onKeep = {}, onCheckStatus = {}, onCheckOperation = {})
                }
            }
        }
        compose.onNodeWithText("Can't reach Deck", substring = true).assertExists()
        listOf("removed", "revoked", "no longer", "unpair", "forgot", "access was").forEach { claim ->
            compose.onAllNodesWithText(claim, substring = true, ignoreCase = true).assertCountEquals(0)
        }
        // The pairing is still there and still usable the moment the host answers again.
        compose.onNodeWithContentDescription("Connection details").assertIsEnabled()
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun host(scopes: List<String>, profiles: List<DisplayRecoveryProfile>? = null) = ActiveHost(
        hostId = "host-fixture0001xxxxxxxxxxxxx", clientId = "client-android", endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        grantedScopes = scopes, cachedRecoveryProfiles = profiles,
        wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"), pairedAtMillis = 1_780_000_000_000,
    )

    private fun observed(
        host: ActiveHost, status: VersionedPayload.Status, outputs: VersionedPayload.DisplayOutputs,
        order: VersionedPayload.DisplayOrder? = null, timing: ResponseTiming? = null,
    ) = StatusPollState(
        lastRead = PairedStatusRead(status, outputs, order, outputsTiming = timing),
        lastSuccessMonotonicNanos = 1_100_000_000L, source = StatusObservationSource.from(host),
    )

    private fun reported(name: String): List<String> = checkNotNull(status(name).credentialScopes)

    private fun status(name: String) = payload(ApiRoute.STATUS, "status/$name") as VersionedPayload.Status
    private fun outputs(name: String) = payload(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/$name") as VersionedPayload.DisplayOutputs
    private fun order(name: String) = payload(ApiRoute.DISPLAY_ORDER, "display-order-get/$name") as VersionedPayload.DisplayOrder

    private fun payload(route: ApiRoute, name: String): VersionedPayload {
        val fixture = BoundedJson.parseObject(File("../protocol/fixtures/contract/$name.json").also {
            check(it.isFile) { "vendored fixture not found at ${it.absolutePath}" }
        }.readBytes())
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }

    private companion object { const val NOW = 2_000_000_000L }
}
