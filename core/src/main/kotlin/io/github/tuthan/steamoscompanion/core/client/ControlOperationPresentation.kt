package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue

enum class ControlOperationKind { SUSPEND, RESTART, SHUTDOWN, SUNSHINE_RESTART }

enum class ControlObservation {
    NOT_CHECKED, CONNECTION_LOST, HOST_REACHABLE, BOOT_ID_CHANGED,
    SUNSHINE_RUNNING, SUNSHINE_STOPPED, SUNSHINE_UNAVAILABLE,
    UNPROVEN_PAIRING,
}

/** Operation phase and observed host state are independent facts, including after process recreation. */
data class ControlOperationCard(
    val localGeneration: Long,
    val requestId: String,
    val kind: ControlOperationKind,
    val phase: JournalPhase,
    val operationId: String?,
    val observed: ControlObservation,
    val pairingProven: Boolean,
)

object ControlOperationPresentation {
    fun project(
        host: ActiveHost,
        state: PersistentState,
        observation: StatusPollState,
        nowMonotonicNanos: Long,
    ): List<ControlOperationCard> {
        val active = state.activeHost ?: return emptyList()
        val source = StatusObservationSource.from(active)
        if (source != StatusObservationSource.from(host)) return emptyList()
        val observedState = currentObservation(active, observation, nowMonotonicNanos)
        return state.journal.asReversed().mapNotNull { entry ->
            val kind = kind(entry) ?: return@mapNotNull null
            val proven = entry.belongsTo(active)
            if (!proven && !(entry.phase.unresolved && entry.mayConflictWith(active))) return@mapNotNull null
            ControlOperationCard(
                localGeneration = entry.localGeneration,
                requestId = entry.requestId,
                kind = kind,
                phase = entry.phase,
                operationId = entry.operationId,
                observed = if (proven) observedFor(entry, kind, observedState) else ControlObservation.UNPROVEN_PAIRING,
                pairingProven = proven,
            )
        }
    }

    private sealed interface CurrentObservation {
        data object Missing : CurrentObservation
        data object Lost : CurrentObservation
        data class Fresh(val read: PairedStatusRead) : CurrentObservation
    }

    private fun currentObservation(
        host: ActiveHost,
        observation: StatusPollState,
        nowMonotonicNanos: Long,
    ): CurrentObservation {
        if (observation.source != StatusObservationSource.from(host)) return CurrentObservation.Missing
        if (observation.unreachable) return CurrentObservation.Lost
        val read = observation.lastRead ?: return CurrentObservation.Missing
        if (read.status.hostId != host.hostId || observation.stale) return CurrentObservation.Missing
        val age = observation.lastSuccessMonotonicNanos?.let { nowMonotonicNanos - it }
            ?: return CurrentObservation.Missing
        if (age !in 0..FRESH_NANOS) return CurrentObservation.Missing
        return CurrentObservation.Fresh(read)
    }

    private fun observedFor(
        entry: JournalEntry,
        kind: ControlOperationKind,
        observation: CurrentObservation,
    ): ControlObservation = when (observation) {
        CurrentObservation.Missing -> ControlObservation.NOT_CHECKED
        CurrentObservation.Lost -> ControlObservation.CONNECTION_LOST
        is CurrentObservation.Fresh -> when (kind) {
            ControlOperationKind.RESTART -> if (entry.hostBootId != null &&
                entry.hostBootId != observation.read.status.bootId
            ) ControlObservation.BOOT_ID_CHANGED else ControlObservation.HOST_REACHABLE
            ControlOperationKind.SUSPEND, ControlOperationKind.SHUTDOWN -> ControlObservation.HOST_REACHABLE
            ControlOperationKind.SUNSHINE_RESTART -> when (observation.read.status.sunshineState) {
                WireValue.Known(SunshineState.RUNNING) -> ControlObservation.SUNSHINE_RUNNING
                WireValue.Known(SunshineState.STOPPED) -> ControlObservation.SUNSHINE_STOPPED
                else -> ControlObservation.SUNSHINE_UNAVAILABLE
            }
        }
    }

    private fun kind(entry: JournalEntry): ControlOperationKind? = when (entry.path) {
        ApiRoute.POWER.path -> when (entry.expectedOperationKind()) {
            "power.suspend" -> ControlOperationKind.SUSPEND
            "power.restart" -> ControlOperationKind.RESTART
            "power.shutdown" -> ControlOperationKind.SHUTDOWN
            else -> null
        }
        ApiRoute.SUNSHINE_RESTART.path -> ControlOperationKind.SUNSHINE_RESTART
        else -> null
    }

    private const val FRESH_NANOS = 15_000_000_000L
}
