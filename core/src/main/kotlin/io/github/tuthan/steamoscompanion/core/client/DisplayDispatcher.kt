package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.DisplayOutputSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayPreviewSummary
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

sealed interface DisplayDispatchResult {
    data class Blocked(val reason: DisplayBlock) : DisplayDispatchResult
    data class Dispatched(val result: JournalDispatchResult) : DisplayDispatchResult
}

/** Capture when the save-current sheet opens; pass only after the user confirms the physical picture. */
data class SaveCurrentConfirmation(
    val target: StatusObservationSource,
    val outputId: String,
    val outputGeneration: Long,
    val currentModeId: String,
) {
    companion object {
        fun forOutput(host: ActiveHost, output: DisplayOutputSummary): SaveCurrentConfirmation? {
            val current = output.currentModeId?.takeIf { id -> output.modes.any { it.id == id } } ?: return null
            return SaveCurrentConfirmation(StatusObservationSource.from(host), output.id, output.generation, current)
        }
    }
}

/** Capture the exact host preview shown when the owner confirms the visible picture. */
data class KeepPreviewConfirmation(
    val target: StatusObservationSource,
    val previewId: String,
    val outputId: String,
    val outputGeneration: Long,
    val targetModeId: String,
    val deadlineSeconds: String,
) {
    companion object {
        fun forPreview(host: ActiveHost, preview: DisplayPreviewSummary): KeepPreviewConfirmation =
            KeepPreviewConfirmation(StatusObservationSource.from(host), preview.id, preview.outputId,
                preview.generation, preview.targetMode.id, preview.deadlineSeconds)
    }

    fun matches(preview: DisplayPreviewSummary): Boolean =
        previewId == preview.id && outputId == preview.outputId &&
            outputGeneration == preview.generation && targetModeId == preview.targetMode.id &&
            deadlineSeconds == preview.deadlineSeconds
}

enum class DisplayBlock {
    PAIRING_CHANGED, STORE_UNAVAILABLE, MISSING_SCOPE, STATUS_NOT_FRESH,
    OUTPUTS_UNAVAILABLE, STALE_SELECTION, MODE_ALREADY_CURRENT, PREVIEW_ACTIVE,
    PREVIEW_NOT_OWNED, PREVIEW_DEADLINE_UNKNOWN, PREVIEW_ENDED,
    PICTURE_NOT_CONFIRMED, CONFIRMATION_MISMATCH, PROFILE_MISSING, OPERATION_UNRESOLVED,
    OBSERVATION_PAIRING_CHANGED,
}

