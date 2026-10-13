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
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.color
import io.github.tuthan.steamoscompanion.android.ui.PixelSupport.textColors
import io.github.tuthan.steamoscompanion.core.client.DisplayObservation
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationCard
import io.github.tuthan.steamoscompanion.core.client.DisplayOperationKind
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayPreviewSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Flows §9 wording on the preview card and the display recovery lines; none of it is inferred from time alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewCardTextTest {
    @get:Rule val compose = createComposeRule()

    private val mode = DisplayModeSummary("1920x1080@60", 1920, 1080, "60.000")
    private val summary = DisplayPreviewSummary("preview-1", "HDMI-A-1", 1, mode, mode, "1780000015")

    private fun active(remaining: Long?, checking: Boolean, lost: Boolean) = PreviewPresentation.Active(
        preview = summary, ownedByThisPairing = true, unprovenOperationNeedsReview = false, remainingSeconds = remaining,
        checkingRestoration = checking, connectionLost = lost, keepAvailable = true, revertAvailable = true)

    // ---- state -----------------------------------------------------------------------------------------------------

    @Test fun theCountdownSentenceFollowsWhatTheHostDeadlineAndTheReadingsSay() {
        assertEquals(PreviewStatus.REVERTS_IN, previewCardState(active(12, checking = false, lost = false)).status)
        assertEquals(PreviewStatus.ENDED_CHECKING, previewCardState(active(null, checking = true, lost = false)).status)
        // Once the deadline has passed and the host still reports the preview, a stale remaining value never wins.
        assertEquals(PreviewStatus.ENDED_CHECKING, previewCardState(active(0, checking = true, lost = false)).status)
        assertEquals(PreviewStatus.CHECKING, previewCardState(active(null, checking = false, lost = false)).status)
    }

    @Test fun aLostConnectionIsSaidOnceBeforeTheDeadlineAndRestorationIsNotClaimedAfterIt() {
        assertNull(previewCardState(active(12, checking = false, lost = false)).notice)
        assertNull("no connection notice while the device can still be reached",
            previewCardState(active(null, checking = true, lost = false)).notice)
        assertEquals(PreviewNotice.CONNECTION_LOST, previewCardState(active(12, checking = false, lost = true)).notice)
        assertEquals(PreviewNotice.CONNECTION_LOST, previewCardState(active(null, checking = false, lost = true)).notice)
        assertEquals(PreviewNotice.RESTORATION_NOT_CONFIRMED,
            previewCardState(active(null, checking = true, lost = true)).notice)
    }

    // ---- "Couldn't confirm display recovery" -----------------------------------------------------------------------

    private fun card(kind: DisplayOperationKind, phase: JournalPhase, observed: DisplayObservation) = DisplayOperationCard(
        localGeneration = 1, kind = kind, phase = phase, operationId = "op", failure = null, observed = observed,
        pairingProven = true)

    @Test fun onlyARestoreThatFailedOrIsOpenWhileTheConnectionIsDownCannotConfirmRecovery() {
        val restores = setOf(DisplayOperationKind.REVERT_PREVIEW, DisplayOperationKind.RESTORE_SAVED)
        for (kind in DisplayOperationKind.entries) for (phase in JournalPhase.entries) {
            for (observed in DisplayObservation.entries) {
                val expected = kind in restores &&
                    (phase == JournalPhase.FAILED || (phase != JournalPhase.SUCCEEDED && observed == DisplayObservation.CONNECTION_LOST))
                assertEquals("$kind $phase $observed", expected, recoveryNotConfirmed(card(kind, phase, observed)))
            }
        }
    }

    @Test fun savesKeepsAndPreviewsNeverClaimARecoveryProblem() {
        for (kind in listOf(DisplayOperationKind.SAVE_CURRENT, DisplayOperationKind.KEEP, DisplayOperationKind.PREVIEW)) {
            assertFalse(recoveryNotConfirmed(card(kind, JournalPhase.FAILED, DisplayObservation.CONNECTION_LOST)))
        }
        assertTrue(recoveryNotConfirmed(card(DisplayOperationKind.REVERT_PREVIEW, JournalPhase.UNKNOWN,
            DisplayObservation.CONNECTION_LOST)))
        assertFalse("a restore the host has since confirmed is not a problem",
            recoveryNotConfirmed(card(DisplayOperationKind.RESTORE_SAVED, JournalPhase.SUCCEEDED, DisplayObservation.CONNECTION_LOST)))
        assertFalse("an open restore with the device reachable is simply waiting",
            recoveryNotConfirmed(card(DisplayOperationKind.RESTORE_SAVED, JournalPhase.DISPATCHED, DisplayObservation.DISPLAY_REACHABLE)))
    }

    // ---- text and announcement ---------------------------------------------------------------------------------------

    private fun showCard(state: PreviewCardState, remaining: Long?) {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PreviewCard(
                        mode = "HDMI display · 1920 × 1080 · 60 Hz", remainingSeconds = remaining,
                        status = previewStatusText(state, remaining), canRevert = true, canKeep = true,
                        onRevert = {}, onKeep = {}, notice = previewNoticeText(state.notice))
                }
            }
        }
    }

    private val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)

    @Test fun theWordingIsTheFlowsTextForEachState() {
        showCard(PreviewCardState(PreviewStatus.REVERTS_IN, PreviewNotice.CONNECTION_LOST), 12)
        compose.onNodeWithText("Reverts in 12 seconds").assertExists()
        compose.onNodeWithText("Connection lost. The device will attempt to restore the previous mode at the deadline.")
            .assertExists()
    }

    @Test fun afterTheDeadlineWithNoConnectionItSaysRestorationIsNotConfirmed() {
        showCard(PreviewCardState(PreviewStatus.ENDED_CHECKING, PreviewNotice.RESTORATION_NOT_CONFIRMED), null)
        compose.onNodeWithText("Preview time ended · Checking restoration…").assertExists()
        compose.onNodeWithText("Restoration not confirmed").assertExists()
    }

    @Test fun withNoTrustworthyDeadlineItJustChecks() {
        showCard(PreviewCardState(PreviewStatus.CHECKING, null), null)
        compose.onNodeWithText("Checking restoration…").assertExists()
        compose.onAllNodesWithText("Restoration not confirmed").assertCountEquals(0)
    }

    @Test fun theNoticeIsAnnouncedOnceAndTheCountdownNever() {
        showCard(PreviewCardState(PreviewStatus.REVERTS_IN, PreviewNotice.CONNECTION_LOST), 12)
        compose.onNode(hasText("Connection lost.", substring = true) and live).assertExists()
        compose.onNode(hasText("Reverts in", substring = true) and live).assertDoesNotExist()
    }

    @Test fun theCountdownTurnsAmberInItsLastFiveSecondsAndOnlyThen() {
        showCard(PreviewCardState(PreviewStatus.REVERTS_IN, null), 6)
        assertEquals(setOf(color(CompanionColors.dark.text)), compose.onNodeWithText("Reverts in 6 seconds").textColors())
    }

    @Test fun inItsLastFiveSecondsTheCountdownIsAmber() {
        showCard(PreviewCardState(PreviewStatus.REVERTS_IN, null), 5)
        assertEquals(setOf(color(CompanionColors.dark.amber)), compose.onNodeWithText("Reverts in 5 seconds").textColors())
    }
}
