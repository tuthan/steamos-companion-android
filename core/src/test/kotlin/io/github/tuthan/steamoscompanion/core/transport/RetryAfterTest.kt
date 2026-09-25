package io.github.tuthan.steamoscompanion.core.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryAfterTest {
    @Test
    fun parsesDecimalSecondsAndUsesFallbackForMissingOrInvalidValues() {
        assertEquals(2_000L, RetryAfter.delayMillis(mapOf("retry-after" to "2"), 0))
        assertEquals(0L, RetryAfter.delayMillis(mapOf("retry-after" to "0"), 0))
        assertEquals(2_000L, RetryAfter.delayMillis(emptyMap(), 0))
        assertEquals(2_000L, RetryAfter.delayMillis(mapOf("retry-after" to "-1"), 0))
        assertEquals(Long.MAX_VALUE, RetryAfter.delayMillis(mapOf("retry-after" to "999999999999999999999999"), 0))
    }

    @Test
    fun parsesHttpDatesRelativeToResponseDateAndFallsBackToLocalUtc() {
        val twoSeconds = mapOf(
            "retry-after" to "Wed, 21 Oct 2015 07:28:00 GMT",
            "date" to "Wed, 21 Oct 2015 07:27:58 GMT",
        )
        assertEquals(2_000L, RetryAfter.delayMillis(twoSeconds, 0))

        val localFallback = mapOf("retry-after" to "Wed, 21 Oct 2015 07:28:00 GMT")
        val localBase = java.time.Instant.parse("2015-10-21T07:27:58Z").toEpochMilli()
        assertEquals(2_000L, RetryAfter.delayMillis(localFallback, localBase))
        assertEquals(0L, RetryAfter.delayMillis(localFallback, java.time.Instant.parse("2015-10-21T07:29:00Z").toEpochMilli()))
    }
}
