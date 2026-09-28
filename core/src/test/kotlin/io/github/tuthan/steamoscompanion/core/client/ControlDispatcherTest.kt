package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlDispatcherTest {
    private val clock = object : Clock {
        var nanos = 2_000_000_000L
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = nanos
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val dispatcher = ControlDispatcher(repository, journal, clock)
    private val host = testActiveHost()
    private val confirmation = PowerConfirmation.forHost(host, PowerVerb.RESTART)

    @Test fun acceptedPowerRequestIsJournaledButDoesNotClaimPhysicalRestart() {
        repository.transact { it.copy(activeHost = host) }
        var sends = 0
        val result = dispatcher.power(host, confirmation, observation(), JournalMutationSender { target, request, body ->
            sends++
            assertEquals(host, target)
            assertEquals(ApiRoute.POWER.path, request.path)
            assertTrue(body.toString(Charsets.UTF_8).contains("\"action\":\"restart\""))
            RequestOutcome.Unknown(TransportFailure.IO)
        }) as ControlDispatchResult.Dispatched
        assertEquals(1, sends)
        assertEquals(JournalPhase.UNKNOWN, (result.result as JournalDispatchResult.Finished).entry.phase)
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.OPERATION_UNRESOLVED),
            dispatcher.power(host, confirmation, observation(), JournalMutationSender { _, _, _ -> error("must not resend") }))
    }

    @Test fun staleScopePreviewAndConfirmationFailuresNeverDispatch() {
        repository.transact { it.copy(activeHost = host.copy(grantedScopes = listOf("status.read"))) }
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.MISSING_SCOPE), dispatcher.power(host, confirmation, observation(), sender))
        repository.transact { it.copy(activeHost = host) }
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.CONFIRMATION_MISMATCH),
            dispatcher.power(host, confirmation.copy(target = confirmation.target.copy(endpoint = "other.local:18443")), observation(), sender))
        clock.nanos = 20_000_000_000L
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.STATUS_NOT_FRESH), dispatcher.power(host, confirmation, observation(), sender))
        clock.nanos = 2_000_000_000L
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.PREVIEW_ACTIVE),
            dispatcher.power(host, confirmation, observation(outputs = fixtureOutputs("with-active-preview")), sender))
    }

    @Test fun sunshineRequiresFreshStoppedProviderState() {
        repository.transact { it.copy(activeHost = host) }
        val sender = JournalMutationSender { _, _, _ -> RequestOutcome.NotSent(TransportFailure.IO) }
        val running = fixtureStatus("sunshine-running")
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_NOT_STOPPED),
            dispatcher.recoverSunshine(host, observation(status = running), sender))
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_MONITORING_OFF),
            dispatcher.recoverSunshine(host, observation(status = fixtureStatus("sunshine-disabled")), sender))
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_PROVIDER_UNAVAILABLE),
            dispatcher.recoverSunshine(host, observation(status = fixtureStatus("sunshine-owner-unavailable")), sender))
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_STATE_UNKNOWN),
            dispatcher.recoverSunshine(host, observation(status = fixtureStatus("sunshine-unknown")), sender))
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_NOT_STOPPED),
            dispatcher.recoverSunshine(host, observation(status = running.copy(
                capabilities = running.capabilities + ("sunshine_restart" to WireValue.Known(CapabilityState.AVAILABLE)),
            )), sender))
        val advertised = fixtureStatus("sunshine-stopped").capabilities +
            ("sunshine_restart" to WireValue.Known(CapabilityState.AVAILABLE))
        for ((state, reason) in listOf(
            SunshineState.DISABLED to ControlBlock.SUNSHINE_MONITORING_OFF,
            SunshineState.UNAVAILABLE to ControlBlock.SUNSHINE_PROVIDER_UNAVAILABLE,
            SunshineState.UNKNOWN to ControlBlock.SUNSHINE_STATE_UNKNOWN,
        )) {
            assertEquals(ControlDispatchResult.Blocked(reason),
                dispatcher.recoverSunshine(host, observation(status = fixtureStatus("sunshine-stopped").copy(
                    sunshineState = WireValue.Known(state), capabilities = advertised,
                )), sender))
        }
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_STATE_UNKNOWN),
            dispatcher.recoverSunshine(host, observation(status = fixtureStatus("sunshine-stopped").copy(
                sunshineState = WireValue.Unknown("future_state"), capabilities = advertised,
            )), sender))
        assertTrue(dispatcher.recoverSunshine(host, observation(), sender) is ControlDispatchResult.Dispatched)
    }

    @Test fun sunshineRaceRejectionStaysFailedEvenWhenProcessIsLaterObservedRunning() {
        repository.transact { it.copy(activeHost = host) }
        var sends = 0
        val sent = dispatcher.recoverSunshine(host, observation(), JournalMutationSender { _, _, _ ->
            sends++
            RequestOutcome.Rejected(ApiReply.Rejected(409, "not_stopped", "Sunshine became running"), null)
        }) as ControlDispatchResult.Dispatched
        assertEquals(JournalPhase.FAILED, (sent.result as JournalDispatchResult.Finished).entry.phase)
        val state = (repository.load() as StateLoadResult.Ready).state
        val card = ControlOperationPresentation.project(host, state,
            observation(status = fixtureStatus("sunshine-running")), clock.nanos).single()
        assertEquals(JournalPhase.FAILED, card.phase)
        assertEquals(ControlObservation.SUNSHINE_RUNNING, card.observed)
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.SUNSHINE_NOT_STOPPED),
            dispatcher.recoverSunshine(host, observation(status = fixtureStatus("sunshine-running")),
                JournalMutationSender { _, _, _ -> error("must not resend") }))
        assertEquals(1, sends)
    }

    @Test fun oldCredentialObservationCannotAuthorizeSameHostAfterRePairing() {
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        repository.transact { it.copy(activeHost = rotated) }
        val sender = JournalMutationSender { _, _, _ -> error("must not send") }
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.OBSERVATION_PAIRING_CHANGED),
            dispatcher.power(rotated, PowerConfirmation.forHost(rotated, PowerVerb.RESTART), observation(), sender))
    }

    @Test fun confirmationFromOldCredentialCannotAuthorizeSameHostAfterRePairing() {
        val replacements = listOf(
            host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx"),
            host.copy(pairedAtMillis = host.pairedAtMillis + 1),
            host.copy(certificatePin = "sha256:" + "b".repeat(64)),
        )
        for (replacement in replacements) {
            repository.transact { it.copy(activeHost = replacement) }
            val fresh = observation().copy(source = StatusObservationSource.from(replacement))
            assertEquals(ControlDispatchResult.Blocked(ControlBlock.CONFIRMATION_MISMATCH),
                dispatcher.power(replacement, confirmation, fresh,
                    JournalMutationSender { _, _, _ -> error("must not send") }))
        }
    }

    @Test fun oldCredentialUnknownOperationDoesNotBlockNewPairingControls() {
        val old = JournalEntry.create(1, host, testPowerRequest("old-unknown"),
            BoundedJson.encode(testPowerRequest("old-unknown").body!!), 100)
            .copy(phase = JournalPhase.UNKNOWN)
        val rotated = host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        repository.transact { it.copy(activeHost = rotated, journal = listOf(old)) }
        val result = dispatcher.power(rotated, PowerConfirmation.forHost(rotated, PowerVerb.RESTART),
            observation().copy(source = StatusObservationSource.from(rotated)),
            JournalMutationSender { _, _, _ -> RequestOutcome.NotSent(TransportFailure.CONNECT) })
        assertTrue(result is ControlDispatchResult.Dispatched)
    }

    @Test fun legacyUnknownOperationStillBlocksPossibleCurrentPairingConflict() {
        val request = testPowerRequest("legacy-unknown")
        val legacy = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 100)
            .copy(originalPairedAtMillis = null, originalBearerSha256 = null, phase = JournalPhase.UNKNOWN)
        repository.transact { it.copy(activeHost = host, journal = listOf(legacy)) }
        assertEquals(ControlDispatchResult.Blocked(ControlBlock.OPERATION_UNRESOLVED),
            dispatcher.power(host, confirmation, observation(), JournalMutationSender { _, _, _ -> error("must not send") }))
    }

    private fun observation(
        status: VersionedPayload.Status = fixtureStatus("sunshine-stopped"),
        outputs: VersionedPayload.DisplayOutputs = fixtureOutputs("available"),
    ) = StatusPollState(lastRead = PairedStatusRead(status, outputs, null),
        lastSuccessMonotonicNanos = 1_000_000_000L, source = StatusObservationSource.from(host))

    private fun fixtureStatus(name: String): VersionedPayload.Status =
        fixture(ApiRoute.STATUS, "status/$name") as VersionedPayload.Status

    private fun fixtureOutputs(name: String): VersionedPayload.DisplayOutputs =
        fixture(ApiRoute.DISPLAY_OUTPUTS, "display-outputs/$name") as VersionedPayload.DisplayOutputs

    private fun fixture(route: ApiRoute, name: String): VersionedPayload {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }
}
