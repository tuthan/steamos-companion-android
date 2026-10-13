package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.JournalRejectionCode
import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Flows §9 on the Display screen, rendered by the real screen from a real journal entry: a restore that failed, or that is
 * still open while the connection is down, says it could not confirm recovery and offers Try connection, which is one
 * fresh status read and never a second restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayRecoveryWiringTest {
    @get:Rule val compose = createComposeRule()

    private val host = ActiveHost("host-fixture0001xxxxxxxxxxxxx", "client-android", "deck.local:18443",
        "sha256:" + "ab".repeat(32), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        listOf("status.read", "display.control"), StoredWakeTarget(false, "", null, null, null), 1_780_000_000_000)

    private fun entry(route: ApiRoute, body: JsonValue.ObjectValue, phase: JournalPhase,
                      code: JournalRejectionCode? = null): JournalEntry {
        val request = ApiRequest(HttpMethod.POST, route.path, body, host.bearerToken)
        return JournalEntry.create(1, host, request, BoundedJson.encode(body), 100)
            .copy(phase = phase, rejectionCode = code, operationId = "op-1")
    }

    private fun restore(phase: JournalPhase, code: JournalRejectionCode? = null) = entry(ApiRoute.DISPLAY_RESTORE,
        jsonObject("request_id" to JsonValue.StringValue("restore-1"), "source" to JsonValue.StringValue("verified"),
            "profile_id" to JsonValue.StringValue("profile-1")),
        phase, code)

    private fun saveCurrent(phase: JournalPhase, code: JournalRejectionCode? = null) = entry(ApiRoute.DISPLAY_SAVE_CURRENT,
        jsonObject("request_id" to JsonValue.StringValue("save-1"), "output_id" to JsonValue.StringValue("1"),
            "generation" to JsonValue.NumberValue("4"), "visible" to JsonValue.BooleanValue(true)), phase, code)

    private fun outputs() = payload(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/available") as VersionedPayload.DisplayOutputs

    private fun payload(route: ApiRoute, name: String): VersionedPayload {
        val fixture = BoundedJson.parseObject(File("../protocol/fixtures/contract/$name.json").readBytes())
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }

    private fun status(unreachable: Boolean) = StatusPollState(
        lastRead = PairedStatusRead(payload(ApiRoute.STATUS, "status/sunshine-stopped") as VersionedPayload.Status, outputs(), null),
        lastSuccessMonotonicNanos = 1_100_000_000L, unreachable = unreachable, source = StatusObservationSource.from(host))

    private class Calls { var tryConnection = 0; var restore = 0; var check = 0 }

    private fun show(journal: JournalEntry, unreachable: Boolean): Calls {
        val calls = Calls()
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DisplayScreen(host, PersistentState(activeHost = host, journal = listOf(journal)), status(unreachable),
                        2_000_000_000L, busy = false, feedback = null, onPreview = {}, onRestore = { calls.restore++ },
                        onRevert = {}, onKeep = {}, onSaveCurrent = {}, onSaveOrder = { _, _ -> },
                        onSaveOrderAndRestart = {}, onResetOrder = {}, onCheckOperation = { calls.check++ },
                        onTryConnection = { calls.tryConnection++ })
                }
            }
        }
        return calls
    }

    private val recovery = "Couldn't confirm display recovery"

    @Test fun aFailedRestoreSaysItCouldNotConfirmRecoveryAndOffersTryConnection() {
        val calls = show(restore(JournalPhase.FAILED, JournalRejectionCode.OPERATION_FAILED), unreachable = false)
        compose.onNode(hasText(recovery) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).performScrollTo()
            .assertExists()
        compose.onNodeWithText("Try connection").performScrollTo().performClick()
        assertEquals("one fresh status read", 1, calls.tryConnection)
        assertEquals("never a second restore", 0, calls.restore)
    }

    @Test fun anOpenRestoreWhileTheConnectionIsDownSaysTheSameAndOffersTheSameWay() {
        val calls = show(restore(JournalPhase.DISPATCHED), unreachable = true)
        compose.onNodeWithText(recovery).performScrollTo().assertExists()
        compose.onNodeWithText("Try connection").performScrollTo().performClick()
        assertEquals(1, calls.tryConnection)
        assertEquals(0, calls.restore)
    }

    @Test fun aSaveThatFailedIsNotARecoveryProblem() {
        show(saveCurrent(JournalPhase.FAILED, JournalRejectionCode.OPERATION_FAILED), unreachable = true)
        compose.onAllNodesWithText(recovery).assertCountEquals(0)
        compose.onAllNodesWithText("Try connection").assertCountEquals(0)
    }

    @Test fun aRestoreTheHostHasConfirmedOrThatIsStillOpenWithTheDeviceReachableShowsNoRecoveryProblem() {
        show(restore(JournalPhase.SUCCEEDED), unreachable = true)
        compose.onAllNodesWithText(recovery).assertCountEquals(0)
    }

    @Test fun anOpenRestoreWithTheDeviceReachableIsSimplyWaiting() {
        show(restore(JournalPhase.DISPATCHED), unreachable = false)
        compose.onAllNodesWithText(recovery).assertCountEquals(0)
        compose.onAllNodesWithText("Try connection").assertCountEquals(0)
    }
}
