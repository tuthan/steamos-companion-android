package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue

/**
 * Pure presentation gate shared by touch, keyboard, and accessibility activation paths.
 * The service and protocol layers must still validate every dispatched request.
 */
object ActionAvailability {
    fun forAction(action: UiAction, context: ActionContext): ActionState = when (action) {
        UiAction.OPEN_DEVICE_DETAILS -> paired(context)
        UiAction.OPEN_DISPLAY_SETTINGS -> paired(context)
        UiAction.OPEN_POWER_OPTIONS -> paired(context)
        UiAction.CHECK_OPERATION -> when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
            !context.hasScope(STATUS_SCOPE) -> blocked(ActionBlock.MISSING_STATUS_PERMISSION)
            !context.hasUnresolvedOperation -> blocked(ActionBlock.NO_UNRESOLVED_OPERATION)
            else -> ALLOWED
        }
        UiAction.WAKE_DEVICE -> when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            !context.wakeTargetConfigured -> blocked(ActionBlock.WAKE_NOT_CONFIGURED)
            else -> ALLOWED
        }
        UiAction.RESTORE_SAVED_DISPLAY -> when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
            !context.hasScope(DISPLAY_SCOPE) -> blocked(ActionBlock.MISSING_DISPLAY_PERMISSION)
            !context.savedRecoveryProfileAvailable -> blocked(ActionBlock.NO_SAVED_PROFILE)
            context.previewOwnership == PreviewOwnership.OTHER_CLIENT -> blocked(ActionBlock.PREVIEW_OWNED_ELSEWHERE)
            else -> mutationLane(context)
        }
        UiAction.PREVIEW_DISPLAY_MODE -> when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
            !context.hasScope(DISPLAY_SCOPE) -> blocked(ActionBlock.MISSING_DISPLAY_PERMISSION)
            !context.displayTargetValid -> blocked(ActionBlock.DISPLAY_TARGET_NOT_CURRENT)
            !context.selectedModeValid -> blocked(ActionBlock.NO_VALID_MODE_SELECTED)
            context.selectedModeIsCurrent -> blocked(ActionBlock.MODE_ALREADY_CURRENT)
            context.previewOwnership != PreviewOwnership.NONE -> previewBlock(context.previewOwnership)
            else -> mutationLane(context)
        }
        UiAction.REVERT_PREVIEW -> previewRecoveryAction(context)
        UiAction.KEEP_PREVIEW -> when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
            !context.hasScope(DISPLAY_SCOPE) -> blocked(ActionBlock.MISSING_DISPLAY_PERMISSION)
            context.previewOwnership != PreviewOwnership.OWNED_HERE -> previewBlock(context.previewOwnership)
            context.previewRemainingMillis == null -> blocked(ActionBlock.PREVIEW_DEADLINE_UNKNOWN)
            context.previewRemainingMillis <= 0 -> blocked(ActionBlock.PREVIEW_ENDED)
            context.anotherOperationInProgress -> blocked(ActionBlock.OPERATION_IN_PROGRESS)
            !context.operationHistoryHasRoom -> blocked(ActionBlock.OPERATION_HISTORY_FULL)
            else -> ALLOWED
        }
        UiAction.SAVE_CURRENT_DISPLAY -> saveCurrentDisplay(context, requireConfirmation = false)
        UiAction.CONFIRM_SAVE_CURRENT_DISPLAY -> saveCurrentDisplay(context, requireConfirmation = true)
        UiAction.SUSPEND -> powerAction(context, "suspend")
        UiAction.RESTART -> powerAction(context, "restart")
        UiAction.SHUT_DOWN -> powerAction(context, "shutdown")
        UiAction.RECOVER_SUNSHINE -> recoverSunshine(context)
    }

    private fun previewRecoveryAction(context: ActionContext): ActionState = when {
        !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
        deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
        !context.hasScope(DISPLAY_SCOPE) -> blocked(ActionBlock.MISSING_DISPLAY_PERMISSION)
        context.previewOwnership != PreviewOwnership.OWNED_HERE -> previewBlock(context.previewOwnership)
        context.anotherOperationInProgress -> blocked(ActionBlock.OPERATION_IN_PROGRESS)
        !context.operationHistoryHasRoom -> blocked(ActionBlock.OPERATION_HISTORY_FULL)
        else -> ALLOWED
    }

    private fun saveCurrentDisplay(context: ActionContext, requireConfirmation: Boolean): ActionState = when {
        !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
        deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
        !context.hasScope(DISPLAY_SCOPE) -> blocked(ActionBlock.MISSING_DISPLAY_PERMISSION)
        !context.displayTargetValid -> blocked(ActionBlock.DISPLAY_TARGET_NOT_CURRENT)
        context.saveCurrentRouteSupported == null -> blocked(ActionBlock.SAVE_CURRENT_SUPPORT_UNVERIFIED)
        context.saveCurrentRouteSupported == false -> blocked(ActionBlock.SAVE_CURRENT_UNSUPPORTED)
        context.previewOwnership != PreviewOwnership.NONE -> previewBlock(context.previewOwnership)
        requireConfirmation && !context.visiblePictureConfirmed -> blocked(ActionBlock.PICTURE_NOT_CONFIRMED)
        context.anotherOperationInProgress -> blocked(ActionBlock.OPERATION_IN_PROGRESS)
        !context.operationHistoryHasRoom -> blocked(ActionBlock.OPERATION_HISTORY_FULL)
        else -> ALLOWED
    }

    private fun powerAction(context: ActionContext, capability: String): ActionState {
        val capabilityIssue = capabilityBlock(context, capability)
        return when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
            !context.hasScope(POWER_SCOPE) -> blocked(ActionBlock.MISSING_POWER_PERMISSION)
            context.statusFreshness != StatusFreshness.FRESH -> blocked(ActionBlock.STATUS_NOT_FRESH)
            capabilityIssue != null -> blocked(capabilityIssue)
            context.previewOwnership != PreviewOwnership.NONE -> previewBlock(context.previewOwnership)
            else -> mutationLane(context)
        }
    }

    private fun recoverSunshine(context: ActionContext): ActionState {
        val capabilityIssue = capabilityBlock(context, "sunshine_restart")
        return when {
            !context.hasActiveHost -> blocked(ActionBlock.PAIRING_REQUIRED)
            deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
            !context.hasScope(POWER_SCOPE) -> blocked(ActionBlock.MISSING_POWER_PERMISSION)
            context.statusFreshness != StatusFreshness.FRESH -> blocked(ActionBlock.STATUS_NOT_FRESH)
            context.sunshineState != SunshineState.STOPPED -> blocked(ActionBlock.SUNSHINE_NOT_FRESHLY_STOPPED)
            capabilityIssue != null -> blocked(capabilityIssue)
            context.previewOwnership != PreviewOwnership.NONE -> previewBlock(context.previewOwnership)
            else -> mutationLane(context)
        }
    }

    private fun capabilityBlock(context: ActionContext, key: String): ActionBlock? = when (val value = context.capabilities[key]) {
        null, is WireValue.Unknown -> ActionBlock.CAPABILITY_UNVERIFIED
        is WireValue.Known -> when (value.value) {
            CapabilityState.AVAILABLE -> null
            CapabilityState.UNAVAILABLE,
            CapabilityState.UNSUPPORTED,
            CapabilityState.UNVERIFIED,
            CapabilityState.DISABLED,
            -> ActionBlock.CAPABILITY_UNAVAILABLE
        }
    }

    private fun mutationLane(context: ActionContext): ActionState = when {
        deviceAccessBlock(context) != null -> blocked(requireNotNull(deviceAccessBlock(context)))
        context.anotherOperationInProgress -> blocked(ActionBlock.OPERATION_IN_PROGRESS)
        !context.operationHistoryHasRoom -> blocked(ActionBlock.OPERATION_HISTORY_FULL)
        else -> ALLOWED
    }

    private fun previewBlock(owner: PreviewOwnership): ActionState = blocked(
        when (owner) {
            PreviewOwnership.NONE -> ActionBlock.PREVIEW_NOT_ACTIVE
            PreviewOwnership.OWNED_HERE -> ActionBlock.PREVIEW_ACTIVE
            PreviewOwnership.OTHER_CLIENT -> ActionBlock.PREVIEW_OWNED_ELSEWHERE
            PreviewOwnership.UNKNOWN -> ActionBlock.PREVIEW_STATE_UNKNOWN
        },
    )

    private fun paired(context: ActionContext): ActionState =
        if (context.hasActiveHost) ALLOWED else blocked(ActionBlock.PAIRING_REQUIRED)

    private fun deviceAccessBlock(context: ActionContext): ActionBlock? = when (context.deviceAccess) {
        DeviceAccess.VERIFIED -> null
        DeviceAccess.IDENTITY_CHANGED -> ActionBlock.DEVICE_IDENTITY_CHANGED
        DeviceAccess.CREDENTIAL_REJECTED -> ActionBlock.DEVICE_CREDENTIAL_REJECTED
    }

    private fun ActionContext.hasScope(scope: String): Boolean = scope in grantedScopes

    private fun blocked(reason: ActionBlock) = ActionState(enabled = false, block = reason)

    private val ALLOWED = ActionState(enabled = true)
    private const val STATUS_SCOPE = "status.read"
    private const val DISPLAY_SCOPE = "display.control"
    private const val POWER_SCOPE = "power.control"
}

