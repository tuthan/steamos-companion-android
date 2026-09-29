package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
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

/** A preview that runs out of time is restored by the host; there is nothing left to keep. */
class PreviewTimeoutReconcileTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 1_000_000_000L
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val host = testActiveHost()
    private val previewId = "op-fixture0001xxxxxxxxxxxxx"

    @Test fun hostReportedVerifiedRestoreFinishesTheTimedOutPreview() {
        val generation = seedOpenPreview()
        val reconciler = OperationReconciler(repository, journal) { _, _ -> lookup(restoreState = "succeeded", restored = true) }
        assertEquals(ReconcileResult.Updated(JournalUpdateResult.Saved), reconciler.reconcile(host, generation))
        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(JournalPhase.SUCCEEDED, state.journal.single().phase)
        assertFalse(OperationLanePresentation.hasUnresolved(host, state))
        assertEquals(PreviewPresentation.None, DisplayRecoveryPresentation.project(host, state,
            StatusPollState(source = StatusObservationSource.from(host)), 1_500_000_000L))
    }

    @Test fun anUnknownOrUnverifiedRestoreStaysUnresolved() {
        val generation = seedOpenPreview()
        for ((restoreState, restored) in listOf("unknown" to null, "dispatched" to null, "failed" to false, null to null,
            "succeeded" to false, "succeeded" to null)) {
            val reconciler = OperationReconciler(repository, journal) { _, _ -> lookup(restoreState, restored) }
            reconciler.reconcile(host, generation)
            assertTrue("$restoreState/$restored", (repository.load() as StateLoadResult.Ready).state
                .journal.single().phase.unresolved)
        }
    }

    @Test fun restoreFactsOnAnotherKindOfOperationNeverFinishIt() {
        repository.transact { it.copy(activeHost = host) }
        val entry = (journal.dispatchForHost(host, testPowerRequest("power-restored")) { _, _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
                OperationSummary("op-power-1", "power-restored", WireValue.Known(OperationState.ACCEPTED), "power.suspend",
                    "t", "t", null, null), jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null)
        } as JournalDispatchResult.Finished).entry
        val reconciler = OperationReconciler(repository, journal) { _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Operation(
                OperationSummary("op-power-1", "power-restored", WireValue.Known(OperationState.OBSERVED_RETURN),
                    "power.suspend", "t", "t", null, null, "succeeded", true))), 200, null)
        }
        reconciler.reconcile(host, entry.localGeneration)
        assertEquals(JournalPhase.OBSERVED_RETURN, (repository.load() as StateLoadResult.Ready).state.journal.single().phase)
    }

    @Test fun endedPreviewChecksAreThrottledAndBounded() {
        seedOpenPreview()
        val gets = AtomicInteger()
        val reconciler = OperationReconciler(repository, journal) { _, _ ->
            gets.incrementAndGet(); lookup("unknown", null)
        }
        ForegroundOperationReview(repository, reconciler, host) { _, _ -> }.use { review ->
            review.start()
            awaitGets(gets, 1)
            var now = 10_000_000_000L
            review.reviewEndedPreviews(now)
            awaitGets(gets, 2)
            // Inside the interval nothing is sent.
            review.reviewEndedPreviews(now + 1_000_000_000L)
            Thread.sleep(200)
            assertEquals(2, gets.get())
            // Six bounded attempts, then it stops asking.
            repeat(12) { now += 5_000_000_000L; review.reviewEndedPreviews(now); Thread.sleep(150) }
            assertEquals(1 + 6, gets.get())
        }
    }

    @Test fun theOperationParserReadsTheHostsRestoreFacts() {
        fun parse(extra: String) = io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec.parseOperation(
            BoundedJson.parseObject("""{"id":"op-1","request_id":"r","state":"observed_return","kind":"display.preview",
                "created_at":"t","updated_at":"t"$extra}""".toByteArray()))
        val restored = parse(""","restore_state":"succeeded","restored":true""")
        assertTrue(restored.hostRestored)
        assertFalse(parse("").hostRestored)
        assertFalse(parse(""","restore_state":"unknown","restored":false""").hostRestored)
        assertEquals("succeeded", restored.restoreState)
    }

    private fun awaitGets(gets: AtomicInteger, expected: Int) {
        repeat(50) { if (gets.get() >= expected) return; Thread.sleep(50) }
        assertEquals(expected, gets.get())
    }

    private fun lookup(restoreState: String?, restored: Boolean?) = RequestOutcome.Reply(
        ApiReply.Versioned(1, VersionedPayload.Operation(OperationSummary(previewId, "preview-seed",
            WireValue.Known(OperationState.OBSERVED_RETURN), "display.preview", "t", "t", "mode_request_returned",
            "host preview deadline expired; original mode restored", restoreState, restored))), 200, null)

    private fun seedOpenPreview(): Long {
        repository.transact { it.copy(activeHost = host) }
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject("request_id" to JsonValue.StringValue("preview-seed"), "output_id" to JsonValue.StringValue("1"),
                "mode_id" to JsonValue.StringValue("2"), "generation" to JsonValue.NumberValue("4")), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = JournalPhase.OBSERVED_RETURN, operationId = previewId)
        repository.transact { it.copy(journal = listOf(entry)) }
        return entry.localGeneration
    }
}
