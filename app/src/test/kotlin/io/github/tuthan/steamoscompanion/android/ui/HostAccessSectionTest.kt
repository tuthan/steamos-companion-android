package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Access on this host" line of Connection details, rendered by the real screen. The scopes come from the vendored
 * host fixtures through the real codec, and the screen reads them from the pairing it is given: the same value every
 * gate reads, replaced by each status read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostAccessSectionTest {
    @get:Rule val compose = createComposeRule()

    private val note = "The owner of the host chose this and can change it there."

    private class Harness(first: ActiveHost) {
        var host by mutableStateOf(first)
    }

    private fun show(harness: Harness) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionDetailsScreen(
                        host = harness.host, name = "Deck", state = null, status = StatusPollState(), busy = false, feedback = null,
                        onPairAnother = {}, onConfirmReplacement = { _, _ -> }, onDiscardReplacement = {}, onRevoke = {},
                        onForget = {}, onExport = {},
                    )
                }
            }
        }
    }

    @Test fun fullAccessNamesAllThreeAndHasNoNote() {
        show(Harness(host(reported("sunshine-running"))))
        compose.onNodeWithText("ACCESS ON THIS HOST").performScrollTo().assertExists()
        compose.onNodeWithText("Full access · Status · Power · Display").performScrollTo().assertExists()
        compose.onAllNodesWithText(note).assertCountEquals(0)
    }

    @Test fun viewOnlyFixtureShowsViewOnlyWithTheOwnersNote() {
        show(Harness(host(reported("view-only-credential"))))
        compose.onNodeWithText("View only · Status").performScrollTo().assertExists()
        compose.onNodeWithText(note).performScrollTo().assertExists()
    }

    @Test fun ownerChangedFixtureShowsCustomInStatusPowerDisplayOrder() {
        show(Harness(host(reported("scopes-changed-by-owner"))))
        compose.onNodeWithText("Custom · Status · Display").performScrollTo().assertExists()
        compose.onNodeWithText(note).performScrollTo().assertExists()
    }

    @Test fun noScopesIsNoAccessWithNoPermissions() {
        show(Harness(host(emptyList())))
        compose.onNodeWithText("No access · No permissions").performScrollTo().assertExists()
        compose.onNodeWithText(note).performScrollTo().assertExists()
    }

    @Test fun theLineFollowsTheStoredPairingWhenAStatusReadReplacesItsScopes() {
        val harness = Harness(host(reported("sunshine-running")))
        show(harness)
        compose.onNodeWithText("Full access · Status · Power · Display").performScrollTo().assertExists()
        harness.host = harness.host.copy(grantedScopes = reported("view-only-credential"))
        compose.waitForIdle()
        compose.onNodeWithText("View only · Status").performScrollTo().assertExists()
        compose.onAllNodesWithText("Full access", substring = true).assertCountEquals(0)
        harness.host = harness.host.copy(grantedScopes = reported("sunshine-running"))
        compose.waitForIdle()
        compose.onNodeWithText("Full access · Status · Power · Display").performScrollTo().assertExists()
        compose.onAllNodesWithText(note).assertCountEquals(0)
    }

    @Test fun grantedPermissionsAndTheAccessLineAreOneValue() {
        // The chips below the line read the same pairing, so the two can never disagree.
        show(Harness(host(reported("view-only-credential"))))
        compose.onNodeWithText("View only · Status").performScrollTo().assertExists()
        compose.onNodeWithText("READ STATUS").performScrollTo().assertExists()
        compose.onAllNodesWithText("CONTROL POWER").assertCountEquals(0)
        compose.onAllNodesWithText("CHANGE DISPLAY SETTINGS").assertCountEquals(0)
    }

    @Test fun theReportedScopesTheScreenIsGivenAreExactlyWhatTheFixtureSays() {
        assertEquals(listOf("status.read"), reported("view-only-credential"))
        assertEquals(listOf("status.read", "display.control"), reported("scopes-changed-by-owner"))
        assertEquals(listOf("status.read", "power.control", "display.control"), reported("sunshine-running"))
    }

    private fun host(scopes: List<String>) = ActiveHost(
        hostId = "host-fixture0001xxxxxxxxxxxxx", clientId = "client-android", endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        grantedScopes = scopes,
        wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"), pairedAtMillis = 1_780_000_000_000,
    )

    /** What a status read of this vendored fixture reports, through the real codec. */
    private fun reported(name: String): List<String> {
        val fixture = BoundedJson.parseObject(File("../protocol/fixtures/contract/status/$name.json").also {
            check(it.isFile) { "vendored fixture not found at ${it.absolutePath}" }
        }.readBytes())
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        val reply = ProtocolCodec.decodeResponse(ApiRoute.STATUS, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return checkNotNull(((reply as ApiReply.Versioned).payload as VersionedPayload.Status).credentialScopes)
    }
}
