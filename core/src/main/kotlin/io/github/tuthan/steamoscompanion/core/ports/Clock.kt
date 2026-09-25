package io.github.tuthan.steamoscompanion.core.ports

/** Wall time is for display and persisted timestamps; monotonic time is for deadlines. */
interface Clock {
    fun wallTimeMillis(): Long
    fun monotonicNanos(): Long
}