enum class UiAction {
    OPEN_DEVICE_DETAILS,
    OPEN_DISPLAY_SETTINGS,
    OPEN_POWER_OPTIONS,
    CHECK_OPERATION,
    WAKE_DEVICE,
    RESTORE_SAVED_DISPLAY,
    PREVIEW_DISPLAY_MODE,
    REVERT_PREVIEW,
    KEEP_PREVIEW,
    SAVE_CURRENT_DISPLAY,
    CONFIRM_SAVE_CURRENT_DISPLAY,
    SUSPEND,
    RESTART,
    SHUT_DOWN,
    RECOVER_SUNSHINE,
}

data class ActionState(val enabled: Boolean, val block: ActionBlock? = null) {
    init {
        require(enabled == (block == null)) { "An enabled action has no block reason, and a blocked action must have one" }
    }
}

enum class ActionBlock {
    PAIRING_REQUIRED,
    NO_UNRESOLVED_OPERATION,
    MISSING_STATUS_PERMISSION,
    WAKE_NOT_CONFIGURED,
    MISSING_DISPLAY_PERMISSION,
    MISSING_POWER_PERMISSION,
    NO_SAVED_PROFILE,
    STATUS_NOT_FRESH,
    CAPABILITY_UNVERIFIED,
    CAPABILITY_UNAVAILABLE,
    PREVIEW_NOT_ACTIVE,
    PREVIEW_ACTIVE,
    PREVIEW_OWNED_ELSEWHERE,
    PREVIEW_STATE_UNKNOWN,
    PREVIEW_DEADLINE_UNKNOWN,
    PREVIEW_ENDED,
    DISPLAY_TARGET_NOT_CURRENT,
    NO_VALID_MODE_SELECTED,
    MODE_ALREADY_CURRENT,
    SAVE_CURRENT_SUPPORT_UNVERIFIED,
    SAVE_CURRENT_UNSUPPORTED,
    PICTURE_NOT_CONFIRMED,
    OPERATION_IN_PROGRESS,
    OPERATION_HISTORY_FULL,
    SUNSHINE_NOT_FRESHLY_STOPPED,
    DEVICE_IDENTITY_CHANGED,
    DEVICE_CREDENTIAL_REJECTED,
}

