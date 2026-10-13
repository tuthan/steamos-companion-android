package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.OperationState
import io.github.tuthan.steamoscompanion.core.protocol.OperationSummary
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An accepted host operation must not block the lane until someone remembers to tap "Check result". */
class OpenOperationReviewTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 1_000_000_000L
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val host = testActiveHost()

    @Test fun aPowerRequestTheHostReturnedFromDoesNotBlockTheLane() {
        val entry = dispatchPower(OperationState.OBSERVED_RETURN)
        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(JournalPhase.OBSERVED_RETURN, entry.phase)
        assertFalse(OperationLanePresentation.hasUnresolved(host, state))
        // Still pending in the journal, so history and diagnostics keep the host's exact word.
        assertTrue(state.journal.single().phase.unresolved)
        assertEquals(1, ControlOperationPresentation.project(host, state, StatusPollState(), 1L).size)
        assertFalse(ControlOperationPresentation.project(host, state, StatusPollState(), 1L).single().blocksLane)
    }

    @Test fun anAcceptedPowerRequestStillBlocksUntilTheHostReturns() {
        dispatchPower(OperationState.ACCEPTED)
        assertTrue(OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state))
    }

    @Test fun openOperationChecksSettleAnAcceptedEntryAndAreBounded() {
        val entry = dispatchPower(OperationState.ACCEPTED)
        val gets = AtomicInteger()
        val reconciler = OperationReconciler(repository, journal) { _, _ ->
            val done = gets.incrementAndGet() >= 2
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Operation(
                OperationSummary("op-power-1", "power-open", WireValue.Known(
                    if (done) OperationState.SUCCEEDED else OperationState.DISPATCHED),
                    "power.suspend", "t", "t", null, null))), 200, null)
        }
        ForegroundOperationReview(repository, reconciler, host) { _, _ -> }.use { review ->
            review.start()
            awaitGets(gets, 1) // the start sweep
            var now = 10_000_000_000L
            offer(gets, 2) { review.reviewOpenOperations(now) }
            awaitTrue("the settled entry stops blocking the lane") {
                !OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state)
            }
            assertFalse(OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state))
            assertEquals(JournalPhase.SUCCEEDED,
                (repository.load() as StateLoadResult.Ready).state.journal.single { it.localGeneration == entry.localGeneration }.phase)
            // A settled entry is not asked about again.
            repeat(5) { now += 5_000_000_000L; review.reviewOpenOperations(now); Thread.sleep(80) }
            assertEquals(2, gets.get())
        }
    }

    @Test fun openOperationChecksThrottleAndStopAtTheCap() {
        dispatchPower(OperationState.ACCEPTED)
        val gets = AtomicInteger()
        val reconciler = OperationReconciler(repository, journal) { _, _ ->
            gets.incrementAndGet()
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Operation(
                OperationSummary("op-power-1", "power-open", WireValue.Known(OperationState.ACCEPTED),
                    "power.suspend", "t", "t", null, null))), 200, null)
        }
        ForegroundOperationReview(repository, reconciler, host) { _, _ -> }.use { review ->
            review.start()
            awaitGets(gets, 1)
            var now = 10_000_000_000L
            offer(gets, 2) { review.reviewOpenOperations(now) }
            review.reviewOpenOperations(now + 1_000_000_000L) // inside the interval
            Thread.sleep(200)
            assertEquals(2, gets.get())
            // Twenty bounded attempts after the start sweep, then it stops asking.
            var tries = 0
            while (gets.get() < 1 + 20 && tries++ < 800) {
                now += 5_000_000_000L; review.reviewOpenOperations(now); Thread.sleep(30)
            }
            assertEquals(1 + 20, gets.get())
            repeat(10) { now += 5_000_000_000L; review.reviewOpenOperations(now); Thread.sleep(80) }
            assertEquals(1 + 20, gets.get())
        }
    }

    @Test fun aFailedOperationKeepsTheHostsReasonAndASuccessfulOneDoesNot() {
        val entry = dispatchPower(OperationState.ACCEPTED)
        fun reply(state: OperationState, reason: String?) = OperationReconciler(repository, journal) { _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Operation(
                OperationSummary("op-power-1", "power-open", WireValue.Known(state), "power.suspend", "t", "t",
                    null, reason))), 200, null)
        }
        reply(OperationState.FAILED, "Steam rejected\u0007 display mode").reconcile(host, entry.localGeneration)
        val failed = (repository.load() as StateLoadResult.Ready).state.journal.single()
        assertEquals("failed: Steam rejected  display mode", failed.resultSummary)
        assertEquals(JournalPhase.FAILED, failed.phase)
    }

    @Test fun aReasonOnANonFailureIsNotStored() {
        val entry = dispatchPower(OperationState.ACCEPTED)
        OperationReconciler(repository, journal) { _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Operation(
                OperationSummary("op-power-1", "power-open", WireValue.Known(OperationState.OBSERVED_RETURN),
                    "power.suspend", "t", "t", "method_returned", "Steam returned"))), 200, null)
        }.reconcile(host, entry.localGeneration)
        assertEquals("observed_return: method_returned",
            (repository.load() as StateLoadResult.Ready).state.journal.single().resultSummary)
    }

    private fun dispatchPower(state: OperationState): JournalEntry {
        repository.transact { it.copy(activeHost = host) }
        return (journal.dispatchForHost(host, testPowerRequest("power-open")) { _, _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
                OperationSummary("op-power-1", "power-open", WireValue.Known(state), "power.suspend",
                    "t", "t", null, null), jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null)
        } as JournalDispatchResult.Finished).entry
    }

    private fun awaitGets(gets: AtomicInteger, expected: Int) {
        repeat(200) { if (gets.get() >= expected) return; Thread.sleep(50) }
        assertEquals(expected, gets.get())
    }

    /**
     * The review drops a call while its previous sweep is still running, and a sweep outlives the
     * lookup that [awaitGets] waits for. Offer the same instant again until a request is made; once
     * one is, the interval throttles every repeat, so this cannot send twice.
     */
    private fun offer(gets: AtomicInteger, expected: Int, call: () -> Unit) {
        repeat(100) { call(); if (gets.get() >= expected) return; Thread.sleep(50) }
        assertEquals(expected, gets.get())
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        repeat(100) { if (condition()) return; Thread.sleep(50) }
        assertTrue(what, condition())
    }
}
