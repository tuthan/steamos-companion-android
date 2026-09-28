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
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundOperationReviewTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 1_000_000_000L
    }
    private val host = testActiveHost()

    @Test fun eachForegroundStartRunsOneReadOnlySweepWithoutPostReplay() {
        val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
        val journal = OperationJournal(repository, clock)
        repository.transact { it.copy(activeHost = host) }
        val entry = (journal.dispatchForHost(host, testPowerRequest("review-1")) { _, _, _ ->
            mutation("op-review-1", "review-1", OperationState.ACCEPTED)
        } as JournalDispatchResult.Finished).entry
        val gets = AtomicInteger()
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val reconciler = OperationReconciler(repository, journal) { _, _ ->
            gets.incrementAndGet()
            operation("op-review-1", "review-1", OperationState.ACCEPTED)
        }
        ForegroundOperationReview(repository, reconciler, host) { generation, _ ->
            assertEquals(entry.localGeneration, generation)
            if (gets.get() == 1) first.countDown() else second.countDown()
        }.use { review ->
            review.start()
            assertTrue(first.await(2, TimeUnit.SECONDS))
            review.start()
            assertEquals(1, gets.get())
            review.stop()
            review.start()
            assertTrue(second.await(2, TimeUnit.SECONDS))
        }
        assertEquals(2, gets.get())
        assertEquals(1, (repository.load() as StateLoadResult.Ready).state.journal.size)
    }

    @Test fun stopCancelsAnInFlightLookupAndLeavesTheJournalUnchanged() {
        val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
        val journal = OperationJournal(repository, clock)
        repository.transact { it.copy(activeHost = host) }
        (journal.dispatchForHost(host, testPowerRequest("review-stop")) { _, _, _ ->
            mutation("op-review-stop", "review-stop", OperationState.ACCEPTED)
        } as JournalDispatchResult.Finished)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellationSeen = CountDownLatch(1)
        val published = AtomicInteger()
        val lookup = object : OperationLookup {
            override fun get(host: ActiveHost, operationId: String): RequestOutcome =
                error("Foreground review must pass its cancellation signal")

            override fun get(host: ActiveHost, operationId: String, cancellation: CancellationSignal): RequestOutcome {
                entered.countDown()
                runCatching { release.await(2, TimeUnit.SECONDS) }
                if (cancellation.isCancelled) cancellationSeen.countDown()
                return operation("op-review-stop", "review-stop", OperationState.SUCCEEDED)
            }
        }
        val reconciler = OperationReconciler(repository, journal, lookup)
        ForegroundOperationReview(repository, reconciler, host) { _, _ -> published.incrementAndGet() }.use { review ->
            review.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            review.stop()
            release.countDown()
            assertTrue(cancellationSeen.await(2, TimeUnit.SECONDS))
        }
        assertEquals(0, published.get())
        assertEquals(JournalPhase.ACCEPTED, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
    }

    private fun summary(id: String, requestId: String, state: OperationState) =
        OperationSummary(id, requestId, WireValue.Known(state), "power.suspend", "created", "updated", null, null)

    private fun mutation(id: String, requestId: String, state: OperationState) = RequestOutcome.Reply(
        ApiReply.Versioned(1, VersionedPayload.Mutation(summary(id, requestId, state),
            jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null,
    )

    private fun operation(id: String, requestId: String, state: OperationState) = RequestOutcome.Reply(
        ApiReply.Versioned(1, VersionedPayload.Operation(summary(id, requestId, state))), 200, null,
    )
}
