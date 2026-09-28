package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.util.UUID

enum class PowerVerb(val wire: String) { SUSPEND("suspend"), RESTART("restart"), SHUTDOWN("shutdown") }

/** Captured when the exact target and consequence are shown in the confirmation sheet. */
data class PowerConfirmation(
    val target: StatusObservationSource,
    val verb: PowerVerb,
) {
    companion object {
        /** Capture the credential shown on the confirmation sheet without retaining its bearer. */
        fun forHost(host: ActiveHost, verb: PowerVerb) = PowerConfirmation(StatusObservationSource.from(host), verb)
    }
}

sealed interface ControlDispatchResult {
    data class Blocked(val reason: ControlBlock) : ControlDispatchResult
    data class Dispatched(val result: JournalDispatchResult) : ControlDispatchResult
}

enum class ControlBlock {
    PAIRING_CHANGED, STORE_UNAVAILABLE, CONFIRMATION_MISMATCH, MISSING_SCOPE,
    STATUS_NOT_FRESH, CAPABILITY_UNAVAILABLE, SUNSHINE_NOT_STOPPED,
    SUNSHINE_MONITORING_OFF, SUNSHINE_PROVIDER_UNAVAILABLE, SUNSHINE_STATE_UNKNOWN,
    PREVIEW_UNVERIFIED, PREVIEW_ACTIVE, OPERATION_UNRESOLVED, OBSERVATION_PAIRING_CHANGED,
}

/** Last safety check before a journaled host control mutation. */
class ControlDispatcher(
    private val repository: EncryptedStateRepository,
    private val journal: OperationJournal,
    private val clock: Clock,
) {
    fun power(
        expected: ActiveHost,
        confirmation: PowerConfirmation,
        observation: StatusPollState,
        sender: JournalMutationSender,
    ): ControlDispatchResult {
        if (confirmation.target != StatusObservationSource.from(expected)) {
            return ControlDispatchResult.Blocked(ControlBlock.CONFIRMATION_MISMATCH)
        }
        val blocked = commonBlock(expected, observation, confirmation.verb.wire)
        if (blocked != null) return ControlDispatchResult.Blocked(blocked)
        val request = ApiRequest(
            HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject(
                "request_id" to JsonValue.StringValue(UUID.randomUUID().toString()),
                "action" to JsonValue.StringValue(confirmation.verb.wire),
            ),
            expected.bearerToken,
        )
        return dispatched(journal.dispatchForHost(expected, request, sender))
    }

    fun recoverSunshine(
        expected: ActiveHost,
        observation: StatusPollState,
        sender: JournalMutationSender,
    ): ControlDispatchResult {
        val blocked = commonBlock(expected, observation, "sunshine_restart")
        if (blocked != null) return ControlDispatchResult.Blocked(blocked)
        val sunshine = observation.lastRead?.status?.sunshineState
        val stateBlock = when (sunshine) {
            WireValue.Known(SunshineState.STOPPED) -> null
            WireValue.Known(SunshineState.DISABLED) -> ControlBlock.SUNSHINE_MONITORING_OFF
            WireValue.Known(SunshineState.UNAVAILABLE) -> ControlBlock.SUNSHINE_PROVIDER_UNAVAILABLE
            WireValue.Known(SunshineState.UNKNOWN), null, is WireValue.Unknown -> ControlBlock.SUNSHINE_STATE_UNKNOWN
            WireValue.Known(SunshineState.RUNNING), WireValue.Known(SunshineState.RESTARTING) ->
                ControlBlock.SUNSHINE_NOT_STOPPED
            else -> ControlBlock.SUNSHINE_STATE_UNKNOWN
        }
        if (stateBlock != null) return ControlDispatchResult.Blocked(stateBlock)
        val request = ApiRequest(
            HttpMethod.POST, ApiRoute.SUNSHINE_RESTART.path,
            jsonObject("request_id" to JsonValue.StringValue(UUID.randomUUID().toString())),
            expected.bearerToken,
        )
        return dispatched(journal.dispatchForHost(expected, request, sender))
    }

    private fun commonBlock(expected: ActiveHost, observation: StatusPollState, capability: String): ControlBlock? {
        val state = when (val loaded = repository.load()) {
            is StateLoadResult.Ready -> loaded.state
            else -> return ControlBlock.STORE_UNAVAILABLE
        }
        val active = state.activeHost ?: return ControlBlock.PAIRING_CHANGED
        if (!samePairing(active, expected)) return ControlBlock.PAIRING_CHANGED
        if ("power.control" !in active.grantedScopes) return ControlBlock.MISSING_SCOPE
        val ageNanos = observation.lastSuccessMonotonicNanos?.let { clock.monotonicNanos() - it }
        val read = observation.lastRead
        if (observation.unreachable || observation.stale || read == null ||
            read.status.hostId != active.hostId || ageNanos == null || ageNanos < 0 || ageNanos > FRESH_NANOS
        ) return ControlBlock.STATUS_NOT_FRESH
        if (observation.source != StatusObservationSource.from(active)) {
            return ControlBlock.OBSERVATION_PAIRING_CHANGED
        }
        val availability = read.status.capabilities[capability]
        if (availability != WireValue.Known(CapabilityState.AVAILABLE)) {
            if (capability == "sunshine_restart") return when (read.status.sunshineState) {
                WireValue.Known(SunshineState.DISABLED) -> ControlBlock.SUNSHINE_MONITORING_OFF
                WireValue.Known(SunshineState.UNAVAILABLE) -> ControlBlock.SUNSHINE_PROVIDER_UNAVAILABLE
                WireValue.Known(SunshineState.UNKNOWN) -> ControlBlock.SUNSHINE_STATE_UNKNOWN
                WireValue.Known(SunshineState.RUNNING), WireValue.Known(SunshineState.RESTARTING) ->
                    ControlBlock.SUNSHINE_NOT_STOPPED
                WireValue.Known(SunshineState.STOPPED) ->
                    if (availability == WireValue.Known(CapabilityState.UNAVAILABLE)) {
                        ControlBlock.SUNSHINE_PROVIDER_UNAVAILABLE
                    } else ControlBlock.CAPABILITY_UNAVAILABLE
                else -> ControlBlock.CAPABILITY_UNAVAILABLE
            }
            return ControlBlock.CAPABILITY_UNAVAILABLE
        }
        val outputs = read.outputs ?: return ControlBlock.PREVIEW_UNVERIFIED
        if (outputs.legacyShape || !outputs.available) return ControlBlock.PREVIEW_UNVERIFIED
        if (outputs.preview != null) return ControlBlock.PREVIEW_ACTIVE
        if (state.journal.any { it.mayConflictWith(active) && it.phase.unresolved }) {
            return ControlBlock.OPERATION_UNRESOLVED
        }
        return null
    }

    private fun samePairing(a: ActiveHost, b: ActiveHost): Boolean =
        a.hostId == b.hostId && a.clientId == b.clientId && a.endpoint == b.endpoint &&
            a.certificatePin == b.certificatePin && a.pairedAtMillis == b.pairedAtMillis && a.bearerToken == b.bearerToken

    private fun dispatched(result: JournalDispatchResult): ControlDispatchResult =
        if (result == JournalDispatchResult.NotDispatched(StateInvariantCode.OPERATION_UNRESOLVED)) {
            ControlDispatchResult.Blocked(ControlBlock.OPERATION_UNRESOLVED)
        } else ControlDispatchResult.Dispatched(result)

    private companion object { const val FRESH_NANOS = 15_000_000_000L }
}
