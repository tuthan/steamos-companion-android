package io.github.tuthan.steamoscompanion.core.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One export's bounded wait for the readings it asked for (the host journal and the link facts, each once).
 *
 * It is neither a timer nor a poll. The two loaders keep their own spacing and "latest wins" rules and deliver on
 * their own; each delivery releases one count here, and the exporting worker waits for the counts, never longer than
 * [budgetMillis] after the owner asked for the export. A reading that does not arrive in time is simply absent from
 * the report, and a gate that nobody waits on costs nothing.
 */
class ExportReadingsGate(
    expected: Int,
    private val monotonicNanos: () -> Long,
    private val budgetMillis: Long = BUDGET_MILLIS,
) {
    private val latch = CountDownLatch(expected.coerceAtLeast(0))
    private val askedNanos = monotonicNanos()

    /** One awaited reading was delivered, whatever it said. Safe from any thread, and after the wait is over. */
    fun arrived() = latch.countDown()

    /** True when every awaited reading arrived. Blocks the calling worker for what is left of the budget, at most. */
    fun await(): Boolean {
        val spentMillis = TimeUnit.NANOSECONDS.toMillis((monotonicNanos() - askedNanos).coerceAtLeast(0))
        val leftMillis = budgetMillis - spentMillis
        if (leftMillis <= 0) return latch.count == 0L
        return try {
            latch.await(leftMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    companion object {
        /** "About four seconds in total": long enough for a read to finish, short enough that Export never feels stuck. */
        const val BUDGET_MILLIS = 4_000L
    }
}
