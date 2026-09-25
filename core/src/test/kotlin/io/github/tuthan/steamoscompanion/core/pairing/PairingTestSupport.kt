package io.github.tuthan.steamoscompanion.core.pairing

import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.JvmAesGcmStateCipher
import io.github.tuthan.steamoscompanion.core.client.MemoryStateStore
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.PairingState
import io.github.tuthan.steamoscompanion.core.protocol.PairedCredential
import io.github.tuthan.steamoscompanion.core.protocol.PairingWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

internal const val TEST_NOW = 1_790_000_000_000L
internal val TEST_PIN = "sha256:" + "ab".repeat(32)
internal const val TEST_HOST = "host-fixture0001xxxxxxxxxxxxx"
internal const val TEST_PAIRING_ID = "pair-fixture0001xxxxxxxxxxxxx"
internal const val TEST_SESSION = "fixture-token-0001-xxxxxxxxxxxxxxxxxxxxxxxx"
internal const val TEST_TOKEN = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx"

internal class FakeClock(var now: Long = TEST_NOW) : Clock {
    override fun wallTimeMillis(): Long = now
    override fun monotonicNanos(): Long = now * 1_000_000L
}

internal class ScriptedPairingGateway(
    var handler: (PairingCandidate, ApiRequest, CancellationSignal) -> RequestOutcome,
) : PairingGateway {
    val requests = CopyOnWriteArrayList<ApiRequest>()
    val candidates = CopyOnWriteArrayList<PairingCandidate>()

    override fun execute(candidate: PairingCandidate, request: ApiRequest, cancellation: CancellationSignal): RequestOutcome {
        candidates += candidate
        requests += request
        return handler(candidate, request, cancellation)
    }
}

internal data class PairingTestRig(
    val clock: FakeClock,
    val store: MemoryStateStore,
    val cipher: JvmAesGcmStateCipher,
    val repository: EncryptedStateRepository,
    val gateway: ScriptedPairingGateway,
    val coordinator: PairingCoordinator,
)

internal fun rig(
    handler: (PairingCandidate, ApiRequest, CancellationSignal) -> RequestOutcome,
    now: Long = TEST_NOW,
    executor: Executor = Executor { it.run() },
): PairingTestRig {
    val clock = FakeClock(now)
    val store = MemoryStateStore()
    val cipher = JvmAesGcmStateCipher()
    val repository = EncryptedStateRepository(store, cipher)
    val gateway = ScriptedPairingGateway(handler)
    val coordinator = PairingCoordinator(
        repository = repository,
        transactions = io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions(repository),
        clock = clock,
        gateway = gateway,
        executor = executor,
    )
    return PairingTestRig(clock, store, cipher, repository, gateway, coordinator)
}

internal fun pendingReply(
    pairingId: String = TEST_PAIRING_ID,
    session: String? = TEST_SESSION,
    expiresAtMillis: Long = TEST_NOW + 90_000,
) = RequestOutcome.Reply(
    ApiReply.Versioned(
        1,
        VersionedPayload.Pairing(
            state = WireValue.Known(PairingState.PENDING),
            pairingId = pairingId,
            hostId = null,
            expiresAt = JsonValue.NumberValue((expiresAtMillis / 1_000.0).toString()),
            pairingSession = session,
            credential = null,
            wakeTarget = null,
            body = jsonObject(),
        ),
    ),
    200,
    null,
)

internal fun approvedReply(
    pairingId: String = TEST_PAIRING_ID,
    hostId: String = TEST_HOST,
    token: String = TEST_TOKEN,
    clientId: String,
    scopes: List<String> = listOf("status.read", "power.control", "display.control"),
) = RequestOutcome.Reply(
    ApiReply.Versioned(
        1,
        VersionedPayload.Pairing(
            state = WireValue.Known(PairingState.APPROVED),
            pairingId = pairingId,
            hostId = hostId,
            expiresAt = null,
            pairingSession = null,
            credential = PairedCredential(clientId, token, scopes, hostId),
            wakeTarget = PairingWakeTarget(true, "001122334455", "enp5s0", "192.168.50.24", null),
            body = jsonObject(),
        ),
    ),
    200,
    null,
)

internal fun cancelledReply() = RequestOutcome.Reply(
    ApiReply.Versioned(
        1,
        VersionedPayload.Pairing(
            state = WireValue.Known(PairingState.CANCELLED),
            pairingId = TEST_PAIRING_ID,
            hostId = null,
            expiresAt = null,
            pairingSession = null,
            credential = null,
            wakeTarget = null,
            body = jsonObject(),
        ),
    ),
    200,
    null,
)

internal fun unconfirmedReply() = RequestOutcome.PairingUnresolved(TransportFailure.TIMEOUT)

internal fun repositoryState(rig: PairingTestRig) = (rig.repository.load() as io.github.tuthan.steamoscompanion.core.client.StateLoadResult.Ready).state
