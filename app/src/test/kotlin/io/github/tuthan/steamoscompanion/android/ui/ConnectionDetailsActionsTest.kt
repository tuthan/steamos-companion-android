package io.github.tuthan.steamoscompanion.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 07: Connection details offers Rename locally, Find device again and Edit address, and the removal choice is two
 * radio cards. The screen only reports the owner's intent; the checks that matter (identity, pin, scopes) are off screen
 * and covered in `ConnectionRepairTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionDetailsActionsTest {
    @get:Rule val compose = createComposeRule()

    private val host = ActiveHost("host-fixture0001xxxxxxxxxxxxx", "client-android", "deck.local:18443",
        "sha256:" + "ab".repeat(32), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
        listOf("status.read", "power.control", "display.control"),
        StoredWakeTarget(false, "", null, null, "No usable wake interface"), 1_780_000_000_000)

    private class Calls {
        val renames = mutableListOf<String>()
        val addresses = mutableListOf<String>()
        var findAgain = 0
        var revoke = 0
        var forget = 0
    }

    private fun show(
        calls: Calls = Calls(),
        name: String = "deck.local:18443",
        busy: Boolean = false,
        feedback: ActionFeedback? = null,
        renameSucceeds: Boolean = true,
    ): Calls {
        compose.setContent {
            CompanionTheme(darkTheme = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConnectionDetailsScreen(
                        host = host, name = name, state = null, status = StatusPollState(), busy = busy,
                        feedback = feedback, onPairAnother = {}, onConfirmReplacement = { _, _ -> },
                        onDiscardReplacement = {}, onRevoke = { calls.revoke++ }, onForget = { calls.forget++ },
                        onExport = {},
                        onRename = { calls.renames += it; renameSucceeds },
                        onEditAddress = { calls.addresses += it },
                        onFindAgain = { calls.findAgain++ },
                    )
                }
            }
        }
        return calls
    }

    @Test fun theThreeConnectionRepairRowsAreOffered() {
        show()
        for (title in listOf("Rename locally", "Find device again", "Edit address")) {
            compose.onNodeWithText(title).performScrollTo().assertExists().assertIsEnabled()
        }
    }

    @Test fun whileBusyNoneOfTheRowsCanBeUsed() {
        show(busy = true)
        for (title in listOf("Rename locally", "Find device again", "Edit address")) {
            compose.onNodeWithText(title).performScrollTo().assertIsNotEnabled()
        }
    }

    @Test fun renameSendsTheTypedNameAndReportsItSavedOnThisPhone() {
        val calls = show()
        compose.onNodeWithText("Rename locally").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  Living-room Deck ")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(listOf("  Living-room Deck "), calls.renames)
        compose.onNodeWithText("Name saved on this phone").performScrollTo().assertExists()
        // A saved-name line is a polite live region, announced once.
        compose.onNode(hasText("Name saved on this phone") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
            .assertExists()
    }

    @Test fun renameFailureIsSaidAndNothingElseChanges() {
        val calls = show(renameSucceeds = false)
        compose.onNodeWithText("Rename locally").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("x")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(1, calls.renames.size)
        compose.onNodeWithText("That name could not be saved").performScrollTo().assertExists()
    }

    @Test fun renameStartsFromTheCurrentAliasAndNeverFromTheEndpoint() {
        show(name = "Living-room Deck")
        compose.onNodeWithText("Rename locally").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Living-room Deck")).assertExists()
    }

    @Test fun renameDialogStartsEmptyWhenThereIsNoAliasYet() {
        show(name = "deck.local:18443")
        compose.onNodeWithText("Rename locally").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("deck.local:18443")).assertDoesNotExist()
    }

    @Test fun editAddressNeedsAChangedValidAddressAndThenOffersOnlyTheAuthorityToCheck() {
        val calls = show()
        compose.onNodeWithText("Edit address").performScrollTo().performClick()
        // Unchanged: nothing to check.
        compose.onNodeWithText("Check and save").assertIsNotEnabled()

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("")
        compose.onNodeWithText("Check and save").assertIsNotEnabled()
        fields[0].performTextReplacement("192.0.2.9")
        fields[1].performTextReplacement("70000")
        compose.onNodeWithText("Check and save").assertIsNotEnabled()
        fields[1].performTextReplacement("9000")
        compose.onNodeWithText("Check and save").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("192.0.2.9:9000"), calls.addresses)
        compose.onAllNodesWithText("Check and save").assertCountEquals(0)
    }

    @Test fun cancellingEditAddressChecksNothing() {
        val calls = show()
        compose.onNodeWithText("Edit address").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("192.0.2.9")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(calls.addresses.isEmpty())
    }

    @Test fun findDeviceAgainReportsTheIntentOnce() {
        val calls = show()
        compose.onNodeWithText("Find device again").performScrollTo().performClick()
        assertEquals(1, calls.findAgain)
    }

    @Test fun aRepairResultReadsBesideTheRowsThatRanItAndIsAnnouncedOnce() {
        val feedback = ActionFeedback("Address updated to 192.0.2.8:18443", StatusTone.GOOD, 1)
        show(feedback = feedback)
        // Before any repair tap the result belongs to removal and sits under the Remove row, below Find device again.
        val below = compose.onNodeWithText(feedback.text).performScrollTo().fetchSemanticsNode().positionInRoot.y
        val rowTop = compose.onNodeWithText("Find device again").performScrollTo().fetchSemanticsNode().positionInRoot.y
        assertTrue(below > rowTop)

        compose.onNodeWithText("Find device again").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(feedback.text).assertCountEquals(1)
        val moved = compose.onNodeWithText(feedback.text).performScrollTo().fetchSemanticsNode().positionInRoot.y
        val pair = compose.onNodeWithText("Pair with another device").performScrollTo().fetchSemanticsNode().positionInRoot.y
        assertTrue("the result sits above the pairing rows", moved < pair)
        compose.onNode(hasText(feedback.text) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    @Test fun removingAPairingIsTwoRadioCardsAndNothingIsChosenForTheOwner() {
        val calls = show()
        compose.onNodeWithText("Remove this pairing").performScrollTo().performClick()
        val revoke = compose.onNode(hasText("Remove access on the host") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        val forget = compose.onNode(hasText("Forget on this phone only") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        revoke.assertExists().assertIsNotSelected()
        forget.assertExists().assertIsNotSelected()
        compose.onNodeWithText("Continue").assertIsNotEnabled()

        revoke.performClick()
        revoke.assertIsSelected()
        forget.assertIsNotSelected()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, calls.revoke)
        assertEquals(0, calls.forget)
    }

    @Test fun forgettingOnThisPhoneStillAsksForConfirmationAndNeverRevokes() {
        val calls = show()
        compose.onNodeWithText("Remove this pairing").performScrollTo().performClick()
        compose.onNode(hasText("Forget on this phone only") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals("forgetting waits for its own confirmation", 0, calls.forget)
        compose.onNodeWithText("Forget on this phone only?").assertExists()
        compose.onNodeWithText("Forget on this phone").performClick()
        compose.waitForIdle()
        assertEquals(1, calls.forget)
        assertEquals(0, calls.revoke)
    }

    @Test fun cancellingTheRemovalChoiceChangesNothing() {
        val calls = show()
        compose.onNodeWithText("Remove this pairing").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, calls.revoke + calls.forget)
        compose.onAllNodesWithText("Continue").assertCountEquals(0)
    }
}
