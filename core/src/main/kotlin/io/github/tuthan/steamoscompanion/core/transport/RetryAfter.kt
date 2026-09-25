package io.github.tuthan.steamoscompanion.core.transport

import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Parses Retry-After without allowing a local clock skew to shorten a server-provided wait. */
object RetryAfter {
    const val DEFAULT_SECONDS = 2L
    private val IMF_FIXDATE = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
    private val RFC850 = DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US)
    private val ASCTIME = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US).withZone(ZoneOffset.UTC)

    fun delayMillis(headers: Map<String, String>, localUtcMillis: Long): Long {
        val retry = headers["retry-after"] ?: headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value
            ?: return DEFAULT_SECONDS * 1000
        val value = retry.trim()
        if (value.isEmpty()) return DEFAULT_SECONDS * 1000
        if (value.all(Char::isDigit)) {
            return try {
                BigInteger(value).multiply(BigInteger.valueOf(1000)).coerceAtMost(LONG_MAX).toLong()
            } catch (_: NumberFormatException) {
                DEFAULT_SECONDS * 1000
            }
        }
        val target = parseHttpDate(value) ?: return DEFAULT_SECONDS * 1000
        val responseDate = headers["date"] ?: headers.entries.firstOrNull { it.key.equals("date", true) }?.value
        val base = responseDate?.let(::parseHttpDate)?.toEpochMilli() ?: localUtcMillis
        val delta = try {
            Math.subtractExact(target.toEpochMilli(), base)
        } catch (_: ArithmeticException) {
            if (target.toEpochMilli() >= base) Long.MAX_VALUE else Long.MIN_VALUE
        }
        return delta.coerceAtLeast(0)
    }

    private fun parseHttpDate(value: String): Instant? {
        val formats = listOf(IMF_FIXDATE, RFC850)
        formats.forEach { formatter ->
            try {
                return ZonedDateTime.parse(value, formatter).toInstant()
            } catch (_: DateTimeParseException) {
                // Try the next HTTP-date form.
            }
        }
        return try {
            ASCTIME.parse(value, Instant::from)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
}
