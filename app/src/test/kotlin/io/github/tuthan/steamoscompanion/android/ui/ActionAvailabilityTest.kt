package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionAvailabilityTest {
    @Test
    fun wakeUsesSavedTargetWithoutRequiringFreshRemoteStatus() {
        val context = readyContext().copy(statusFreshness = StatusFreshness.UNREACHABLE)

        assertTrue(ActionAvailability.forAction(UiAction.WAKE_DEVICE, context).enabled)
        assertEquals(
            ActionBlock.WAKE_NOT_CONFIGURED,
            ActionAvailability.forAction(UiAction.WAKE_DEVICE, context.copy(wakeTargetConfigured = false)).block,
        )
    }

    @Test
    fun savedDisplayRestoreUsesKnownProfileEvenWhenStatusIsStaleOrUnreachable() {
        for (freshness in listOf(StatusFreshness.STALE, StatusFreshness.UNREACHABLE)) {
            val result = ActionAvailability.forAction(
                UiAction.RESTORE_SAVED_DISPLAY,
                readyContext().copy(statusFreshness = freshness),
            )
            assertTrue("$freshness should not hide blind recovery", result.enabled)
        }

        assertTrue(ActionAvailability.forAction(
            UiAction.RESTORE_SAVED_DISPLAY,
            readyContext().copy(statusFreshness = StatusFreshness.NEVER_CHECKED,
                previewOwnership = PreviewOwnership.UNKNOWN),
        ).enabled)

        assertEquals(
            ActionBlock.NO_SAVED_PROFILE,
            ActionAvailability.forAction(
                UiAction.RESTORE_SAVED_DISPLAY,
                readyContext().copy(savedRecoveryProfileAvailable = false),
            ).block,
        )
    }

    @Test
    fun powerRequiresFreshStatusGrantedScopeAndAvailableCapability() {
        val context = readyContext()
        assertTrue(ActionAvailability.forAction(UiAction.SHUT_DOWN, context).enabled)
        assertEquals(
            ActionBlock.STATUS_NOT_FRESH,
            ActionAvailability.forAction(UiAction.SHUT_DOWN, context.copy(statusFreshness = StatusFreshness.STALE)).block,
        )
        assertEquals(
            ActionBlock.MISSING_POWER_PERMISSION,
            ActionAvailability.forAction(UiAction.RESTART, context.copy(grantedScopes = setOf("status.read"))).block,
        )
        assertEquals(
            ActionBlock.CAPABILITY_UNAVAILABLE,
            ActionAvailability.forAction(
                UiAction.SUSPEND,
                context.copy(capabilities = context.capabilities + ("suspend" to WireValue.Known(CapabilityState.UNAVAILABLE))),
            ).block,
        )
        assertEquals(
            ActionBlock.CAPABILITY_UNVERIFIED,
            ActionAvailability.forAction(UiAction.SUSPEND, context.copy(capabilities = emptyMap())).block,
        )
    }

    @Test
    fun exactPreviewRecoveryControlsRemainAvailableAndOtherMutationsAreBlocked() {
        val preview = readyContext().copy(
            previewOwnership = PreviewOwnership.OWNED_HERE,
            previewRemainingMillis = 5_000,
        )

        assertTrue(ActionAvailability.forAction(UiAction.REVERT_PREVIEW, preview).enabled)
        assertTrue(ActionAvailability.forAction(UiAction.KEEP_PREVIEW, preview).enabled)
        assertTrue(ActionAvailability.forAction(UiAction.RESTORE_SAVED_DISPLAY, preview).enabled)
        assertEquals(ActionBlock.PREVIEW_ACTIVE, ActionAvailability.forAction(UiAction.PREVIEW_DISPLAY_MODE, preview).block)
        assertEquals(ActionBlock.PREVIEW_ACTIVE, ActionAvailability.forAction(UiAction.SAVE_CURRENT_DISPLAY, preview).block)
        assertEquals(ActionBlock.PREVIEW_ACTIVE, ActionAvailability.forAction(UiAction.SHUT_DOWN, preview).block)
    }

    @Test
    fun previewOwnedByAnotherClientNeverExposesLocalRecoveryActions() {
        val preview = readyContext().copy(previewOwnership = PreviewOwnership.OTHER_CLIENT)

        assertEquals(ActionBlock.PREVIEW_OWNED_ELSEWHERE, ActionAvailability.forAction(UiAction.REVERT_PREVIEW, preview).block)
        assertEquals(ActionBlock.PREVIEW_OWNED_ELSEWHERE, ActionAvailability.forAction(UiAction.RESTORE_SAVED_DISPLAY, preview).block)
        assertEquals(ActionBlock.PREVIEW_OWNED_ELSEWHERE, ActionAvailability.forAction(UiAction.RESTART, preview).block)
    }

    @Test
    fun previewKeepExpiresButRevertRemainsAnExplicitRecoveryAction() {
        val preview = readyContext().copy(
            previewOwnership = PreviewOwnership.OWNED_HERE,
            previewRemainingMillis = 0,
        )

        assertEquals(ActionBlock.PREVIEW_ENDED, ActionAvailability.forAction(UiAction.KEEP_PREVIEW, preview).block)
        assertTrue(ActionAvailability.forAction(UiAction.REVERT_PREVIEW, preview).enabled)
    }

    @Test
    fun saveCurrentRequiresValidTargetPermissionAndExplicitVisiblePictureConfirmation() {
        val context = readyContext()

        assertTrue(ActionAvailability.forAction(UiAction.SAVE_CURRENT_DISPLAY, context).enabled)
        assertEquals(
            ActionBlock.PICTURE_NOT_CONFIRMED,
            ActionAvailability.forAction(UiAction.CONFIRM_SAVE_CURRENT_DISPLAY, context).block,
        )
        assertTrue(
            ActionAvailability.forAction(
                UiAction.CONFIRM_SAVE_CURRENT_DISPLAY,
                context.copy(visiblePictureConfirmed = true),
            ).enabled,
        )
        assertEquals(
            ActionBlock.SAVE_CURRENT_UNSUPPORTED,
            ActionAvailability.forAction(
                UiAction.SAVE_CURRENT_DISPLAY,
                context.copy(saveCurrentRouteSupported = false),
            ).block,
        )
        assertEquals(
            ActionBlock.SAVE_CURRENT_SUPPORT_UNVERIFIED,
            ActionAvailability.forAction(
                UiAction.SAVE_CURRENT_DISPLAY,
                context.copy(saveCurrentRouteSupported = null),
            ).block,
        )
    }

    @Test
    fun operationReconciliationRequiresStatusReadScope() {
        val context = readyContext().copy(hasUnresolvedOperation = true)

        assertTrue(ActionAvailability.forAction(UiAction.CHECK_OPERATION, context).enabled)
        assertEquals(
            ActionBlock.MISSING_STATUS_PERMISSION,
            ActionAvailability.forAction(
                UiAction.CHECK_OPERATION,
                context.copy(grantedScopes = setOf("power.control", "display.control")),
            ).block,
        )
    }

    @Test
    fun sunshineRecoveryRequiresFreshStoppedStateAndAvailableCapability() {
        val context = readyContext()

        assertTrue(ActionAvailability.forAction(UiAction.RECOVER_SUNSHINE, context).enabled)
        assertEquals(
            ActionBlock.SUNSHINE_NOT_FRESHLY_STOPPED,
            ActionAvailability.forAction(
                UiAction.RECOVER_SUNSHINE,
                context.copy(sunshineState = SunshineState.RUNNING),
            ).block,
        )
        assertEquals(
            ActionBlock.STATUS_NOT_FRESH,
            ActionAvailability.forAction(
                UiAction.RECOVER_SUNSHINE,
                context.copy(statusFreshness = StatusFreshness.STALE),
            ).block,
        )
    }

    @Test
    fun mutationConflictsAndFullHistoryDoNotDisableReadOnlyOrWakeActions() {
        val context = readyContext().copy(anotherOperationInProgress = true)

        assertEquals(ActionBlock.OPERATION_IN_PROGRESS, ActionAvailability.forAction(UiAction.RESTART, context).block)
        assertTrue(ActionAvailability.forAction(UiAction.WAKE_DEVICE, context).enabled)
        assertTrue(ActionAvailability.forAction(UiAction.CHECK_OPERATION, context.copy(hasUnresolvedOperation = true)).enabled)
        assertFalse(ActionAvailability.forAction(UiAction.CHECK_OPERATION, context).enabled)
        assertEquals(
            ActionBlock.OPERATION_HISTORY_FULL,
            ActionAvailability.forAction(
                UiAction.RESTORE_SAVED_DISPLAY,
                context.copy(anotherOperationInProgress = false, operationHistoryHasRoom = false),
            ).block,
        )
    }

    @Test
    fun changedIdentityOrRejectedCredentialDisablesAuthenticatedControls() {
        for ((access, expectedBlock) in listOf(
            DeviceAccess.IDENTITY_CHANGED to ActionBlock.DEVICE_IDENTITY_CHANGED,
            DeviceAccess.CREDENTIAL_REJECTED to ActionBlock.DEVICE_CREDENTIAL_REJECTED,
        )) {
            val context = readyContext().copy(deviceAccess = access)
            val authenticatedActions = listOf(
                UiAction.CHECK_OPERATION,
                UiAction.RESTORE_SAVED_DISPLAY,
                UiAction.PREVIEW_DISPLAY_MODE,
                UiAction.REVERT_PREVIEW,
                UiAction.KEEP_PREVIEW,
                UiAction.SAVE_CURRENT_DISPLAY,
                UiAction.CONFIRM_SAVE_CURRENT_DISPLAY,
                UiAction.SUSPEND,
                UiAction.RESTART,
                UiAction.SHUT_DOWN,
                UiAction.RECOVER_SUNSHINE,
            )
            for (action in authenticatedActions) {
                assertEquals("$access must block $action", expectedBlock, ActionAvailability.forAction(action, context).block)
            }
            assertTrue("Device details stay open for recovery", ActionAvailability.forAction(UiAction.OPEN_DEVICE_DETAILS, context).enabled)
            assertTrue("Saved local Wake remains available", ActionAvailability.forAction(UiAction.WAKE_DEVICE, context).enabled)
        }
    }

    private fun readyContext() = ActionContext(
        hasActiveHost = true,
        grantedScopes = setOf("status.read", "display.control", "power.control"),
        statusFreshness = StatusFreshness.FRESH,
        capabilities = mapOf(
            "suspend" to WireValue.Known(CapabilityState.AVAILABLE),
            "restart" to WireValue.Known(CapabilityState.AVAILABLE),
            "shutdown" to WireValue.Known(CapabilityState.AVAILABLE),
            "sunshine_restart" to WireValue.Known(CapabilityState.AVAILABLE),
        ),
        wakeTargetConfigured = true,
        savedRecoveryProfileAvailable = true,
        saveCurrentRouteSupported = true,
        operationHistoryHasRoom = true,
        displayTargetValid = true,
        selectedModeValid = true,
        sunshineState = SunshineState.STOPPED,
    )
}
