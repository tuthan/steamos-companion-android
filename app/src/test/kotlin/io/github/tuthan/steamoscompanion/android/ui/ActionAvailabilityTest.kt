package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-product table test for the shared availability gate (touch, keyboard and accessibility paths all use it).
 *
 * Every action has a decision table: an ordered list of (block, condition) rows where the first row that applies names
 * the reason, and no row applying means the action is enabled. Each action also declares which context dimensions its
 * table reads. The test then
 *  - enumerates the full cross-product of those dimensions and requires [ActionAvailability] to agree with the table on
 *    every combination (state and reason),
 *  - sweeps every dimension the table does NOT read over all of its values and requires the answer never to change, so a
 *    new dependency cannot appear without the table being updated, and
 *  - checks the policy invariants (scope, identity, single lane, preview) directly over those same combinations.
 */
class ActionAvailabilityTest {
    // ---- dimensions ------------------------------------------------------------------------------------------------

    private class Dim(val name: String, val values: List<Pair<String, (ActionContext) -> ActionContext>>)

    private class Combination(val context: ActionContext, val dim: String?, val label: String?, val parent: Combination?) {
        fun describe(): String = generateSequence(this) { it.parent }.filter { it.dim != null }.toList().reversed()
            .joinToString(", ") { "${it.dim}=${it.label}" }
    }

    private val allScopes = listOf("status.read", "display.control", "power.control")
    private val scopeSets: List<Set<String>> =
        (0 until 8).map { mask -> allScopes.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet() }
    private val capabilityKeys = listOf("suspend", "restart", "shutdown", "sunshine_restart")

    private fun <T> dim(name: String, values: List<T>, label: (T) -> String = { it.toString() },
                        apply: ActionContext.(T) -> ActionContext) =
        Dim(name, values.map { value -> label(value) to { context: ActionContext -> context.apply(value) } })

    private fun capabilityValues(): List<Pair<String, WireValue<CapabilityState>?>> =
        listOf<Pair<String, WireValue<CapabilityState>?>>("missing" to null, "unknown-wire" to WireValue.Unknown("future")) +
            CapabilityState.entries.map { it.name to WireValue.Known(it) }

    private fun withCapability(context: ActionContext, key: String, value: WireValue<CapabilityState>?) =
        context.copy(capabilities = if (value == null) context.capabilities - key else context.capabilities + (key to value))

    /** The dimensions, with the capability pair built for the one key the action under test reads. */
    private fun dimensions(capabilityKey: String?): Map<String, Dim> {
        val dims = mutableListOf(
            dim("host", listOf(false, true)) { copy(hasActiveHost = it) },
            dim("access", DeviceAccess.entries) { copy(deviceAccess = it) },
            dim("scopes", scopeSets, { it.sorted().joinToString("+").ifEmpty { "none" } }) { copy(grantedScopes = it) },
            dim("freshness", StatusFreshness.entries) { copy(statusFreshness = it) },
            dim("preview", PreviewOwnership.entries) { copy(previewOwnership = it) },
            dim("remaining", listOf(null, 0L, 5_000L)) { copy(previewRemainingMillis = it) },
            dim("anotherOperation", listOf(false, true)) { copy(anotherOperationInProgress = it) },
            dim("historyRoom", listOf(false, true)) { copy(operationHistoryHasRoom = it) },
            dim("wakeTarget", listOf(false, true)) { copy(wakeTargetConfigured = it) },
            dim("savedProfile", listOf(false, true)) { copy(savedRecoveryProfileAvailable = it) },
            dim("unresolvedOperation", listOf(false, true)) { copy(hasUnresolvedOperation = it) },
            dim("displayTarget", listOf(false, true)) { copy(displayTargetValid = it) },
            dim("selectedMode", listOf(false, true)) { copy(selectedModeValid = it) },
            dim("modeIsCurrent", listOf(false, true)) { copy(selectedModeIsCurrent = it) },
            dim("saveRoute", listOf(null, false, true)) { copy(saveCurrentRouteSupported = it) },
            dim("pictureConfirmed", listOf(false, true)) { copy(visiblePictureConfirmed = it) },
            dim("sunshine", listOf<SunshineState?>(null) + SunshineState.entries, { it?.name ?: "null" }) {
                copy(sunshineState = it)
            },
        )
        if (capabilityKey != null) {
            dims += Dim("capability", capabilityValues().map { (label, value) ->
                label to { context: ActionContext -> withCapability(context, capabilityKey, value) }
            })
            dims += Dim("otherCapabilities", listOf("available" to CapabilityState.AVAILABLE,
                "unavailable" to CapabilityState.UNAVAILABLE).map { (label, state) ->
                label to { context: ActionContext ->
                    capabilityKeys.filter { it != capabilityKey }.fold(context) { acc, key ->
                        withCapability(acc, key, WireValue.Known(state))
                    }
                }
            })
        }
        return dims.associateBy { it.name }
    }

