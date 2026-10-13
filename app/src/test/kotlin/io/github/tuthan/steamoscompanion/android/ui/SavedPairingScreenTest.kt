package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tuthan.steamoscompanion.core.client.SavedPairingProblem
import io.github.tuthan.steamoscompanion.core.client.StatePersistenceFailure
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StateUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 04: a state that cannot be opened is its own screen, never the unpaired flow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedPairingScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Calls { var retry = 0; var reset = 0 }

    private fun show(load: StateLoadResult, busy: Boolean = false, feedback: ActionFeedback? = null): Calls {
        val calls = Calls()
        val problem = checkNotNull(SavedPairingProblem.from(load))
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SavedPairingUnavailableScreen(problem, busy, feedback, onRetry = { calls.retry++ },
                        onReset = { calls.reset++ })
                }
            }
        }
        return calls
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun assertResetIsOfferedAndConfirmed(reason: StateUnavailableReason) {
        val calls = show(StateLoadResult.Unavailable(reason))
        compose.onNode(hasText("Saved pairing unavailable") and heading).assertExists()
        compose.onNodeWithText("Reset local pairing").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)

        // Nothing is erased by the tap itself: the owner confirms first, and cancelling erases nothing.
        compose.onNodeWithText("Reset local pairing").performClick()
        compose.onNodeWithText("Reset local pairing?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals("$reason cancelled", 0, calls.reset)

        compose.onNodeWithText("Reset local pairing").performClick()
        compose.onNodeWithText("Reset on this phone").performClick()
        compose.waitForIdle()
        assertEquals("$reason confirmed", 1, calls.reset)
        assertEquals(0, calls.retry)
    }

    @Test fun aDamagedStateOffersAConfirmedReset() = assertResetIsOfferedAndConfirmed(StateUnavailableReason.DAMAGED)

    @Test fun aMissingKeyOffersAConfirmedReset() = assertResetIsOfferedAndConfirmed(StateUnavailableReason.KEY_MISSING)

    @Test fun anInvalidatedKeyOffersAConfirmedReset() = assertResetIsOfferedAndConfirmed(StateUnavailableReason.KEY_INVALIDATED)

    @Test fun theConfirmationSaysTheOtherDeviceMayStillListThisPhone() {
        show(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED))
        compose.onNodeWithText("Reset local pairing").performScrollTo().performClick()
        compose.onNode(hasText("Nothing is sent to the other device", substring = true)).assertExists()
    }

    private fun assertOnlyRetryIsOffered(load: StateLoadResult) {
        val calls = show(load)
        compose.onNode(hasText("Saved pairing unavailable") and heading).assertExists()
        compose.onAllNodesWithText("Reset local pairing").assertCountEquals(0)
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(load.toString(), 1, calls.retry)
        assertEquals(0, calls.reset)
    }

    @Test fun aTemporaryKeyProblemOnlyRetries() =
        assertOnlyRetryIsOffered(StateLoadResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE))

    @Test fun anUnreadableFileOnlyRetries() = assertOnlyRetryIsOffered(StateLoadResult.Failed(StatePersistenceFailure.IO))

    @Test fun aCryptoFailureOnlyRetries() = assertOnlyRetryIsOffered(StateLoadResult.Failed(StatePersistenceFailure.CRYPTO))

    @Test fun aNewerSchemaIsAVersionMismatchWithUpdateGuidanceAndIsNeverErased() {
        val calls = show(StateLoadResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED))
        compose.onNodeWithText("App version mismatch").assertExists()
        compose.onNode(hasText("Update the app", substring = true)).assertExists()
        compose.onNode(hasText("Reinstalling", substring = true)).assertExists()
        compose.onAllNodesWithText("Reset local pairing").assertCountEquals(0)
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(1, calls.retry)
        assertEquals(0, calls.reset)
    }

    @Test fun whileAnActionRunsNothingCanBeStartedTwice() {
        show(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED), busy = true)
        compose.onNodeWithText("Reset local pairing").performScrollTo().assertIsNotEnabled()
    }

    @Test fun theResultOfResetOrRetryIsAnnouncedOnce() {
        show(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED),
            feedback = ActionFeedback("Could not remove the saved pairing; try again.", StatusTone.WARNING, 1))
        compose.onNode(hasText("Could not remove the saved pairing; try again.") and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    @Test fun nothingPairedAndAReadableStateAreNotProblems() {
        assertEquals(null, SavedPairingProblem.from(StateLoadResult.Empty))
    }
}
