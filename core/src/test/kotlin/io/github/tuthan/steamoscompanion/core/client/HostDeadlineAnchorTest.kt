package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class HostDeadlineAnchorTest {
    private val date = "Sat, 26 Sep 2026 05:00:00 GMT"
    private val deadline = Instant.parse("2026-09-26T05:00:20Z").toEpochMilli()

    @Test fun countdownUsesHostDateRoundTripAndMonotonicTime() {
        val timing = ResponseTiming(1_000_000_000L, 1_300_000_000L, date)
        val anchor = HostDeadlineAnchor.fromHostReply(deadline, timing)
        assertNotNull(anchor)
        anchor!!
        assertEquals(18L, anchor.remainingSeconds(1_300_000_000L))
        assertEquals(17L, anchor.remainingSeconds(2_300_000_000L))
        assertEquals(0L, anchor.remainingSeconds(30_000_000_000L))
    }

    @Test fun missingOrUntrustedTimeDisablesDeadlineConfirmation() {
        assertNull(HostDeadlineAnchor.fromHostReply(deadline, null))
        assertNull(HostDeadlineAnchor.fromHostReply(deadline, ResponseTiming(1, 2, null)))
        assertNull(HostDeadlineAnchor.fromHostReply(deadline, ResponseTiming(1, 2, "bad date")))
        assertNull(HostDeadlineAnchor.fromHostReply(deadline, ResponseTiming(20, 10, date)))
        assertNull(HostDeadlineAnchor.fromHostReply(deadline, ResponseTiming(0, 11_000_000_000L, date)))
    }
}