/** Dispatches distinct host-owned display transactions with a durable journal record. */
class DisplayDispatcher(
    private val repository: EncryptedStateRepository,
    private val journal: OperationJournal,
    private val clock: Clock,
) {
    fun preview(
        host: ActiveHost, observation: StatusPollState, selection: DisplaySelection, sender: JournalMutationSender,
    ): DisplayDispatchResult {
        val checked = context(host, observation, requireFresh = true)
        val context = checked.context ?: return blocked(requireNotNull(checked.block))
        val inventory = context.inventory ?: return blocked(DisplayBlock.OUTPUTS_UNAVAILABLE)
        if (inventory.preview != null) return blocked(DisplayBlock.PREVIEW_ACTIVE)
        if (context.hasUnresolved()) return blocked(DisplayBlock.OPERATION_UNRESOLVED)
        val output = inventory.outputs.firstOrNull { it.id == selection.outputId && it.generation == selection.outputGeneration }
            ?: return blocked(DisplayBlock.STALE_SELECTION)
        val mode = output.modes.firstOrNull { it.id == selection.modeId } ?: return blocked(DisplayBlock.STALE_SELECTION)
        if (mode.id == output.currentModeId) return blocked(DisplayBlock.MODE_ALREADY_CURRENT)
        return dispatch(host, ApiRoute.DISPLAY_PREVIEW, sender,
            "output_id" to string(output.id), "mode_id" to string(mode.id), "generation" to number(output.generation))
    }

    fun saveCurrent(
        host: ActiveHost, observation: StatusPollState, confirmation: SaveCurrentConfirmation?,
        sender: JournalMutationSender,
    ): DisplayDispatchResult {
        if (confirmation == null) return blocked(DisplayBlock.PICTURE_NOT_CONFIRMED)
        if (confirmation.target != StatusObservationSource.from(host)) return blocked(DisplayBlock.CONFIRMATION_MISMATCH)
        val checked = context(host, observation, requireFresh = true)
        val context = checked.context ?: return blocked(requireNotNull(checked.block))
        val inventory = context.inventory ?: return blocked(DisplayBlock.OUTPUTS_UNAVAILABLE)
        if (inventory.preview != null) return blocked(DisplayBlock.PREVIEW_ACTIVE)
        if (context.hasUnresolved()) return blocked(DisplayBlock.OPERATION_UNRESOLVED)
        val output = inventory.outputs.firstOrNull {
            it.id == confirmation.outputId && it.generation == confirmation.outputGeneration
        } ?: return blocked(DisplayBlock.STALE_SELECTION)
        if (output.currentModeId != confirmation.currentModeId || output.modes.none { it.id == confirmation.currentModeId }) {
            return blocked(DisplayBlock.STALE_SELECTION)
        }
        return dispatch(host, ApiRoute.DISPLAY_SAVE_CURRENT, sender,
            "output_id" to string(output.id), "generation" to number(output.generation),
            "visible" to JsonValue.BooleanValue(true))
    }

    /** Uses the last known exact profile, including when status is currently unreachable. */
    fun restoreSaved(
        host: ActiveHost, observation: StatusPollState, profileId: String, sender: JournalMutationSender,
    ): DisplayDispatchResult {
        val checked = context(host, observation, requireFresh = false)
        val context = checked.context ?: return blocked(requireNotNull(checked.block))
        val inventory = context.inventory
        if (inventory?.preview != null && !context.ownsPreview()) return blocked(DisplayBlock.PREVIEW_NOT_OWNED)
        if (context.hasUnresolved(exceptOwnedPreview = inventory?.preview != null)) {
            return blocked(DisplayBlock.OPERATION_UNRESOLVED)
        }
        val profiles = if (inventory != null && !observation.unreachable && !observation.stale) {
            inventory.profiles
        } else context.host.cachedRecoveryProfiles ?: inventory?.profiles
        if (profiles == null) return blocked(DisplayBlock.OUTPUTS_UNAVAILABLE)
        if (profiles.none { it.id == profileId }) return blocked(DisplayBlock.PROFILE_MISSING)
        return dispatch(host, ApiRoute.DISPLAY_RESTORE, sender,
            "source" to string("verified"), "profile_id" to string(profileId))
    }

    fun revertPreview(host: ActiveHost, observation: StatusPollState, sender: JournalMutationSender): DisplayDispatchResult {
        val checked = context(host, observation, requireFresh = false)
        val context = checked.context ?: return blocked(requireNotNull(checked.block))
        if (!context.ownsPreview()) return blocked(DisplayBlock.PREVIEW_NOT_OWNED)
        if (context.hasUnresolved(exceptOwnedPreview = true)) return blocked(DisplayBlock.OPERATION_UNRESOLVED)
        return dispatch(host, ApiRoute.DISPLAY_RESTORE, sender, "source" to string("preview"))
    }

    fun keepPreview(
        host: ActiveHost, observation: StatusPollState, confirmation: KeepPreviewConfirmation?, sender: JournalMutationSender,
    ): DisplayDispatchResult {
        if (confirmation == null) return blocked(DisplayBlock.PICTURE_NOT_CONFIRMED)
        if (confirmation.target != StatusObservationSource.from(host)) return blocked(DisplayBlock.CONFIRMATION_MISMATCH)
        val checked = context(host, observation, requireFresh = true)
        val context = checked.context ?: return blocked(requireNotNull(checked.block))
        val preview = context.inventory?.preview ?: return blocked(DisplayBlock.PREVIEW_NOT_OWNED)
        if (!confirmation.matches(preview)) return blocked(DisplayBlock.CONFIRMATION_MISMATCH)
        if (!context.ownsPreview()) return blocked(DisplayBlock.PREVIEW_NOT_OWNED)
        if (context.hasUnresolved(exceptOwnedPreview = true)) return blocked(DisplayBlock.OPERATION_UNRESOLVED)
        val deadlineMillis = try {
            BigDecimal(preview.deadlineSeconds).movePointRight(3).setScale(0, RoundingMode.DOWN).longValueExact()
        } catch (_: Exception) { return blocked(DisplayBlock.PREVIEW_DEADLINE_UNKNOWN) }
        val anchor = HostDeadlineAnchor.fromHostReply(deadlineMillis, observation.lastRead?.outputsTiming)
            ?: return blocked(DisplayBlock.PREVIEW_DEADLINE_UNKNOWN)
        if (anchor.remainingMillis(clock.monotonicNanos()) <= 0) return blocked(DisplayBlock.PREVIEW_ENDED)
        return dispatch(host, ApiRoute.DISPLAY_CONFIRM, sender,
            "preview_id" to string(preview.id), "visible" to JsonValue.BooleanValue(true))
    }

    private data class CheckedContext(val context: Context? = null, val block: DisplayBlock? = null)

    private fun context(host: ActiveHost, observation: StatusPollState, requireFresh: Boolean): CheckedContext {
        val state = when (val loaded = repository.load()) {
            is StateLoadResult.Ready -> loaded.state
            else -> return CheckedContext(block = DisplayBlock.STORE_UNAVAILABLE)
        }
        val active = state.activeHost
        if (active == null || !samePairing(active, host)) {
            return CheckedContext(block = DisplayBlock.PAIRING_CHANGED)
        }
        if ("display.control" !in active.grantedScopes) {
            return CheckedContext(block = DisplayBlock.MISSING_SCOPE)
        }
        val read = observation.lastRead
        if (read == null) {
            return if (requireFresh) CheckedContext(block = DisplayBlock.OUTPUTS_UNAVAILABLE)
            else CheckedContext(context = Context(state, active, null))
        }
        if (read.status.hostId != host.hostId) {
            if (!requireFresh && active.cachedRecoveryProfiles != null) {
                return CheckedContext(context = Context(state, active, null))
            }
            return CheckedContext(block = DisplayBlock.OUTPUTS_UNAVAILABLE)
        }
        if (observation.source != StatusObservationSource.from(active)) {
            if (!requireFresh && active.cachedRecoveryProfiles != null) {
                return CheckedContext(context = Context(state, active, null))
            }
            return CheckedContext(block = DisplayBlock.OBSERVATION_PAIRING_CHANGED)
        }
        if (requireFresh) {
            val age = observation.lastSuccessMonotonicNanos?.let { clock.monotonicNanos() - it }
            if (observation.unreachable || observation.stale || age == null || age < 0 || age > FRESH_NANOS) {
                return CheckedContext(block = DisplayBlock.STATUS_NOT_FRESH)
            }
        }
        return CheckedContext(context = Context(state, active, read.outputs?.takeIf { it.available && !it.legacyShape }))
    }

    private data class Context(
        val state: PersistentState,
        val host: ActiveHost,
        val inventory: VersionedPayload.DisplayOutputs?,
    ) {
        fun ownsPreview(): Boolean {
            val id = inventory?.preview?.id ?: return false
            return state.journal.any {
                it.belongsTo(host) &&
                    it.path == ApiRoute.DISPLAY_PREVIEW.path && it.operationId == id
            }
        }

        fun hasUnresolved(exceptOwnedPreview: Boolean = false): Boolean = state.journal.any {
            it.mayConflictWith(host) && it.phase.unresolved &&
                !(exceptOwnedPreview && it.belongsTo(host) && it.path == ApiRoute.DISPLAY_PREVIEW.path &&
                    it.operationId == inventory?.preview?.id)
        }
    }

    private fun dispatch(
        host: ActiveHost, route: ApiRoute, sender: JournalMutationSender, vararg fields: Pair<String, JsonValue>,
    ): DisplayDispatchResult {
        val request = ApiRequest(HttpMethod.POST, route.path,
            jsonObject("request_id" to string(UUID.randomUUID().toString()), *fields), host.bearerToken)
        val result = journal.dispatchForHost(host, request, sender)
        return if (result == JournalDispatchResult.NotDispatched(StateInvariantCode.OPERATION_UNRESOLVED)) {
            blocked(DisplayBlock.OPERATION_UNRESOLVED)
        } else DisplayDispatchResult.Dispatched(result)
    }

    private fun samePairing(a: ActiveHost, b: ActiveHost): Boolean =
        a.hostId == b.hostId && a.clientId == b.clientId && a.endpoint == b.endpoint &&
            a.certificatePin == b.certificatePin && a.pairedAtMillis == b.pairedAtMillis && a.bearerToken == b.bearerToken

    private fun blocked(reason: DisplayBlock) = DisplayDispatchResult.Blocked(reason)
    private fun string(value: String) = JsonValue.StringValue(value)
    private fun number(value: Long) = JsonValue.NumberValue(value.toString())

    private companion object { const val FRESH_NANOS = 15_000_000_000L }
}
