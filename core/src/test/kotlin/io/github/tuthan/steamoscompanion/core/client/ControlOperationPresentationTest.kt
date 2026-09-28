package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlOperationPresentationTest {
    private val host = testActiveHost().copy(lastObservedBootId = "boot-before")
    private val nowNanos = 2_000_000_000L

    @Test fun restartCardSeparatesAcceptedOperationFromFreshBootIdChange() {
        val entry = entry(power("restart", "restart-card"), JournalPhase.ACCEPTED)
        val state = PersistentState(activeHost = host.copy(lastObservedBootId = "boot-after"), journal = listOf(entry))
        val fresh = observation(status("sunshine-stopped").copy(bootId = "boot-after"))
        val card = ControlOperationPresentation.project(host, state, fresh, nowNanos).single()
        assertEquals(ControlOperationKind.RESTART, card.kind)
        assertEquals(JournalPhase.ACCEPTED, card.phase)
        assertEquals(ControlObservation.BOOT_ID_CHANGED, card.observed)
        assertTrue(card.pairingProven)

        assertEquals(ControlObservation.HOST_REACHABLE,
            ControlOperationPresentation.project(host, state,
                observation(status("sunshine-stopped").copy(bootId = "boot-before")), nowNanos).single().observed)
        assertEquals(ControlObservation.NOT_CHECKED,
            ControlOperationPresentation.project(host, state, fresh.copy(stale = true), nowNanos).single().observed)
        assertEquals(ControlObservation.NOT_CHECKED,
            ControlOperationPresentation.project(host, state, fresh.copy(source = null), nowNanos).single().observed)
    }

    @Test fun shutdownDisconnectAndSunshineRunningStaySeparateFromHostOperationPhase() {
        val shutdown = entry(power("shutdown", "shutdown-card"), JournalPhase.OBSERVED_RETURN)
        val sunshineRequest = ApiRequest(HttpMethod.POST, ApiRoute.SUNSHINE_RESTART.path,
            jsonObject("request_id" to JsonValue.StringValue("sunshine-card")), host.bearerToken)
        val sunshine = entry(sunshineRequest, JournalPhase.SUCCEEDED, 2)
        val state = PersistentState(activeHost = host, journal = listOf(shutdown, sunshine))
        val disconnected = ControlOperationPresentation.project(host, state,
            observation(status("sunshine-running")).copy(unreachable = true), nowNanos)
        assertEquals(ControlObservation.CONNECTION_LOST, disconnected.single { it.kind == ControlOperationKind.SHUTDOWN }.observed)
        assertEquals(JournalPhase.OBSERVED_RETURN, disconnected.single { it.kind == ControlOperationKind.SHUTDOWN }.phase)

        val connected = ControlOperationPresentation.project(host, state, observation(status("sunshine-running")), nowNanos)
        assertEquals(ControlObservation.HOST_REACHABLE,
            connected.single { it.kind == ControlOperationKind.SHUTDOWN }.observed)
        assertEquals(ControlObservation.SUNSHINE_RUNNING,
            connected.single { it.kind == ControlOperationKind.SUNSHINE_RESTART }.observed)
        assertEquals(JournalPhase.SUCCEEDED,
            connected.single { it.kind == ControlOperationKind.SUNSHINE_RESTART }.phase)
    }

    @Test fun unresolvedLegacyControlRemainsVisibleWithoutAttributingCurrentHostObservation() {
        val legacy = entry(power("suspend", "legacy-card"), JournalPhase.UNKNOWN)
            .copy(originalPairedAtMillis = null, originalBearerSha256 = null)
        val state = PersistentState(activeHost = host, journal = listOf(legacy))
        val card = ControlOperationPresentation.project(host, state, observation(status("sunshine-stopped")), nowNanos).single()
        assertFalse(card.pairingProven)
        assertEquals(ControlObservation.UNPROVEN_PAIRING, card.observed)

        val refreshed = host.copy(bearerToken = "refreshed-token-xxxxxxxxxxxxxxxxxxxxxxxx")
        val provenOldEntry = entry(power("suspend", "old-pairing-card"), JournalPhase.UNKNOWN)
        val refreshedState = PersistentState(activeHost = refreshed, journal = listOf(provenOldEntry))
        assertTrue(ControlOperationPresentation.project(refreshed, refreshedState,
            observation(status("sunshine-stopped")).copy(source = StatusObservationSource.from(refreshed)), nowNanos).isEmpty())
    }

    private fun power(action: String, requestId: String) = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
        jsonObject("request_id" to JsonValue.StringValue(requestId),
            "action" to JsonValue.StringValue(action)), host.bearerToken)

    private fun entry(request: ApiRequest, phase: JournalPhase, generation: Long = 1): JournalEntry =
        JournalEntry.create(generation, host, request, BoundedJson.encode(requireNotNull(request.body)), 100)
            .copy(phase = phase, operationId = if (phase == JournalPhase.UNKNOWN) null else "op-card-$generation")

    private fun observation(status: VersionedPayload.Status) = StatusPollState(
        lastRead = PairedStatusRead(status, null, null), lastSuccessMonotonicNanos = 1_000_000_000L,
        source = StatusObservationSource.from(host),
    )

    private fun status(name: String): VersionedPayload.Status {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/status/$name.json"))
        val body = fixture["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(ApiRoute.STATUS,
            HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload as VersionedPayload.Status
    }
}
