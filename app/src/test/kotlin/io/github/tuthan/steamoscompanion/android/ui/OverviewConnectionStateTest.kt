package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the paired overview says when the device cannot be reached, with and without a display preview running. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverviewConnectionStateTest {
    @get:Rule val compose = createComposeRule()

    private val host = ActiveHost("host-fixture0001xxxxxxxxxxxxx", "client-android", "deck.local:18443",
        "sha256:" + "ab".repeat(32), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        listOf("status.read", "display.control", "power.control"), StoredWakeTarget(false, "", null, null, null),
        1_780_000_000_000)

    private fun payload(route: ApiRoute, name: String): VersionedPayload {
        val fixture = BoundedJson.parseObject(File("../protocol/fixtures/contract/$name.json").readBytes())
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }

    private fun show(state: PersistentState?, status: StatusPollState) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OverviewScreen(host, state, status, "Deck", 2_000_000_000L, busy = false, onNavigate = {}, onRestore = {},
                        onRevert = {}, onKeep = {}, onCheckStatus = {}, onCheckOperation = {})
                }
            }
        }
    }

    private val unreachableWithoutRead = StatusPollState(unreachable = true, source = StatusObservationSource.from(host))

    @Test fun anUnreachableDeviceOnApi36WithoutTheNearbyGrantSaysWhatMayBeNeeded() {
        show(null, unreachableWithoutRead.copy(error = LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE))
        compose.onNodeWithText("Can't reach Deck").assertExists()
        compose.onNodeWithText(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE).assertExists()
    }

    @Test fun anyOtherUnreachableReasonKeepsTheOrdinaryWording() {
        show(null, unreachableWithoutRead.copy(error = "connect timed out"))
        compose.onNodeWithText("Can't reach Deck").assertExists()
        compose.onAllNodesWithText(LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE).assertCountEquals(0)
    }

    // ---- a preview running when the connection drops ---------------------------------------------------------------

    private val outputs get() = payload(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/with-active-preview")
        as VersionedPayload.DisplayOutputs

    private fun ownedPreview(): PersistentState {
        val preview = checkNotNull(outputs.preview)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-1"), "output_id" to JsonValue.StringValue(preview.outputId),
                "mode_id" to JsonValue.StringValue(preview.targetMode.id),
                "generation" to JsonValue.NumberValue(preview.generation.toString())), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(checkNotNull(request.body)), 100)
            .copy(phase = JournalPhase.OBSERVED_RETURN, operationId = preview.id)
        return PersistentState(activeHost = host, journal = listOf(entry))
    }

    private fun previewTiming(): ResponseTiming {
        val deadline = checkNotNull(outputs.preview).deadlineSeconds.substringBefore('.').toLong()
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(deadline - 20).atZone(ZoneOffset.UTC))
        return ResponseTiming(1_000_000_000L, 1_100_000_000L, date)
    }

    private fun previewStatus(unreachable: Boolean) = StatusPollState(
        lastRead = PairedStatusRead(payload(ApiRoute.STATUS, "status/sunshine-stopped") as VersionedPayload.Status, outputs,
            null, outputsTiming = previewTiming()),
        lastSuccessMonotonicNanos = 1_100_000_000L, unreachable = unreachable, source = StatusObservationSource.from(host))

    private val lost = "Connection lost. The device will attempt to restore the previous mode at the deadline."

    @Test fun whenTheConnectionDropsDuringAPreviewTheCardSaysTheDeviceWillRestoreAtTheDeadline() {
        show(ownedPreview(), previewStatus(unreachable = true))
        compose.onNodeWithText(lost).performScrollTo().assertExists()
        // The countdown itself is still the host's deadline; nothing claims the picture was restored.
        compose.onAllNodesWithText("restored", substring = true).assertCountEquals(0)
    }

    @Test fun whileTheDeviceIsReachableThePreviewCardHasNoConnectionNotice() {
        show(ownedPreview(), previewStatus(unreachable = false))
        compose.onNodeWithText("Revert now", substring = true).performScrollTo().assertExists()
        compose.onAllNodesWithText(lost).assertCountEquals(0)
    }
}