    /** The all-good context every dimension starts from: every action that can be enabled is enabled. */
    private fun readyContext() = ActionContext(
        hasActiveHost = true,
        grantedScopes = allScopes.toSet(),
        statusFreshness = StatusFreshness.FRESH,
        capabilities = capabilityKeys.associateWith { WireValue.Known(CapabilityState.AVAILABLE) },
        wakeTargetConfigured = true,
        savedRecoveryProfileAvailable = true,
        previewOwnership = PreviewOwnership.NONE,
        previewRemainingMillis = 5_000,
        anotherOperationInProgress = false,
        hasUnresolvedOperation = true,
        operationHistoryHasRoom = true,
        displayTargetValid = true,
        selectedModeValid = true,
        selectedModeIsCurrent = false,
        saveCurrentRouteSupported = true,
        visiblePictureConfirmed = true,
        sunshineState = SunshineState.STOPPED,
        deviceAccess = DeviceAccess.VERIFIED,
    )

    private fun combinations(dims: List<Dim>): Sequence<Combination> =
        dims.fold(sequenceOf(Combination(readyContext(), null, null, null))) { acc, dim ->
            acc.flatMap { parent ->
                dim.values.asSequence().map { (label, change) -> Combination(change(parent.context), dim.name, label, parent) }
            }
        }

    /** Message strings are built only on failure; the loops below run hundreds of thousands of times. */
    private inline fun verify(ok: Boolean, message: () -> String) {
        if (!ok) throw AssertionError(message())
    }

    // ---- the decision tables ---------------------------------------------------------------------------------------

    private class Row(val block: ActionBlock, val applies: (ActionContext) -> Boolean)

    private fun row(block: ActionBlock, applies: (ActionContext) -> Boolean) = Row(block, applies)

    private val noHost = row(ActionBlock.PAIRING_REQUIRED) { !it.hasActiveHost }
    private val identityChanged = row(ActionBlock.DEVICE_IDENTITY_CHANGED) { it.deviceAccess == DeviceAccess.IDENTITY_CHANGED }
    private val credentialRejected =
        row(ActionBlock.DEVICE_CREDENTIAL_REJECTED) { it.deviceAccess == DeviceAccess.CREDENTIAL_REJECTED }
    private fun scope(block: ActionBlock, name: String) = row(block) { name !in it.grantedScopes }
    private val statusScope = scope(ActionBlock.MISSING_STATUS_PERMISSION, "status.read")
    private val displayScope = scope(ActionBlock.MISSING_DISPLAY_PERMISSION, "display.control")
    private val powerScope = scope(ActionBlock.MISSING_POWER_PERMISSION, "power.control")
    private val statusNotFresh = row(ActionBlock.STATUS_NOT_FRESH) { it.statusFreshness != StatusFreshness.FRESH }
    private val laneBusy = row(ActionBlock.OPERATION_IN_PROGRESS) { it.anotherOperationInProgress }
    private val historyFull = row(ActionBlock.OPERATION_HISTORY_FULL) { !it.operationHistoryHasRoom }

