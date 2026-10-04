package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import io.github.tuthan.steamoscompanion.core.client.HostJournalPresentation
import io.github.tuthan.steamoscompanion.core.client.HostJournalState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
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
 * Renders the real Host journal section, and the real Connection details screen around it, under Robolectric
 * and reads them the way TalkBack does: merged nodes and spoken descriptions. The records come from the
 * vendored host fixtures through the real codec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostJournalSectionTest {
    @get:Rule val compose = createComposeRule()

    private val note = "Entries record what the host did and read back. A completed entry does not by itself show that the picture is visible."

    // --- the section on its own ------------------------------------------------------------------------------

    private fun showSection(state: HostJournalState?, onRefresh: () -> Unit = {}) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) { HostJournalSection(state, onRefresh) }
            }
        }
    }

    @Test fun readyListShowsEveryRecordAsOneMergedRowWithASpokenDescription() {
        showSection(HostJournalState.Ready(source(), journal("operations/list-mixed")))

        compose.onNodeWithText("HOST JOURNAL").assertExists()
        val rows = HostJournalPresentation.view(journal("operations/list-mixed")).rows
        assertEquals(5, rows.size)
        rows.forEach { row ->
            // The title and its detail lines are one node, and what a screen reader says is the whole record as sentences.
            compose.onNodeWithText(row.title).performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(row.spoken)))
            row.lines.forEach { line -> compose.onNodeWithText(line, useUnmergedTree = false).assertExists() }
        }
        compose.onNodeWithText("Display mode preview · Returned, not confirmed").assertExists()
        compose.onNodeWithText("Requested · output id: 1 · mode id: 2 · generation: 4").assertExists()
        compose.onNodeWithText("Recorded · Mode request returned; waiting for confirmation").assertExists()
        compose.onNodeWithText("Previous screen restored").assertExists()
        compose.onNodeWithText("Updated 2026-09-13 12:00:24 UTC").assertExists()
        // Five records is not a truncated list.
        compose.onAllNodesWithText("Showing the newest", substring = true).assertCountEquals(0)
    }

    @Test fun aCompletedEntryNeverReadsAsAVisiblePictureAndTheStandingNoteIsShown() {
        showSection(HostJournalState.Ready(source(), journal("operations/list-mixed")))
        compose.onNodeWithText(note).assertExists()
        compose.onNodeWithText(HostJournalPresentation.STANDING_NOTE).assertExists()
        // A Completed title says Completed and nothing about the picture.
        compose.onNodeWithText("Gaming Mode display preference · Completed").assertExists()
        // Only the entry whose outcome code says the owner confirmed visibility uses the word in its own wording.
        compose.onNodeWithText("Recorded · Current mode read back; owner confirmed the picture was visible").assertExists()
        compose.onAllNodesWithText("was visible", substring = true).assertCountEquals(1)
    }

    @Test fun anOlderListWithNoRecordsShowsTheEmptyLineAndTheNote() {
        showSection(HostJournalState.Ready(source(), HostJournal(emptyList())))
        compose.onNodeWithText("The host has recorded no operations for this device yet.").assertExists()
        compose.onNodeWithText(note).assertExists()
        compose.onAllNodesWithText("Showing the newest", substring = true).assertCountEquals(0)
    }

    @Test fun moreThanTwentyRecordsShowTheNewestTwentyAndSayHowManyThereAre() {
        val many = HostJournal((1..30).map { record("op-$it", "power.suspend") })
        showSection(HostJournalState.Ready(source(), many))
        compose.onAllNodesWithText("Suspend · Completed").assertCountEquals(20)
        compose.onNodeWithText("Showing the newest 20 of 30").performScrollTo().assertExists()
        compose.onNodeWithText(note).assertExists()
    }

    @Test fun exactlyTwentyRecordsAreNotTruncated() {
        showSection(HostJournalState.Ready(source(), HostJournal((1..20).map { record("op-$it", "power.suspend") })))
        compose.onAllNodesWithText("Suspend · Completed").assertCountEquals(20)
        compose.onAllNodesWithText("Showing the newest", substring = true).assertCountEquals(0)
    }

    @Test fun anUnknownKindStateAndOutcomeAreShownAsTextNotHidden() {
        val odd = HostJournal(listOf(HostJournalRecord("op-1", "future.kind", "paused", null, null, "strange_outcome", null,
            "half_done", null, null, emptyMap())))
        showSection(HostJournalState.Ready(source(), odd))
        compose.onNodeWithText("future.kind · paused").assertExists()
        compose.onNodeWithText("Recorded · strange_outcome").assertExists()
        compose.onNodeWithText("Restore state · half_done").assertExists()
    }

    @Test fun loadingShowsOneMutedLineDisablesRefreshAndHasNoRowsOrNote() {
        showSection(HostJournalState.Loading(source()))
        compose.onNodeWithText("Loading the host journal…").assertExists()
        compose.onNodeWithContentDescription("Refresh journal").assertIsNotEnabled()
        compose.onAllNodesWithText(note).assertCountEquals(0)
        compose.onAllNodesWithText("not available", substring = true).assertCountEquals(0)
    }

    @Test fun unavailableIsOneMutedLineNotAnErrorAndKeepsRefresh() {
        showSection(HostJournalState.Unavailable(source()))
        compose.onNodeWithText("The host journal is not available from this device.").assertExists()
        compose.onNodeWithContentDescription("Refresh journal").assertIsEnabled()
        listOf("error", "failed", "denied", "unauthorized").forEach {
            compose.onAllNodesWithText(it, substring = true, ignoreCase = true).assertCountEquals(0)
        }
        compose.onAllNodesWithText(note).assertCountEquals(0)
    }

    @Test fun nothingAskedYetShowsOnlyTheHeadingAndRefresh() {
        showSection(null)
        compose.onNodeWithText("HOST JOURNAL").assertExists()
        compose.onNodeWithContentDescription("Refresh journal").assertIsEnabled()
        compose.onAllNodesWithText("Loading", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(note).assertCountEquals(0)
    }

    @Test fun refreshAsksOnceWhenPressedAndNotWhileLoading() {
        var asked = 0
        var state by mutableStateOf<HostJournalState?>(HostJournalState.Unavailable(source()))
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) { HostJournalSection(state) { asked++ } }
            }
        }
        compose.onNodeWithContentDescription("Refresh journal").performClick()
        compose.waitForIdle()
        assertEquals(1, asked)
        state = HostJournalState.Loading(source())
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Refresh journal").assertIsNotEnabled()
        assertEquals(1, asked)
    }

    // --- inside Connection details ---------------------------------------------------------------------------

    private class Harness(first: ActiveHost) {
        var host by mutableStateOf(first)
        var status by mutableStateOf(StatusPollState())
        var journal by mutableStateOf<HostJournalState?>(null)
        val wanted = mutableListOf<ActiveHost>()
    }

    private fun showScreen(harness: Harness) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionDetailsScreen(
                        host = harness.host, name = "Deck", state = null, status = harness.status, busy = false, feedback = null,
                        onPairAnother = {}, onConfirmReplacement = { _, _ -> }, onDiscardReplacement = {}, onRevoke = {},
                        onForget = {}, onExport = {},
                        hostJournal = harness.journal, onHostJournalWanted = { harness.wanted += it },
                    )
                }
            }
        }
    }

    @Test fun theScreenAsksForTheJournalOnceOnEntryAndNeverOnAStatusPoll() {
        val harness = Harness(host())
        showScreen(harness)
        compose.waitForIdle()
        assertEquals(listOf(harness.host), harness.wanted)

        // Status polls keep arriving with fresh observations, as the 2-second poll delivers them: no request.
        repeat(10) { poll ->
            harness.status = StatusPollState(lastSuccessMonotonicNanos = 2L + poll, source = StatusObservationSource.from(harness.host))
            compose.waitForIdle()
        }
        // Neither does a state arriving, nor the same pairing learning a boot ID.
        harness.journal = HostJournalState.Ready(StatusObservationSource.from(harness.host), journal("operations/list-mixed"))
        compose.waitForIdle()
        harness.host = harness.host.copy(lastObservedBootId = "boot-2")
        compose.waitForIdle()
        assertEquals("one request, from entering the screen", 1, harness.wanted.size)
    }

    @Test fun refreshIsTheOnlyOtherWayToAsk() {
        val harness = Harness(host())
        showScreen(harness)
        harness.journal = HostJournalState.Unavailable(StatusObservationSource.from(harness.host))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Refresh journal").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(2, harness.wanted.size)
        assertEquals(harness.host, harness.wanted.last())
    }

    @Test fun theSectionIsPartOfConnectionDetailsBesideTheOtherGroups() {
        val harness = Harness(host())
        harness.journal = HostJournalState.Ready(StatusObservationSource.from(harness.host), journal("operations/list-mixed"))
        showScreen(harness)
        compose.waitForIdle()
        compose.onNodeWithText("HOST JOURNAL").performScrollTo().assertExists()
        compose.onNodeWithText("Display mode preview · Returned, not confirmed").performScrollTo().assertExists()
        compose.onNodeWithText(note).performScrollTo().assertExists()
        // The app's own request log is named for what it is, so it cannot be mistaken for the host's journal.
        compose.onNodeWithText("REQUESTS FROM THIS PHONE").performScrollTo().assertExists()
        compose.onNodeWithText("OPERATION JOURNAL").assertDoesNotExist()
        compose.onNodeWithContentDescription("Remove this pairing").performScrollTo().assertExists()
    }

    @Test fun aJournalHeldForAnotherPairingIsNeverShownAndAnotherPairingAsksAgain() {
        val harness = Harness(host())
        showScreen(harness)
        val other = host(token = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx")
        harness.journal = HostJournalState.Ready(StatusObservationSource.from(other), journal("operations/list-mixed"))
        compose.waitForIdle()
        compose.onAllNodesWithText("Display mode preview", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(note).assertCountEquals(0)

        // The pairing changes: the screen asks for the new pairing, once.
        harness.host = other
        compose.waitForIdle()
        assertEquals(listOf(host(), other).map { StatusObservationSource.from(it) }, harness.wanted.map { StatusObservationSource.from(it) })
        // And the reading that was for it now shows.
        compose.onNodeWithText("Display mode preview · Returned, not confirmed").performScrollTo().assertExists()
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun source() = StatusObservationSource.from(host())

    private fun record(id: String, kind: String) =
        HostJournalRecord(id, kind, "succeeded", null, null, null, null, null, null, null, emptyMap())

    private fun host(token: String = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx") = ActiveHost(
        hostId = "host-fixture0001xxxxxxxxxxxxx", clientId = "client-android", endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32), bearerToken = token,
        grantedScopes = listOf("status.read", "power.control", "display.control"),
        wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"), pairedAtMillis = 1_780_000_000_000,
    )

    private fun journal(name: String): HostJournal {
        val fixture = BoundedJson.parseObject(File("../protocol/fixtures/contract/$name.json").also {
            check(it.isFile) { "vendored fixture not found at ${it.absolutePath}" }
        }.readBytes())
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        val reply = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return checkNotNull(((reply as ApiReply.Versioned).payload as VersionedPayload.OperationList).journal)
    }
}
