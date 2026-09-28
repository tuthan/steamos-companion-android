package io.github.tuthan.steamoscompanion.android.ui

import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.android.data.AndroidAtomicStateStore
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StagedCredential
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** API 37 launch check without Espresso's removed InputManager.getInstance() reflection path. */
@RunWith(AndroidJUnit4::class)
class CompanionActivityLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val repository get() = AndroidAtomicStateStore.createRepository(instrumentation.targetContext)

    @Before fun resetState() {
        assertTrue(repository.transact { PersistentState(pairingGeneration = it.pairingGeneration) }
            is StateCommitResult.Saved)
    }

    @After fun clearState() {
        repository.transact { PersistentState(pairingGeneration = it.pairingGeneration) }
    }

    @Test fun noActivePairingOpensOnboarding() {
        val intent = Intent(instrumentation.targetContext, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            assertTrue(activity is CompanionActivity)
            assertTrue(awaitText("Connect a remote device") != null)
            assertTrue(awaitText("Find devices") != null)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun pairedOverviewOpensPowerScreen() {
        val host = ActiveHost("ui-host", "ui-client", "deck.invalid:18443",
            "sha256:" + "a".repeat(64), "ui-token-1234567890",
            listOf("status.read", "power.control", "display.control"),
            StoredWakeTarget(false, null, null, null, null), 1_790_000_000_000)
        assertTrue(repository.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
        val intent = Intent(instrumentation.targetContext, CompanionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.waitForIdleSync()
            assertTrue(awaitText("deck.invalid:18443") != null)
            assertTrue(awaitText("Check device status") != null)
            clickText("Power options")
            assertTrue(awaitText("Suspend") != null)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun pairedOverviewOpensDisplayAndConnectionDetails() {
        val host = ActiveHost("ui-host", "ui-client", "deck.invalid:18443",
            "sha256:" + "a".repeat(64), "ui-token-1234567890",
            listOf("status.read", "power.control", "display.control"),
            StoredWakeTarget(false, null, null, null, null), 1_790_000_000_000)
        assertTrue(repository.transact { it.copy(activeHost = host) } is StateCommitResult.Saved)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CompanionActivity
        try {
            assertTrue(awaitText("deck.invalid:18443") != null)
            clickText("Display settings")
            assertTrue(awaitText("Saved recovery") != null)
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            assertTrue(awaitText("Power options") != null)
            clickText("Connection details")
            assertTrue(awaitText("Paired device") != null)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun stagedReplacementOpensConfirmationAndCancelKeepsOldHost() {
        val oldHost = seedStagedReplacement()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CompanionActivity
        try {
            assertTrue(awaitText("New pairing approved") != null)
            clickText("Use new device")
            assertTrue(awaitText("Replace deck.invalid:18443?") != null)
            clickText("Cancel")
            val saved = repository.load() as StateLoadResult.Ready
            assertTrue(saved.state.activeHost == oldHost)
            assertTrue(saved.state.candidate?.phase == CandidatePhase.APPROVED_STAGED)
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun confirmingStagedReplacementSwitchesActiveHost() {
        val oldHost = seedStagedReplacement()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CompanionActivity
        try {
            assertTrue(awaitText("New pairing approved") != null)
            assertTrue((repository.load() as StateLoadResult.Ready).state.activeHost == oldHost)
            clickText("Use new device")
            assertTrue(awaitText("Replace deck.invalid:18443?") != null)
            clickText("Use new device")
            repeat(50) {
                val saved = repository.load() as StateLoadResult.Ready
                if (saved.state.activeHost?.endpoint == "new-deck.invalid:18443") {
                    assertTrue(saved.state.candidate == null)
                    assertTrue(awaitText("new-deck.invalid:18443") != null)
                    assertTrue(awaitText("Power options") != null)
                    return
                }
                Thread.sleep(100)
            }
            throw AssertionError("Replacement was not committed")
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun resumedVerificationCodeUsesSecureWindowAndGroupedAnnouncement() {
        val generation = (repository.load() as StateLoadResult.Ready).state.pairingGeneration + 1
        val candidate = PairingCandidate(
            generation = generation, variant = PairingVariant.VERIFICATION,
            clientId = "verification-client", clientName = "Android Companion",
            requestedScopes = listOf("status.read"), endpoint = "deck.invalid:18443",
            certificatePin = "sha256:" + "a".repeat(64), hostId = "verification-host",
            createdAtMillis = 1_790_000_000_100, expiresAtMillis = null,
            phase = CandidatePhase.PENDING,
            verificationNonce = "A".repeat(22), verificationCode = "75332470",
            pairingId = "verification-pair", pairingSession = "S".repeat(24),
        )
        assertTrue(repository.transact {
            it.copy(pairingGeneration = generation, candidate = candidate)
        } is StateCommitResult.Saved)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CompanionActivity
        try {
            assertTrue(awaitText("Compare the code") != null)
            assertTrue(awaitText("7 5 3 3, 2 4 7 0") != null)
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun scannerRouteSecuresWindowAndClearsFlagOnBack() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CompanionActivity
        try {
            assertTrue(awaitText("Connect a remote device") != null)
            clickText("Scan QR code")
            assertTrue(awaitText("Or paste a pairing payload") != null)
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            assertTrue(awaitText("Connect a remote device") != null)
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
            }
        } finally {
            activity.finish()
            instrumentation.waitForIdleSync()
        }
    }

    private fun seedStagedReplacement(): ActiveHost {
        val generation = (repository.load() as StateLoadResult.Ready).state.pairingGeneration + 1
        val oldHost = ActiveHost("ui-host", "ui-client", "deck.invalid:18443",
            "sha256:" + "a".repeat(64), "ui-token-1234567890",
            listOf("status.read", "power.control"),
            StoredWakeTarget(false, null, null, null, null), 1_790_000_000_000)
        val candidate = PairingCandidate(
            generation = generation, variant = PairingVariant.VERIFICATION,
            clientId = "replacement-client", clientName = "Android Companion",
            requestedScopes = listOf("status.read"), endpoint = "new-deck.invalid:18443",
            certificatePin = "sha256:" + "b".repeat(64), hostId = "replacement-host",
            createdAtMillis = 1_790_000_000_100, expiresAtMillis = null,
            phase = CandidatePhase.APPROVED_STAGED, pairingId = "replacement-pair",
            stagedCredential = StagedCredential("replacement-client", "replacement-host",
                "replacement-token-1234567890", listOf("status.read"),
                StoredWakeTarget(false, null, null, null, null), 1_790_000_000_200),
        )
        assertTrue(repository.transact {
            it.copy(pairingGeneration = generation, activeHost = oldHost, candidate = candidate)
        } is StateCommitResult.Saved)
        return oldHost
    }

    private fun clickText(value: String) {
        val node = requireNotNull(awaitText(value)) { "Missing $value" }
        var clickable = node
        while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
        assertTrue("$value is not clickable", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitText(value: String): AccessibilityNodeInfo? {
        repeat(40) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            root?.findText(value)?.let { return it }
            Thread.sleep(100)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        throw AssertionError("Missing $value in\n${root?.dumpTree()}")
    }

    private fun AccessibilityNodeInfo.findText(text: String): AccessibilityNodeInfo? {
        if (this.text?.toString()?.contains(text, ignoreCase = true) == true ||
            contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return this
        }
        for (index in 0 until childCount) {
            getChild(index)?.findText(text)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.dumpTree(depth: Int = 0): String = buildString {
        append("  ".repeat(depth)).append(text).append(" / ").append(contentDescription)
            .append(" clickable=").append(isClickable).append('\n')
        for (index in 0 until childCount) getChild(index)?.let { append(it.dumpTree(depth + 1)) }
    }
}
