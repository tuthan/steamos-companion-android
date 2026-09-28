package io.github.tuthan.steamoscompanion.core.wake

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.protocol.SteamBridgeState
import io.github.tuthan.steamoscompanion.core.protocol.WireValue

data class WakeAttemptState(
    val packet: WakeSendResult,
    val authenticatedResponse: Boolean = false,
    val steamReady: Boolean = false,
    val timedOut: Boolean = false,
)

/** Consumes foreground status observations; it never sends or schedules another Wake packet. */
class WakeAttempt(host: ActiveHost, private val startedAtNanos: Long, packet: WakeSendResult) {
    private val hostId = host.hostId
    private val source = StatusObservationSource.from(host)
    var state = WakeAttemptState(packet)
        private set

    fun observe(status: StatusPollState, nowNanos: Long): WakeAttemptState {
        tick(nowNanos)
        if (state.packet is WakeSendResult.Failed) return state
        val read = status.lastRead ?: return state
        val readAt = status.lastSuccessMonotonicNanos ?: return state
        if (readAt < startedAtNanos || readAt > nowNanos || read.status.hostId != hostId ||
            status.source != source || status.unreachable
        ) return state
        state = state.copy(
            authenticatedResponse = true,
            steamReady = read.status.steamBridge == WireValue.Known(SteamBridgeState.READY),
            timedOut = false,
        )
        return state
    }

    fun tick(nowNanos: Long): WakeAttemptState {
        if (state.packet !is WakeSendResult.Failed && !state.authenticatedResponse && !state.timedOut && nowNanos >= startedAtNanos &&
            nowNanos - startedAtNanos >= MAX_WAIT_NANOS
        ) state = state.copy(timedOut = true)
        return state
    }

    companion object { const val MAX_WAIT_NANOS = 30_000_000_000L }
}
