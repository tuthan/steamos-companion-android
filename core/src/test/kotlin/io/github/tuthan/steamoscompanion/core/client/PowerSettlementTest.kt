package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.OperationState
import io.github.tuthan.steamoscompanion.core.protocol.OperationSummary
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSettlementTest {
    private val clock = object : Clock {
        override fun wallTimeMillis() = 100L
        override fun monotonicNanos() = 1_000_000_000L
    }
    private val repository = EncryptedStateRepository(MemoryStateStore(), JvmAesGcmStateCipher())
    private val journal = OperationJournal(repository, clock)
    private val settlement = PowerSettlement(repository, journal)
    private val host = testActiveHost().copy(lastObservedBootId = "boot-1")

    private fun dispatch(action: String, state: OperationState = OperationState.ACCEPTED, operationId: Boolean = true) {
        repository.transact { it.copy(activeHost = host) }
        val request = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject("request_id" to JsonValue.StringValue("power-$action"), "action" to JsonValue.StringValue(action)),
            host.bearerToken)
        journal.dispatchForHost(host, request) { _, _, _ ->
            if (operationId) RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
                OperationSummary("op-power-1", "power-$action", WireValue.Known(state), "power.$action", "t", "t", null, null),
                jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null)
            else RequestOutcome.Unknown(io.github.tuthan.steamoscompanion.core.transport.TransportFailure.IO)
        }
    }

    private fun entry() = (repository.load() as StateLoadResult.Ready).state.journal.single()
    private fun offline() = StatusPollState(unreachable = true, source = StatusObservationSource.from(host))
    private fun online(bootId: String): StatusPollState {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/status/sunshine-stopped.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        val status = ((ProtocolCodec.decodeResponse(ApiRoute.STATUS, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
            as ApiReply.Versioned).payload as VersionedPayload.Status)
        val withBoot = status.copy(bootId = bootId)
        return StatusPollState(lastRead = PairedStatusRead(withBoot, null, null), lastSuccessMonotonicNanos = 5L,
            source = StatusObservationSource.from(host))
    }

    @Test fun suspendAndShutdownSucceedWhenTheDeviceDropsOffAfterAcceptingTheRequest() {
        for (action in listOf("suspend", "shutdown")) {
            dispatch(action)
            assertTrue(settlement.observe(host, offline()))
            assertEquals(JournalPhase.SUCCEEDED, entry().phase)
            assertEquals("Device went offline after accepting the request", entry().resultSummary)
            assertFalse(OperationLanePresentation.hasUnresolved(host, (repository.load() as StateLoadResult.Ready).state))
            repository.transact { it.copy(journal = emptyList()) }
        }
    }

    @Test fun restartWaitsWhileTheDeviceIsOfflineAndSucceedsWhenItIsBackWithANewBoot() {
        dispatch("restart")
        assertFalse(settlement.observe(host, offline()))
        assertEquals(JournalPhase.ACCEPTED, entry().phase)
        assertFalse("same boot means the restart did not happen", settlement.observe(host, online("boot-1")))
        assertTrue(settlement.observe(host, online("boot-2")))
        assertEquals(JournalPhase.SUCCEEDED, entry().phase)
        assertEquals("Device restarted and is back online", entry().resultSummary)
    }

    @Test fun aRestartWithoutARecordedBootIdSucceedsAfterOfflineThenOnline() {
        repository.transact { it.copy(activeHost = host.copy(lastObservedBootId = null)) }
        val bare = host.copy(lastObservedBootId = null)
        val request = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject("request_id" to JsonValue.StringValue("power-restart"), "action" to JsonValue.StringValue("restart")),
            bare.bearerToken)
        journal.dispatchForHost(bare, request) { _, _, _ ->
            RequestOutcome.Reply(ApiReply.Versioned(1, VersionedPayload.Mutation(
                OperationSummary("op-power-1", "power-restart", WireValue.Known(OperationState.ACCEPTED), "power.restart",
                    "t", "t", null, null), jsonObject("protocol_version" to JsonValue.NumberValue("1")))), 202, null)
        }
        val settle = PowerSettlement(repository, journal)
        assertFalse(settle.observe(bare, online("boot-9")))
        assertFalse(settle.observe(bare, offline()))
        assertTrue(settle.observe(bare, online("boot-9")))
        assertEquals("Device is back online after restarting", entry().resultSummary)
    }

    @Test fun aRequestTheHostNeverAcknowledgedIsLeftForTheUser() {
        dispatch("suspend", operationId = false)
        assertFalse(settlement.observe(host, offline()))
        assertEquals(JournalPhase.UNKNOWN, entry().phase)
    }

    @Test fun anObservationFromAnotherCredentialIsIgnored() {
        dispatch("suspend")
        val other = StatusPollState(unreachable = true, source = StatusObservationSource.from(host.copy(clientId = "another")))
        assertFalse(settlement.observe(host, other))
        assertEquals(JournalPhase.ACCEPTED, entry().phase)
    }
}