    /** Any preview at all blocks the action, naming who owns it. */
    private val previewInTheWay = listOf(
        row(ActionBlock.PREVIEW_ACTIVE) { it.previewOwnership == PreviewOwnership.OWNED_HERE },
        row(ActionBlock.PREVIEW_OWNED_ELSEWHERE) { it.previewOwnership == PreviewOwnership.OTHER_CLIENT },
        row(ActionBlock.PREVIEW_STATE_UNKNOWN) { it.previewOwnership == PreviewOwnership.UNKNOWN },
    )

    /** The preview's own controls need a preview this phone owns; the reason otherwise says what is going on. */
    private val needsOwnPreview = listOf(
        row(ActionBlock.PREVIEW_NOT_ACTIVE) { it.previewOwnership == PreviewOwnership.NONE },
        row(ActionBlock.PREVIEW_OWNED_ELSEWHERE) { it.previewOwnership == PreviewOwnership.OTHER_CLIENT },
        row(ActionBlock.PREVIEW_STATE_UNKNOWN) { it.previewOwnership == PreviewOwnership.UNKNOWN },
    )

    private fun capabilityRows(key: String) = listOf(
        row(ActionBlock.CAPABILITY_UNVERIFIED) { context ->
            when (context.capabilities[key]) { null, is WireValue.Unknown -> true; is WireValue.Known -> false }
        },
        row(ActionBlock.CAPABILITY_UNAVAILABLE) { context ->
            val known = context.capabilities[key] as? WireValue.Known<*>
            known != null && known.value != CapabilityState.AVAILABLE
        },
    )

    private fun powerRows(key: String) =
        listOf(noHost, identityChanged, credentialRejected, powerScope, statusNotFresh) + capabilityRows(key) +
            previewInTheWay + listOf(laneBusy, historyFull)

    private fun saveRows(confirm: Boolean) =
        listOf(noHost, identityChanged, credentialRejected, displayScope,
            row(ActionBlock.DISPLAY_TARGET_NOT_CURRENT) { !it.displayTargetValid },
            row(ActionBlock.SAVE_CURRENT_SUPPORT_UNVERIFIED) { it.saveCurrentRouteSupported == null },
            row(ActionBlock.SAVE_CURRENT_UNSUPPORTED) { it.saveCurrentRouteSupported == false }) +
            previewInTheWay +
            (if (confirm) listOf(row(ActionBlock.PICTURE_NOT_CONFIRMED) { !it.visiblePictureConfirmed }) else emptyList()) +
            listOf(laneBusy, historyFull)

    private class Table(val rows: List<Row>, val reads: List<String>, val capabilityKey: String? = null)

    private val common = listOf("host", "access")

