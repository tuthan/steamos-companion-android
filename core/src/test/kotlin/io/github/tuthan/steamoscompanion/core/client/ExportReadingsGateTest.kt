package io.github.tuthan.steamoscompanion.core.client

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportReadingsGateTest {
    private val now = AtomicLong(0)
    private fun gate(expected: Int, budgetMillis: Long = 200) = ExportReadingsGate(expected, now::get, budgetMillis)
    private inline fun elapsedMillis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    @Test fun everyReadingArrivingReleasesTheWaitAtOnce() {
        val gate = gate(2, budgetMillis = 5_000)
        gate.arrived(); gate.arrived()
        var done = false
        assertTrue("returned promptly", elapsedMillis { done = gate.await() } < 1_000)
        assertTrue(done)
    }

    @Test fun aReadingThatNeverArrivesCostsAtMostTheBudget() {
        val gate = gate(2, budgetMillis = 150)
        gate.arrived()
        var done = true
        val took = elapsedMillis { done = gate.await() }
        assertFalse(done)
        assertTrue("waited the budget, not forever: $took ms", took in 100..2_000)
    }

    @Test fun aDeliveryDuringTheWaitReleasesIt() {
        val gate = gate(1, budgetMillis = 5_000)
        thread { Thread.sleep(100); gate.arrived() }
        var done = false
        val took = elapsedMillis { done = gate.await() }
        assertTrue(done)
        assertTrue("released by the delivery, not the budget: $took ms", took < 3_000)
    }

    @Test fun timeAlreadySpentAfterTheExportWasAskedForIsNotWaitedAgain() {
        val gate = gate(2, budgetMillis = 4_000)
        now.set(TimeUnit.SECONDS.toNanos(10)) // the owner spent ten seconds in the document picker
        var done = true
        assertTrue("no wait at all: nothing left of the budget", elapsedMillis { done = gate.await() } < 500)
        assertFalse(done)
        // A late delivery after the budget never blocks or fails anything.
        gate.arrived(); gate.arrived(); gate.arrived()
        assertTrue(gate.await())
    }

    @Test fun nothingAwaitedIsAlreadyDone() {
        assertTrue(gate(0).await())
        assertEquals(4_000L, ExportReadingsGate.BUDGET_MILLIS)
    }

    @Test fun anInterruptedWaitReportsNotDoneAndKeepsTheInterrupt() {
        val gate = gate(1, budgetMillis = 5_000)
        var done = true
        var stillInterrupted = false
        val worker = thread {
            done = gate.await()
            stillInterrupted = Thread.currentThread().isInterrupted
        }
        Thread.sleep(100)
        worker.interrupt()
        worker.join(3_000)
        assertFalse(done)
        assertTrue(stillInterrupted)
    }
}
