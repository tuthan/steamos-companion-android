package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.transport.ResponseTiming
import java.time.Instant
import java.time.format.DateTimeFormatter

/** A host deadline held only in memory, anchored to Android elapsed realtime at response end. */
data class HostDeadlineAnchor(private val expiresAtMonotonicNanos: Long) {
    fun remainingMillis(nowMonotonicNanos: Long): Long =
        ((expiresAtMonotonicNanos - nowMonotonicNanos).coerceAtLeast(0)) / 1_000_000L

    /** Countdown labels round down so they never promise more time than remains. */
    fun remainingSeconds(nowMonotonicNanos: Long): Long = remainingMillis(nowMonotonicNanos) / 1_000L

    companion object {
        /** Missing/malformed Date or implausible timing returns null, disabling confirmation. */
        fun fromHostReply(deadlineEpochMillis: Long, timing: ResponseTiming?): HostDeadlineAnchor? {
            if (timing == null || deadlineEpochMillis < 0) return null
            val date = try {
                Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(timing.hostDateHeader ?: return null)).toEpochMilli()
            } catch (_: Exception) { return null }
            val tripNanos = timing.responseEndMonotonicNanos - timing.requestStartMonotonicNanos
            if (tripNanos < 0 || tripNanos > MAX_TRIP_NANOS) return null
            // HTTP Date has one-second precision. Treat its value as the earliest possible server
            // time, add the full missing second and the entire trip to avoid extending a deadline.
            val conservativeServerNow = date + 999L + (tripNanos + 999_999L) / 1_000_000L
            val remaining = deadlineEpochMillis - conservativeServerNow
            if (remaining < 0 || remaining > MAX_FUTURE_MILLIS) return null
            val addedNanos = remaining * 1_000_000L
            val expires = timing.responseEndMonotonicNanos + addedNanos
            if (expires < timing.responseEndMonotonicNanos) return null
            return HostDeadlineAnchor(expires)
        }

        private const val MAX_TRIP_NANOS = 10_000_000_000L
        private const val MAX_FUTURE_MILLIS = 10 * 60 * 1_000L
    }
}