    private val tables: Map<UiAction, Table> = mapOf(
        UiAction.OPEN_DEVICE_DETAILS to Table(listOf(noHost), listOf("host")),
        UiAction.OPEN_DISPLAY_SETTINGS to Table(listOf(noHost), listOf("host")),
        UiAction.OPEN_POWER_OPTIONS to Table(listOf(noHost), listOf("host")),
        UiAction.WAKE_DEVICE to Table(
            listOf(noHost, row(ActionBlock.WAKE_NOT_CONFIGURED) { !it.wakeTargetConfigured }), listOf("host", "wakeTarget")),
        UiAction.CHECK_OPERATION to Table(
            listOf(noHost, identityChanged, credentialRejected, statusScope,
                row(ActionBlock.NO_UNRESOLVED_OPERATION) { !it.hasUnresolvedOperation }),
            common + listOf("scopes", "unresolvedOperation")),
        // Blind recovery: the saved profile is used whatever the status says, and another client's preview is the only
        // preview that blocks it.
        UiAction.RESTORE_SAVED_DISPLAY to Table(
            listOf(noHost, identityChanged, credentialRejected, displayScope,
                row(ActionBlock.NO_SAVED_PROFILE) { !it.savedRecoveryProfileAvailable },
                row(ActionBlock.PREVIEW_OWNED_ELSEWHERE) { it.previewOwnership == PreviewOwnership.OTHER_CLIENT },
                laneBusy, historyFull),
            common + listOf("scopes", "savedProfile", "preview", "anotherOperation", "historyRoom")),
        UiAction.PREVIEW_DISPLAY_MODE to Table(
            listOf(noHost, identityChanged, credentialRejected, displayScope,
                row(ActionBlock.DISPLAY_TARGET_NOT_CURRENT) { !it.displayTargetValid },
                row(ActionBlock.NO_VALID_MODE_SELECTED) { !it.selectedModeValid },
                row(ActionBlock.MODE_ALREADY_CURRENT) { it.selectedModeIsCurrent }) +
                previewInTheWay + listOf(laneBusy, historyFull),
            common + listOf("scopes", "displayTarget", "selectedMode", "modeIsCurrent", "preview", "anotherOperation",
                "historyRoom")),
        UiAction.REVERT_PREVIEW to Table(
            listOf(noHost, identityChanged, credentialRejected, displayScope) + needsOwnPreview +
                listOf(laneBusy, historyFull),
            common + listOf("scopes", "preview", "anotherOperation", "historyRoom")),
        UiAction.KEEP_PREVIEW to Table(
            listOf(noHost, identityChanged, credentialRejected, displayScope) + needsOwnPreview + listOf(
                row(ActionBlock.PREVIEW_DEADLINE_UNKNOWN) { it.previewRemainingMillis == null },
                row(ActionBlock.PREVIEW_ENDED) { (it.previewRemainingMillis ?: 1) <= 0 },
                laneBusy, historyFull),
            common + listOf("scopes", "preview", "remaining", "anotherOperation", "historyRoom")),
        UiAction.SAVE_CURRENT_DISPLAY to Table(saveRows(confirm = false),
            common + listOf("scopes", "displayTarget", "saveRoute", "preview", "anotherOperation", "historyRoom")),
        UiAction.CONFIRM_SAVE_CURRENT_DISPLAY to Table(saveRows(confirm = true),
            common + listOf("scopes", "displayTarget", "saveRoute", "preview", "pictureConfirmed", "anotherOperation",
                "historyRoom")),
        UiAction.SUSPEND to Table(powerRows("suspend"),
            common + listOf("scopes", "freshness", "capability", "preview", "anotherOperation", "historyRoom"), "suspend"),
        UiAction.RESTART to Table(powerRows("restart"),
            common + listOf("scopes", "freshness", "capability", "preview", "anotherOperation", "historyRoom"), "restart"),
        UiAction.SHUT_DOWN to Table(powerRows("shutdown"),
            common + listOf("scopes", "freshness", "capability", "preview", "anotherOperation", "historyRoom"), "shutdown"),
        UiAction.RECOVER_SUNSHINE to Table(
            listOf(noHost, identityChanged, credentialRejected, powerScope, statusNotFresh,
                row(ActionBlock.SUNSHINE_NOT_FRESHLY_STOPPED) { it.sunshineState != SunshineState.STOPPED }) +
                capabilityRows("sunshine_restart") + previewInTheWay + listOf(laneBusy, historyFull),
            common + listOf("scopes", "freshness", "sunshine", "capability", "preview", "anotherOperation", "historyRoom"),
            "sunshine_restart"),
    )

    private fun expected(table: Table, context: ActionContext): ActionState =
        table.rows.firstOrNull { it.applies(context) }?.let { ActionState(enabled = false, block = it.block) }
            ?: ActionState(enabled = true)

    private fun declared(action: UiAction): List<Dim> {
        val table = tables.getValue(action)
        val dims = dimensions(table.capabilityKey)
        return table.reads.map { name -> dims.getValue(name) }
    }

    // ---- tests -----------------------------------------------------------------------------------------------------

