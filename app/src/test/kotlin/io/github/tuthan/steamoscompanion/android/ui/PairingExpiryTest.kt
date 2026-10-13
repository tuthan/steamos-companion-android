package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.pairing.PairingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The countdown arithmetic and the pairing step rail, with no UI involved. */
class PairingExpiryTest {
    private val now = 1_780_000_000_000L

    private fun counting(remainingMillis: Long): PairingExpiryView.Counting =
        PairingExpiry.view(now + remainingMillis, now) as PairingExpiryView.Counting

    @Test fun remainingTimeRoundsDownSoTheScreenNeverClaimsMoreTimeThanTheRequestHas() {
        assertEquals(117L, counting(117_999).remainingSeconds)
        assertEquals("1:57", counting(117_999).text)
        assertEquals("0:59", counting(59_999).text)
        assertEquals("1:00", counting(60_000).text)
        assertEquals("0:00", counting(999).text)
        assertEquals("2:00", counting(120_000).text)
    }

    @Test fun theLastTenSecondsAreUrgentAndNotOneMillisecondEarlier() {
        assertTrue(counting(10_000).urgent)
        assertTrue(counting(1).urgent)
        assertTrue(!counting(10_001).urgent)
        assertTrue(!counting(117_000).urgent)
    }

    @Test fun atOrPastTheDeadlineItIsExpiredAndNeverNegative() {
        assertEquals(PairingExpiryView.Expired, PairingExpiry.view(now, now))
        assertEquals(PairingExpiryView.Expired, PairingExpiry.view(now - 1, now))
        assertEquals(PairingExpiryView.Expired, PairingExpiry.view(now - 3_600_000, now))
        assertEquals("0:00", PairingExpiry.format(-5))
    }

    @Test fun aCandidateWithoutADeadlineShowsNoTimer() {
        assertNull(PairingExpiry.view(null, now))
    }

    @Test fun onlyALiveRequestShowsATimer() {
        val candidate = PairingFixtures.candidate(now + 60_000)
        val alive = listOf(PairingAction.Ready(candidate), PairingAction.Pending(candidate),
            PairingAction.RateLimited(candidate, now), PairingAction.RequestUnconfirmed(candidate),
            PairingAction.InProgress(candidate), PairingAction.Expired(candidate))
        alive.forEach { assertTrue("$it", PairingExpiry.showsFor(it)) }
        val finished = listOf(PairingAction.Idle, PairingAction.Rejected(candidate, "denied", "Declined"),
            PairingAction.Cancelled(localOnly = true, remoteConfirmed = false, explanation = "cancelled"),
            PairingAction.Retryable(candidate, "network"), PairingAction.StaleGeneration,
            PairingAction.Failed("failed"), PairingAction.NeedsAttention(candidate, "attention"))
        finished.forEach { assertTrue("$it", !PairingExpiry.showsFor(it)) }
    }

    @Test fun theRailHasOneStepFewerWhenThereIsNoCodeToCompare() {
        assertEquals(listOf("Review the device", "Request pairing", "Compare the code", "Approve on the other device"),
            PairingSteps.steps(PairingVariant.VERIFICATION))
        assertEquals(listOf("Review the device", "Request pairing", "Approve on the other device"),
            PairingSteps.steps(PairingVariant.ADVANCED_PAYLOAD))
    }

    @Test fun theRailFollowsThePairingFromReviewToApproval() {
        val candidate = PairingFixtures.candidate(now + 60_000)
        assertEquals(0, PairingSteps.activeIndex(PairingVariant.VERIFICATION, null))
        assertEquals(1, PairingSteps.activeIndex(PairingVariant.VERIFICATION, PairingAction.Ready(candidate)))
        assertEquals(2, PairingSteps.activeIndex(PairingVariant.VERIFICATION, PairingAction.Pending(candidate)))
        assertEquals(1, PairingSteps.activeIndex(PairingVariant.ADVANCED_PAYLOAD, PairingAction.Ready(candidate)))
        assertEquals(2, PairingSteps.activeIndex(PairingVariant.ADVANCED_PAYLOAD, PairingAction.Pending(candidate)))
        // Whatever the action, the active step is always one the rail has.
        for (variant in PairingVariant.entries) for (action in listOf(null, PairingAction.Idle,
            PairingAction.Pending(candidate), PairingAction.Expired(candidate), PairingAction.StaleGeneration)) {
            assertTrue(PairingSteps.activeIndex(variant, action) in PairingSteps.steps(variant).indices)
        }
    }
}
