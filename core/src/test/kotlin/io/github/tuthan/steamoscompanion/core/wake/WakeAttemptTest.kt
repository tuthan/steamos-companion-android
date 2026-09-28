package io.github.tuthan.steamoscompanion.core.wake

import io.github.tuthan.steamoscompanion.core.client.PairedStatusRead
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StatusObservationSource
import io.github.tuthan.steamoscompanion.core.client.testActiveHost
import io.github.tuthan.steamoscompanion.core.protocol.SteamBridgeState
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAttemptTest {
    private val sent = WakeSendResult.Sent(listOf(InetAddress.getByName("192.168.4.255")))
    private val host = testActiveHost(hostId = "target-host")

    @Test fun packetStatusResponseAndSteamReadinessAreIndependentFacts() {
        val attempt = WakeAttempt(host, 1_000_000_000L, sent)
        assertFalse(attempt.state.authenticatedResponse)
        assertFalse(attempt.state.steamReady)
        assertFalse(attempt.observe(status("target-host", SteamBridgeState.READY, 900_000_000L), 2_000_000_000L).authenticatedResponse)
        val connected = attempt.observe(status("target-host", SteamBridgeState.UNAVAILABLE, 2_000_000_000L), 2_000_000_000L)
        assertTrue(connected.authenticatedResponse)
        assertFalse(connected.steamReady)
        assertTrue(attempt.observe(status("target-host", SteamBridgeState.READY, 3_000_000_000L), 3_000_000_000L).steamReady)
    }

    @Test fun timeoutDoesNotResendAndLaterConnectionCanStillBeObserved() {
        val attempt = WakeAttempt(host, 1_000_000_000L, sent)
        assertFalse(attempt.tick(30_999_999_999L).timedOut)
        assertTrue(attempt.tick(31_000_000_000L).timedOut)
        val later = attempt.observe(status("target-host", SteamBridgeState.UNAVAILABLE, 32_000_000_000L), 32_000_000_000L)
        assertTrue(later.authenticatedResponse)
        assertFalse(later.timedOut)
    }

    @Test fun failedPacketDoesNotStartAResponseWait() {
        val attempt = WakeAttempt(host, 1_000_000_000L, WakeSendResult.Failed(WakeSendFailure.TARGET_MISSING))
        assertFalse(attempt.tick(50_000_000_000L).timedOut)
        assertFalse(attempt.observe(status("target-host", SteamBridgeState.READY, 51_000_000_000L), 51_000_000_000L).authenticatedResponse)
    }

    @Test fun responseFromOldCredentialDoesNotSatisfyNewWakeAttempt() {
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        val attempt = WakeAttempt(rotated, 1_000_000_000L, sent)
        assertFalse(attempt.observe(status("target-host", SteamBridgeState.READY, 2_000_000_000L),
            2_000_000_000L).authenticatedResponse)
    }

    private fun status(hostId: String, bridge: SteamBridgeState, at: Long) = StatusPollState(
        lastRead = PairedStatusRead(
            VersionedPayload.Status(hostId, "boot-1", WireValue.Known(bridge), emptyMap(), WireValue.Unknown("unknown"), jsonObject()),
            null, null,
        ),
        lastSuccessMonotonicNanos = at,
        source = StatusObservationSource.from(host),
    )
}