    @Test fun everyActionHasATableAndEveryTableNamesRealDimensions() {
        assertEquals(UiAction.entries.toSet(), tables.keys)
        for ((action, table) in tables) {
            val known = dimensions(table.capabilityKey).keys
            assertTrue("$action reads an unknown dimension", known.containsAll(table.reads))
            assertEquals("$action lists a dimension twice", table.reads.size, table.reads.toSet().size)
        }
    }

    @Test fun theGateAgreesWithTheTableOnEveryCombinationOfWhatEachActionReads() {
        var checked = 0L
        for (action in UiAction.entries) {
            val table = tables.getValue(action)
            for (combination in combinations(declared(action))) {
                val actual = ActionAvailability.forAction(action, combination.context)
                val wanted = expected(table, combination.context)
                verify(actual == wanted) { "$action with ${combination.describe()}: expected $wanted but was $actual" }
                checked++
            }
        }
        assertTrue("the cross-product ran ($checked combinations)", checked > 200_000)
    }

    @Test fun dimensionsAnActionDoesNotReadNeverChangeItsAnswer() {
        for (action in UiAction.entries) {
            val table = tables.getValue(action)
            val unread = dimensions(table.capabilityKey).values.filter { it.name !in table.reads }
            // Every unread dimension is swept over all its values on top of every combination of the read ones.
            for (combination in combinations(declared(action))) {
                val base = ActionAvailability.forAction(action, combination.context)
                for (dimension in unread) {
                    for ((label, change) in dimension.values) {
                        val actual = ActionAvailability.forAction(action, change(combination.context))
                        verify(actual == base) {
                            "$action depends on ${dimension.name}=$label, which its table does not list " +
                                "(with ${combination.describe()}: $base became $actual)"
                        }
                    }
                }
            }
        }
    }

    @Test fun everyBlockReasonIsReachableAndNothingElseIsEverProduced() {
        val seen = mutableSetOf<ActionBlock>()
        for (action in UiAction.entries) {
            for (combination in combinations(declared(action))) {
                ActionAvailability.forAction(action, combination.context).block?.let(seen::add)
            }
        }
        assertEquals("a reason the gate can no longer produce is a stale label", ActionBlock.entries.toSet(), seen)
    }

    // ---- policy invariants, checked over the same combinations -----------------------------------------------------

    private val displayActions = setOf(UiAction.RESTORE_SAVED_DISPLAY, UiAction.PREVIEW_DISPLAY_MODE, UiAction.REVERT_PREVIEW,
        UiAction.KEEP_PREVIEW, UiAction.SAVE_CURRENT_DISPLAY, UiAction.CONFIRM_SAVE_CURRENT_DISPLAY)
    private val powerActions = setOf(UiAction.SUSPEND, UiAction.RESTART, UiAction.SHUT_DOWN, UiAction.RECOVER_SUNSHINE)
    private val authenticated = displayActions + powerActions + UiAction.CHECK_OPERATION
    private val mutations = displayActions + powerActions
    private val blockedByAnyPreview = setOf(UiAction.PREVIEW_DISPLAY_MODE, UiAction.SAVE_CURRENT_DISPLAY,
        UiAction.CONFIRM_SAVE_CURRENT_DISPLAY) + powerActions

    private inline fun forEveryCombination(check: (UiAction, ActionContext, ActionState) -> Unit) {
        for (action in UiAction.entries) for (combination in combinations(declared(action))) {
            check(action, combination.context, ActionAvailability.forAction(action, combination.context))
        }
    }

    @Test fun aGrantNeverEnablesWhatItDoesNotCover() {
        forEveryCombination { action, context, state ->
            if (state.enabled) {
                if (action in displayActions) verify("display.control" in context.grantedScopes) { "$action needs display.control" }
                if (action in powerActions) verify("power.control" in context.grantedScopes) { "$action needs power.control" }
                if (action == UiAction.CHECK_OPERATION) verify("status.read" in context.grantedScopes) { "$action needs status.read" }
            }
        }
        // A view-only grant (status.read alone) leaves Wake and the screens, and nothing that changes the device.
        val viewOnly = readyContext().copy(grantedScopes = setOf("status.read"))
        for (action in UiAction.entries) {
            assertEquals("view-only $action", action !in mutations, ActionAvailability.forAction(action, viewOnly).enabled)
        }
    }

