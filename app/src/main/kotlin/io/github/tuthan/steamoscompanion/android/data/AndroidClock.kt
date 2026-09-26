package io.github.tuthan.steamoscompanion.android.data

import android.os.SystemClock
import io.github.tuthan.steamoscompanion.core.ports.Clock

/** Android wall time for persisted/display values and a monotonic clock for deadlines. */
class AndroidClock : Clock {
    override fun wallTimeMillis(): Long = System.currentTimeMillis()
    override fun monotonicNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
