package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asLong
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 403 `forbidden` arrives when the owner removed a scope before this client has learned of it. It is shown in the
 * owner's plain words and leaves the pairing exactly as it was: nothing is removed or marked invalid, the failed
 * request does not hold the lane, and the status refresh that follows learns the new scopes. A refused or timed-out
 * connection is not a refusal and never touches the pairing.
 */
class ForbiddenRefusalTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 2_000_000_000L
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val controls = ControlDispatcher(repository, journal, clock)
    private val displays = DisplayDispatcher(repository, journal, clock)
    private val host = testActiveHost()

    private fun seed(active: ActiveHost = host) {
        assertTrue(repository.transact { it.copy(activeHost = active) } is StateCommitResult.Saved)
    }

    private fun stored(): ActiveHost = (repository.load() as StateLoadResult.Ready).state.activeHost!!

    private fun read(name: String): StateCommitResult? = repository.recordReportedScopes(host, status(name).credentialScopes)

    // --- a 403 before the new scopes are learned -------------------------------------------

    @Test fun aForbiddenPowerRequestIsAPlainRefusalAndTheStalePairingIsLeftAsItWas() {
        seed()
        val before = (repository.load() as StateLoadResult.Ready).state.activeHost
        val result = controls.power(host, PowerConfirmation.forHost(host, PowerVerb.SUSPEND), observation(),
            JournalMutationSender { _, request, _ ->
                assertEquals(ApiRoute.POWER.path, request.path)
                RequestOutcome.Rejected(rejected(ApiRoute.POWER, "power/forbidden-after-owner-removed-scope"), null)
            }) as ControlDispatchResult.Dispatched
        val entry = (result.result as JournalDispatchResult.Finished).entry
        assertEquals(JournalPhase.FAILED, entry.phase)
        assertEquals(JournalRejectionCode.FORBIDDEN, entry.rejectionCode)
        assertEquals("The host owner has not allowed this action for this pairing.", HostAccessPresentation.refusal(entry))
        // Nothing is removed or marked invalid: same pairing, same credential, same scopes until the next status read says.
        val state = (repository.load() as StateLoadResult.Ready).state
        assertEquals(before, state.activeHost)
        assertNull(state.candidate)
        assertEquals(1, state.journal.size)
        // The failed request does not hold the lane, so the owner is not stuck behind it.
        assertTrue(controls.power(host, PowerConfirmation.forHost(host, PowerVerb.SUSPEND), observation(),
            JournalMutationSender { _, _, _ -> RequestOutcome.NotSent(TransportFailure.CONNECT) }) is ControlDispatchResult.Dispatched)
        // The status refresh that follows learns what the owner changed.
        assertTrue(read("scopes-changed-by-owner") is StateCommitResult.Saved)
        assertEquals(listOf("status.read", "display.control"), stored().grantedScopes)
        assertEquals(host.bearerToken, stored().bearerToken)
    }

    @Test fun aForbiddenDisplayRequestIsTheSamePlainRefusal() {
        seed()
        val inventory = outputs("available")
        val output = inventory.outputs.single()
        val result = displays.preview(host, observation(outputs = inventory),
            DisplaySelection(output.id, output.generation, "2"), JournalMutationSender { _, _, _ ->
                RequestOutcome.Rejected(rejected(ApiRoute.DISPLAY_PREVIEW, "display-preview/forbidden-scope"), null)
            }) as DisplayDispatchResult.Dispatched
        val entry = (result.result as JournalDispatchResult.Finished).entry
        assertEquals(JournalRejectionCode.FORBIDDEN, entry.rejectionCode)
        assertEquals(HostAccessPresentation.FORBIDDEN_MESSAGE, HostAccessPresentation.refusal(entry))
        assertEquals(host, stored())
    }

    @Test fun otherRefusalsAndOtherFailuresKeepTheirOwnWording() {
        for ((route, name) in listOf(ApiRoute.POWER to "power/mutation-conflict", ApiRoute.POWER to "power/bridge-unavailable",
            ApiRoute.POWER to "power/invalid-action")) {
            val repo = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
            repo.transact { it.copy(activeHost = host) }
            val result = ControlDispatcher(repo, OperationJournal(repo, clock), clock)
                .power(host, PowerConfirmation.forHost(host, PowerVerb.SUSPEND), observation(),
                    JournalMutationSender { _, _, _ -> RequestOutcome.Rejected(rejected(route, name), null) }) as ControlDispatchResult.Dispatched
            val entry = (result.result as JournalDispatchResult.Finished).entry
            assertNotEquals(name, JournalRejectionCode.FORBIDDEN, entry.rejectionCode)
            assertNull(name, HostAccessPresentation.refusal(entry))
        }
    }

    @Test fun aRefusedOrTimedOutConnectionIsNotARefusalAndNeverTouchesThePairing() {
        for (failure in listOf(TransportFailure.CONNECT, TransportFailure.TIMEOUT, TransportFailure.IO, TransportFailure.DNS)) {
            val repo = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
            repo.transact { it.copy(activeHost = host) }
            val dispatcher = ControlDispatcher(repo, OperationJournal(repo, clock), clock)
            val confirmation = PowerConfirmation.forHost(host, PowerVerb.SUSPEND)
            val notSent = dispatcher.power(host, confirmation, observation(),
                JournalMutationSender { _, _, _ -> RequestOutcome.NotSent(failure) }) as ControlDispatchResult.Dispatched
            val entry = (notSent.result as JournalDispatchResult.Finished).entry
            assertNull("$failure", HostAccessPresentation.refusal(entry))
            assertNull("$failure", entry.rejectionCode)
            val said = entry.resultSummary.orEmpty().lowercase()
            assertTrue("$failure: $said", listOf("revok", "remov", "unpair", "forget").none { it in said })
            assertEquals(host, (repo.load() as StateLoadResult.Ready).state.activeHost)
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun observation(
        outputs: VersionedPayload.DisplayOutputs = outputs("available"),
        order: VersionedPayload.DisplayOrder? = null,
    ) = StatusPollState(
        lastRead = PairedStatusRead(status("sunshine-stopped"), outputs, order),
        lastSuccessMonotonicNanos = 1_000_000_000L, source = StatusObservationSource.from(host),
    )

    private fun status(name: String): VersionedPayload.Status =
        decoded(ApiRoute.STATUS, "status/$name") as VersionedPayload.Status

    private fun outputs(name: String) = decoded(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/$name") as VersionedPayload.DisplayOutputs
    private fun order(name: String) = decoded(ApiRoute.DISPLAY_ORDER, "display-order-get/$name") as VersionedPayload.DisplayOrder

    private fun decoded(route: ApiRoute, name: String): VersionedPayload {
        val body = fixtureResponse(name).second
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }

    /** The fixture's recorded error response, decoded by the real codec at its recorded status. */
    private fun rejected(route: ApiRoute, name: String): ApiReply.Rejected {
        val (status, body) = fixtureResponse(name)
        assertTrue("$name should record an error", status in 400..599)
        return ProtocolCodec.decodeResponse(route, HttpResponse(status, emptyMap(), BoundedJson.encode(body))) as ApiReply.Rejected
    }

    private fun fixtureResponse(name: String): Pair<Int, JsonValue.ObjectValue> {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        val response = document["response"].asObject("response")
        return response["status"].asLong("status").toInt() to response["body"].asObject("body")
    }
}