    @Test fun aChangedIdentityOrRejectedCredentialDisablesEveryAuthenticatedControlAndOnlyThose() {
        forEveryCombination { action, context, state ->
            if (context.deviceAccess != DeviceAccess.VERIFIED && context.hasActiveHost && action in authenticated) {
                val reason = if (context.deviceAccess == DeviceAccess.IDENTITY_CHANGED) ActionBlock.DEVICE_IDENTITY_CHANGED
                else ActionBlock.DEVICE_CREDENTIAL_REJECTED
                verify(!state.enabled && state.block == reason) { "$action with ${context.deviceAccess} gave $state" }
            }
        }
        // Device details stay open for recovery, and the saved local Wake target still works.
        for (access in DeviceAccess.entries) {
            val context = readyContext().copy(deviceAccess = access)
            for (action in UiAction.entries - authenticated) {
                assertTrue("$action with $access", ActionAvailability.forAction(action, context).enabled)
            }
        }
    }

    @Test fun oneMutationAtATimeAndNeverOverAnotherPreview() {
        forEveryCombination { action, context, state ->
            if (state.enabled && action in mutations) {
                verify(!context.anotherOperationInProgress) { "$action while another operation runs" }
                verify(context.operationHistoryHasRoom) { "$action with a full journal" }
                if (action in blockedByAnyPreview) {
                    verify(context.previewOwnership == PreviewOwnership.NONE) { "$action during a preview" }
                }
                if (action == UiAction.RESTORE_SAVED_DISPLAY) {
                    verify(context.previewOwnership != PreviewOwnership.OTHER_CLIENT) {
                        "recovery must not override another client's preview"
                    }
                }
            }
        }
    }

    @Test fun powerNeedsAFreshReadingAndAnAvailableCapability() {
        forEveryCombination { action, context, state ->
            if (state.enabled && action in powerActions) {
                verify(context.statusFreshness == StatusFreshness.FRESH) { "$action on ${context.statusFreshness}" }
                val key = tables.getValue(action).capabilityKey!!
                verify(context.capabilities[key] == WireValue.Known(CapabilityState.AVAILABLE)) {
                    "$action with capability ${context.capabilities[key]}"
                }
            }
        }
    }

    @Test fun wakeAndRecoveryDoNotWaitForARemoteReading() {
        for (freshness in StatusFreshness.entries) {
            val context = readyContext().copy(statusFreshness = freshness)
            assertTrue("Wake on $freshness", ActionAvailability.forAction(UiAction.WAKE_DEVICE, context).enabled)
            assertTrue("Restore saved display on $freshness",
                ActionAvailability.forAction(UiAction.RESTORE_SAVED_DISPLAY, context).enabled)
        }
        for (configured in listOf(false, true)) {
            for (host in listOf(false, true)) {
                assertEquals(host && configured, ActionAvailability.forAction(UiAction.WAKE_DEVICE,
                    readyContext().copy(hasActiveHost = host, wakeTargetConfigured = configured)).enabled)
            }
        }
    }

    @Test fun anEndedPreviewKeepsRevertAsTheExplicitRecoveryAction() {
        val ended = readyContext().copy(previewOwnership = PreviewOwnership.OWNED_HERE, previewRemainingMillis = 0)
        assertEquals(ActionBlock.PREVIEW_ENDED, ActionAvailability.forAction(UiAction.KEEP_PREVIEW, ended).block)
        assertTrue(ActionAvailability.forAction(UiAction.REVERT_PREVIEW, ended).enabled)
        assertTrue(ActionAvailability.forAction(UiAction.RESTORE_SAVED_DISPLAY, ended).enabled)
    }
}