enum class StatusFreshness { NEVER_CHECKED, FRESH, STALE, UNREACHABLE }

enum class PreviewOwnership { NONE, OWNED_HERE, OTHER_CLIENT, UNKNOWN }

enum class DeviceAccess { VERIFIED, IDENTITY_CHANGED, CREDENTIAL_REJECTED }

data class ActionContext(
    val hasActiveHost: Boolean = false,
    val grantedScopes: Set<String> = emptySet(),
    val statusFreshness: StatusFreshness = StatusFreshness.NEVER_CHECKED,
    val capabilities: Map<String, WireValue<CapabilityState>> = emptyMap(),
    val wakeTargetConfigured: Boolean = false,
    val savedRecoveryProfileAvailable: Boolean = false,
    val previewOwnership: PreviewOwnership = PreviewOwnership.NONE,
    val previewRemainingMillis: Long? = null,
    /** A different mutation owns the lane; an exact preview's own controls are handled separately. */
    val anotherOperationInProgress: Boolean = false,
    val hasUnresolvedOperation: Boolean = false,
    val operationHistoryHasRoom: Boolean = true,
    val displayTargetValid: Boolean = false,
    val selectedModeValid: Boolean = false,
    val selectedModeIsCurrent: Boolean = false,
    /** Null means the client has not yet learned whether the host implements save-current. */
    val saveCurrentRouteSupported: Boolean? = null,
    /** Set only by the explicit confirmation action after the owner checks the physical picture. */
    val visiblePictureConfirmed: Boolean = false,
    val sunshineState: SunshineState? = null,
    /** Authenticated actions stay disabled after pin/identity changes or rejected credentials. */
    val deviceAccess: DeviceAccess = DeviceAccess.VERIFIED,
)
