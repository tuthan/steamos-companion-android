package io.github.tuthan.steamoscompanion.core.pairing

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.client.PersistentState
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StatePersistenceFailure
import io.github.tuthan.steamoscompanion.core.client.StateInvariantCode
import io.github.tuthan.steamoscompanion.core.client.StateInvariantException
import io.github.tuthan.steamoscompanion.core.client.StateUnavailableReason
import io.github.tuthan.steamoscompanion.core.client.StagedCredential
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.PairingState
import io.github.tuthan.steamoscompanion.core.protocol.PairedCredential
import io.github.tuthan.steamoscompanion.core.protocol.PairingWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.transport.CancellationSignal
import io.github.tuthan.steamoscompanion.core.transport.PinnedHttpTransport
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface PairingGateway {
    fun execute(candidate: PairingCandidate, request: ApiRequest, cancellation: CancellationSignal): RequestOutcome
}

/** Production adapter; every request remains on the selected LAN path and uses the candidate pin. */
class PinnedPairingGateway(
    private val transport: PinnedHttpTransport,
    private val networkAccess: NetworkAccess,
) : PairingGateway {
    override fun execute(candidate: PairingCandidate, request: ApiRequest, cancellation: CancellationSignal): RequestOutcome {
        val paths = networkAccess.activeLanPaths()
        val path = if (candidate.networkId != null) {
            paths.firstOrNull { it.id == candidate.networkId }
                ?: return RequestOutcome.NotSent(TransportFailure.CONNECT)
        } else {
            paths.firstOrNull() ?: return RequestOutcome.NotSent(TransportFailure.CONNECT)
        }
        val endpoint = try {
            HostEndpoint.parse(candidate.endpoint)
        } catch (_: IllegalArgumentException) {
            return RequestOutcome.NotSent(TransportFailure.VALIDATION)
        }
        return transport.execute(TransportTarget(endpoint, path), candidate.certificatePin, request, cancellation)
    }
}

sealed interface PairingAction {
    data object Idle : PairingAction
    data class Ready(val candidate: PairingCandidate) : PairingAction
    data class Pending(val candidate: PairingCandidate) : PairingAction
    data class RequestUnconfirmed(val candidate: PairingCandidate) : PairingAction
    data class Retryable(val candidate: PairingCandidate, val reason: String) : PairingAction
    data class RateLimited(val candidate: PairingCandidate, val retryAtMillis: Long) : PairingAction
    data class Approved(val activeHost: ActiveHost) : PairingAction
    data class Rejected(val candidate: PairingCandidate, val error: String, val message: String) : PairingAction
    data class Expired(val candidate: PairingCandidate) : PairingAction
    data class IdentityChanged(val expectedHostId: String?, val actualHostId: String?) : PairingAction
    data class Cancelled(val localOnly: Boolean, val remoteConfirmed: Boolean, val explanation: String) : PairingAction
    data class CredentialCouldNotBeSaved(val candidate: PairingCandidate?) : PairingAction
    data class UnsupportedState(val candidate: PairingCandidate, val state: String) : PairingAction
    data class InProgress(val candidate: PairingCandidate) : PairingAction
    data object StaleGeneration : PairingAction
    data class NeedsAttention(val candidate: PairingCandidate, val reason: String) : PairingAction
    data class InputRejected(val reason: String) : PairingAction
    data class PersistenceFailed(val failure: StatePersistenceFailure) : PairingAction
    data class StateUnavailable(val reason: StateUnavailableReason) : PairingAction
    data class Failed(val reason: String) : PairingAction
}

/**
 * Durable two-form pairing state machine. Public calls run on a bounded worker pool; no scrypt,
 * socket work, or approval polling runs on the caller/UI thread. Network results are committed
 * only through the persisted candidate generation, so cancel/replace wins against late replies.
 */
class PairingCoordinator(
    private val repository: EncryptedStateRepository,
    private val transactions: PairingStateTransactions,
    private val clock: Clock,
    private val gateway: PairingGateway,
    private val candidateFactory: PairingCandidateFactory = PairingCandidateFactory(),
    private val executor: Executor = DEFAULT_WORKER,
) {
    private val pollFlights = ConcurrentHashMap<Long, CompletableFuture<PairingAction>>()

    fun startNonce(
        discovered: UnverifiedDiscoveryCandidate,
        clientName: String = PairingCandidateFactory.DEFAULT_CLIENT_NAME,
        scopes: List<String> = PairingCandidateFactory.DEFAULT_SCOPES,
        cancellation: CancellationSignal = CancellationSignal(),
    ): CompletableFuture<PairingAction> = submit {
        val candidate = try {
            candidateFactory.fromDiscovery(discovered, clientName, scopes, clock.wallTimeMillis())
        } catch (error: IllegalArgumentException) {
            return@submit PairingAction.InputRejected(error.message ?: "Discovery candidate is invalid")
        }
        beginAndDispatch(candidate, cancellation)
    }

    fun startPayload(
        payloadText: String,
        networkId: String?,
        clientName: String = PairingCandidateFactory.DEFAULT_CLIENT_NAME,
        scopes: List<String> = PairingCandidateFactory.DEFAULT_SCOPES,
        cancellation: CancellationSignal = CancellationSignal(),
    ): CompletableFuture<PairingAction> = submit {
        val now = clock.wallTimeMillis()
        val candidate = try {
            val payload = PairingPayloadParser.parse(payloadText, now)
            candidateFactory.fromPayload(payload, networkId, clientName, scopes, now)
        } catch (error: IllegalArgumentException) {
            return@submit PairingAction.InputRejected(error.message ?: "Pairing payload is invalid")
        }
        beginAndDispatch(candidate, cancellation)
    }

    /** Explicit retry is allowed only after a complete not-sent result or a rate-limit interval. */
    fun requestInitial(generation: Long, cancellation: CancellationSignal = CancellationSignal()): CompletableFuture<PairingAction> = submit {
        val candidate = currentCandidate(generation) ?: return@submit PairingAction.StaleGeneration
        dispatchInitial(candidate, cancellation)
    }

    /** Repeated refresh requests for one generation share a single in-flight approval poll. */
    fun poll(generation: Long, cancellation: CancellationSignal = CancellationSignal()): CompletableFuture<PairingAction> {
        val next = CompletableFuture<PairingAction>()
        val existing = pollFlights.putIfAbsent(generation, next)
        if (existing != null) return existing
        try {
            executor.execute {
                try {
                    next.complete(pollBlocking(generation, cancellation))
                } catch (error: Exception) {
                    next.complete(PairingAction.Failed(error.message ?: "Pairing poll failed"))
                } finally {
                    pollFlights.remove(generation, next)
                }
            }
        } catch (error: Exception) {
            pollFlights.remove(generation, next)
            next.complete(PairingAction.Failed(error.message ?: "Pairing worker is unavailable"))
        }
        return next
    }

    /** Durable local invalidation happens before any best-effort remote cancellation. */
    fun cancel(generation: Long, cancellation: CancellationSignal = CancellationSignal()): CompletableFuture<PairingAction> = submit {
        cancelBlocking(generation, cancellation)
    }

    /** Restores state after process death; REQUESTING is conservatively treated as unconfirmed. */
    fun resume(): CompletableFuture<PairingAction> = submit {
        var candidate = when (val load = repository.load()) {
            StateLoadResult.Empty -> return@submit PairingAction.Idle
            is StateLoadResult.Unavailable -> return@submit PairingAction.StateUnavailable(load.reason)
            is StateLoadResult.Failed -> return@submit PairingAction.PersistenceFailed(load.reason)
            is StateLoadResult.Ready -> load.state.candidate ?: return@submit PairingAction.Idle
        }
        prepareVerificationCode(candidate)?.let { return@submit it }
        candidate = currentCandidate(candidate.generation) ?: return@submit PairingAction.StaleGeneration
        when (candidate.phase) {
            CandidatePhase.REQUESTING -> update(candidate.generation) {
                it.copy(phase = CandidatePhase.REQUEST_UNCONFIRMED)
            }.let { savedCandidate ->
                if (savedCandidate == null) PairingAction.StaleGeneration else PairingAction.RequestUnconfirmed(savedCandidate)
            }
            CandidatePhase.APPROVED_STAGED -> finalizeApproved(candidate.generation)
            CandidatePhase.PENDING, CandidatePhase.RATE_LIMITED -> {
                if (candidate.expiresAtMillis?.let { it <= clock.wallTimeMillis() } == true) {
                    terminal(candidate.generation, CandidatePhase.EXPIRED).let { result ->
                        result?.let(PairingAction::Expired) ?: PairingAction.StaleGeneration
                    }
                } else if (candidate.pairingSession == null) {
                    terminal(candidate.generation, CandidatePhase.CREDENTIAL_UNCONFIRMED).let { result ->
                        result?.let { PairingAction.NeedsAttention(it, "Pairing could not be confirmed after the app restarted") }
                            ?: PairingAction.StaleGeneration
                    }
                } else if (candidate.phase == CandidatePhase.RATE_LIMITED &&
                    (candidate.pollNotBeforeMillis ?: Long.MAX_VALUE) > clock.wallTimeMillis()
                ) {
                    PairingAction.RateLimited(candidate, requireNotNull(candidate.pollNotBeforeMillis))
                } else {
                    PairingAction.Pending(candidate)
                }
            }
            CandidatePhase.READY -> PairingAction.Ready(candidate)
            CandidatePhase.REQUEST_UNCONFIRMED -> PairingAction.RequestUnconfirmed(candidate)
            CandidatePhase.REJECTED -> PairingAction.Rejected(candidate, "pairing_rejected", "Pairing was rejected")
            CandidatePhase.EXPIRED -> PairingAction.Expired(candidate)
            CandidatePhase.IDENTITY_CHANGED -> PairingAction.IdentityChanged(candidate.hostId, null)
            CandidatePhase.CREDENTIAL_UNCONFIRMED -> PairingAction.NeedsAttention(candidate, CREDENTIAL_UNCERTAINTY_MESSAGE)
            CandidatePhase.CANCELLED -> PairingAction.Cancelled(true, true, "The host cancelled this request")
        }
    }

    private fun beginAndDispatch(candidate: PairingCandidate, cancellation: CancellationSignal): PairingAction {
        val state = when (val load = repository.load()) {
            StateLoadResult.Empty -> PersistentState()
            is StateLoadResult.Ready -> load.state
            is StateLoadResult.Unavailable -> return PairingAction.StateUnavailable(load.reason)
            is StateLoadResult.Failed -> return PairingAction.PersistenceFailed(load.reason)
        }
        val existing = state.activeHost
        if (existing != null && (sameEndpoint(existing.endpoint, candidate.endpoint) || existing.hostId == candidate.hostId) &&
            (existing.hostId != candidate.hostId || existing.certificatePin != candidate.certificatePin)
        ) {
            return PairingAction.IdentityChanged(existing.hostId, candidate.hostId)
        }
        val begun = transactions.beginCandidate(candidate)
        val fresh = (begun as? StateCommitResult.Saved)?.state?.candidate
        if (fresh == null) return commitFailure(begun)
        return dispatchInitial(fresh, cancellation)
    }

    private fun dispatchInitial(candidate: PairingCandidate, cancellation: CancellationSignal): PairingAction {
        if (candidate.phase != CandidatePhase.READY) {
            return when (candidate.phase) {
                CandidatePhase.REQUESTING -> PairingAction.InProgress(candidate)
                CandidatePhase.REQUEST_UNCONFIRMED -> PairingAction.RequestUnconfirmed(candidate)
                CandidatePhase.PENDING, CandidatePhase.RATE_LIMITED -> PairingAction.Pending(candidate)
                CandidatePhase.APPROVED_STAGED -> finalizeApproved(candidate.generation)
                CandidatePhase.EXPIRED -> PairingAction.Expired(candidate)
                CandidatePhase.CREDENTIAL_UNCONFIRMED -> PairingAction.NeedsAttention(candidate, CREDENTIAL_UNCERTAINTY_MESSAGE)
                else -> PairingAction.Rejected(candidate, "pairing_terminal", "Start a new pairing request")
            }
        }
        if (candidate.variant == PairingVariant.LEGACY_CODE) {
            return PairingAction.InputRejected("Client-chosen pairing codes are not accepted by the pinned host")
        }
        prepareVerificationCode(candidate)?.let { return it }
        val readyCandidate = currentCandidate(candidate.generation) ?: return PairingAction.StaleGeneration
        val now = clock.wallTimeMillis()
        readyCandidate.pollNotBeforeMillis?.let { if (now < it) return PairingAction.RateLimited(readyCandidate, it) }
        val payloadExpiry = readyCandidate.payloadExpiresAtMillis
        if (payloadExpiry != null && payloadExpiry <= now) {
            return terminal(readyCandidate.generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired) ?: PairingAction.StaleGeneration
        }
        val localExpiry = safeAdd(now, PAIRING_WINDOW_MILLIS)
        val claim = transactions.updateCandidate(readyCandidate.generation) { current ->
            if (current.phase != CandidatePhase.READY) {
                throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Pairing request is already in progress")
            }
            if (current.pollNotBeforeMillis?.let { now < it } == true) {
                throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Pairing request is rate limited")
            }
            current.copy(
                phase = CandidatePhase.REQUESTING,
                pairingId = if (current.variant == PairingVariant.ADVANCED_PAYLOAD) current.payloadId else null,
                pairingSession = null,
                requestStartedAtMillis = now,
                localExpiresAtMillis = localExpiry,
                hostExpiresAtMillis = null,
                expiresAtMillis = earlier(current.expiresAtMillis, localExpiry, payloadExpiry),
                pollNotBeforeMillis = null,
            )
        }
        val started = when (claim) {
            is StateCommitResult.Saved -> requireNotNull(claim.state.candidate)
            is StateCommitResult.Rejected -> {
                val latest = currentCandidate(candidate.generation) ?: return PairingAction.StaleGeneration
                when {
                    latest.phase == CandidatePhase.REQUESTING -> return PairingAction.InProgress(latest)
                    latest.phase == CandidatePhase.READY && latest.pollNotBeforeMillis?.let { now < it } == true ->
                        return PairingAction.RateLimited(latest, requireNotNull(latest.pollNotBeforeMillis))
                    else -> return actionForCurrentCandidate(latest)
                }
            }
            is StateCommitResult.Unavailable, is StateCommitResult.Failed -> return commitFailure(claim)
        }

        val requestBody = try {
            PairingRequestBuilder.request(started)
        } catch (error: IllegalArgumentException) {
            return PairingAction.InputRejected(error.message ?: "Pairing request is invalid")
        }
        val request = ApiRequest(HttpMethod.POST, ApiRoute.PAIR_REQUEST.path, requestBody)
        return when (val outcome = gateway.execute(started, request, cancellation)) {
            is RequestOutcome.NotSent -> resetNotSent(started, outcome.failure)
            is RequestOutcome.PairingUnresolved -> markUnconfirmed(started.generation, null, null)
            is RequestOutcome.Unknown -> markUnconfirmed(started.generation, null, null)
            is RequestOutcome.Rejected -> initialRejection(started, outcome.response.error, outcome.response.message, outcome.retryAfterMillis)
            is RequestOutcome.Reply -> {
                when (val response = outcome.response) {
                    is ApiReply.Versioned -> {
                        val pairing = response.payload as? VersionedPayload.Pairing
                            ?: return markUnconfirmed(started.generation, null, null)
                        consumeInitialReply(started, pairing)
                    }
                    is ApiReply.UnsupportedVersion -> markUnconfirmed(started.generation, null, null)
                    is ApiReply.Rejected -> initialRejection(started, response.error, response.message, outcome.retryAfterMillis)
                }
            }
        }
    }

    private fun consumeInitialReply(candidate: PairingCandidate, pairing: VersionedPayload.Pairing): PairingAction {
        val state = (pairing.state as? WireValue.Known)?.value
        if (pairing.hostId != null && pairing.hostId != candidate.hostId) {
            terminal(candidate.generation, CandidatePhase.IDENTITY_CHANGED)?.let { return PairingAction.IdentityChanged(candidate.hostId, pairing.hostId) }
            return PairingAction.StaleGeneration
        }
        val receivedPairingId = pairing.pairingId
        if (candidate.variant == PairingVariant.ADVANCED_PAYLOAD && receivedPairingId != candidate.payloadId) {
            terminal(candidate.generation, CandidatePhase.IDENTITY_CHANGED)
            return PairingAction.IdentityChanged(candidate.hostId, pairing.hostId)
        }
        return when (state) {
            PairingState.PENDING -> {
                val expires = hostExpiryMillis(pairing.expiresAt) ?: return markUnconfirmed(candidate.generation, receivedPairingId, null)
                if (pairing.pairingSession.isNullOrEmpty()) return markUnconfirmed(candidate.generation, receivedPairingId, expires)
                persistPending(candidate, receivedPairingId ?: return markUnconfirmed(candidate.generation, null, expires), pairing.pairingSession, expires)
            }
            PairingState.APPROVED -> completeApproval(candidate, pairing)
            PairingState.REJECTED -> terminal(candidate.generation, CandidatePhase.REJECTED)?.let {
                PairingAction.Rejected(it, "pairing_rejected", "Pairing was rejected by the device owner")
            } ?: PairingAction.StaleGeneration
            PairingState.CANCELLED -> terminal(candidate.generation, CandidatePhase.REJECTED)?.let {
                PairingAction.Rejected(it, "pairing_cancelled", "Pairing was cancelled by the host")
            } ?: PairingAction.StaleGeneration
            PairingState.EXPIRED -> terminal(candidate.generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired)
                ?: PairingAction.StaleGeneration
            null -> markUnconfirmed(candidate.generation, receivedPairingId, hostExpiryMillis(pairing.expiresAt))
        }
    }

    private fun pollBlocking(generation: Long, cancellation: CancellationSignal): PairingAction {
        val candidate = currentCandidate(generation) ?: return PairingAction.StaleGeneration
        when (candidate.phase) {
            CandidatePhase.RATE_LIMITED, CandidatePhase.PENDING -> {
                val retryAt = candidate.pollNotBeforeMillis ?: clock.wallTimeMillis()
                if (clock.wallTimeMillis() < retryAt) return PairingAction.RateLimited(candidate, retryAt)
            }
            CandidatePhase.APPROVED_STAGED -> return finalizeApproved(generation)
            CandidatePhase.REQUEST_UNCONFIRMED -> return PairingAction.RequestUnconfirmed(candidate)
            CandidatePhase.REQUESTING -> return PairingAction.InProgress(candidate)
            CandidatePhase.EXPIRED -> return PairingAction.Expired(candidate)
            CandidatePhase.CREDENTIAL_UNCONFIRMED -> return PairingAction.NeedsAttention(candidate, CREDENTIAL_UNCERTAINTY_MESSAGE)
            CandidatePhase.CANCELLED -> return PairingAction.Cancelled(true, true, "The host cancelled this request")
            else -> return PairingAction.Rejected(candidate, "pairing_terminal", "This pairing request cannot be polled")
        }
        val now = clock.wallTimeMillis()
        if (candidate.expiresAtMillis?.let { now >= it } == true) {
            return terminal(generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired) ?: PairingAction.StaleGeneration
        }
        if (candidate.pairingSession == null || candidate.pairingId == null) {
            return terminal(generation, CandidatePhase.CREDENTIAL_UNCONFIRMED)?.let {
                PairingAction.NeedsAttention(it, "Pairing could not be confirmed. Check or remove the pending request on the host before pairing again.")
            } ?: PairingAction.StaleGeneration
        }
        val body = try {
            PairingRequestBuilder.request(candidate)
        } catch (_: IllegalArgumentException) {
            return PairingAction.NeedsAttention(candidate, "Saved pairing data is incomplete; cancel it before starting again")
        }
        // Persist the normal two-second interval before the read-like poll; restarts cannot hammer the host.
        val claim = transactions.updateCandidate(generation) { current ->
            if (current.phase != CandidatePhase.PENDING && current.phase != CandidatePhase.RATE_LIMITED) {
                throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Pairing is no longer pollable")
            }
            if (current.pollNotBeforeMillis?.let { now < it } == true) {
                throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Pairing poll is rate limited")
            }
            if (current.expiresAtMillis?.let { now >= it } == true) {
                throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Pairing has expired")
            }
            current.copy(phase = CandidatePhase.PENDING, pollNotBeforeMillis = safeAdd(now, DEFAULT_POLL_INTERVAL_MILLIS))
        }
        val polling = when (claim) {
            is StateCommitResult.Saved -> requireNotNull(claim.state.candidate)
            is StateCommitResult.Rejected -> {
                val latest = currentCandidate(generation) ?: return PairingAction.StaleGeneration
                if (latest.expiresAtMillis?.let { now >= it } == true) {
                    return terminal(generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired) ?: PairingAction.StaleGeneration
                }
                if (latest.phase == CandidatePhase.RATE_LIMITED || latest.phase == CandidatePhase.PENDING) {
                    val retryAt = latest.pollNotBeforeMillis
                    if (retryAt != null && now < retryAt) return PairingAction.RateLimited(latest, retryAt)
                }
                return actionForCurrentCandidate(latest)
            }
            is StateCommitResult.Unavailable, is StateCommitResult.Failed -> return commitFailure(claim)
        }
        val request = ApiRequest(HttpMethod.POST, ApiRoute.PAIR_REQUEST.path, body)
        return when (val outcome = gateway.execute(polling, request, cancellation)) {
            is RequestOutcome.NotSent -> PairingAction.Pending(currentCandidate(generation) ?: polling)
            is RequestOutcome.PairingUnresolved, is RequestOutcome.Unknown -> PairingAction.Pending(currentCandidate(generation) ?: polling)
            is RequestOutcome.Rejected -> pollRejection(polling, outcome.response.error, outcome.response.message, outcome.retryAfterMillis)
            is RequestOutcome.Reply -> when (val response = outcome.response) {
                is ApiReply.Versioned -> {
                    val pairing = response.payload as? VersionedPayload.Pairing
                        ?: return PairingAction.Pending(currentCandidate(generation) ?: polling)
                    consumePollReply(polling, pairing)
                }
                is ApiReply.UnsupportedVersion -> PairingAction.UnsupportedState(polling, "protocol_version_${response.protocolVersion}")
                is ApiReply.Rejected -> pollRejection(polling, response.error, response.message, outcome.retryAfterMillis)
            }
        }
    }

    private fun consumePollReply(candidate: PairingCandidate, pairing: VersionedPayload.Pairing): PairingAction {
        val savedPairingId = candidate.pairingId ?: return PairingAction.NeedsAttention(candidate, "Saved pairing identity is incomplete")
        val savedSession = candidate.pairingSession ?: return PairingAction.NeedsAttention(candidate, "Saved pairing session is incomplete")
        if (pairing.pairingId != null && pairing.pairingId != candidate.pairingId) {
            terminal(candidate.generation, CandidatePhase.IDENTITY_CHANGED)
            return PairingAction.IdentityChanged(candidate.hostId, pairing.hostId)
        }
        if (pairing.hostId != null && pairing.hostId != candidate.hostId) {
            terminal(candidate.generation, CandidatePhase.IDENTITY_CHANGED)
            return PairingAction.IdentityChanged(candidate.hostId, pairing.hostId)
        }
        return when (val state = pairing.state) {
            is WireValue.Unknown -> PairingAction.UnsupportedState(candidate, state.raw)
            is WireValue.Known -> when (state.value) {
                PairingState.PENDING -> {
                    val hostExpiry = hostExpiryMillis(pairing.expiresAt)
                    persistPending(candidate, savedPairingId, savedSession, hostExpiry)
                }
                PairingState.APPROVED -> completeApproval(candidate, pairing)
                PairingState.REJECTED -> terminal(candidate.generation, CandidatePhase.REJECTED)?.let {
                    PairingAction.Rejected(it, "pairing_rejected", "Pairing was rejected by the device owner")
                } ?: PairingAction.StaleGeneration
                PairingState.CANCELLED -> terminal(candidate.generation, CandidatePhase.CANCELLED)?.let {
                    PairingAction.Cancelled(true, true, "The host no longer has this pending request")
                } ?: PairingAction.StaleGeneration
                PairingState.EXPIRED -> terminal(candidate.generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired)
                    ?: PairingAction.StaleGeneration
            }
        }
    }

    private fun persistPending(
        previous: PairingCandidate,
        pairingId: String,
        session: String,
        hostExpiryMillis: Long?,
    ): PairingAction {
        val now = clock.wallTimeMillis()
        val effectiveExpiry = earlier(previous.expiresAtMillis, previous.localExpiresAtMillis, previous.payloadExpiresAtMillis, hostExpiryMillis)
        if (effectiveExpiry != null && effectiveExpiry <= now) {
            return terminal(previous.generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired) ?: PairingAction.StaleGeneration
        }
        val notBefore = earlier(effectiveExpiry, safeAdd(now, DEFAULT_POLL_INTERVAL_MILLIS))
        val saved = update(previous.generation) {
            it.copy(
                phase = CandidatePhase.PENDING,
                pairingId = pairingId,
                pairingSession = session,
                hostExpiresAtMillis = minOfNullable(it.hostExpiresAtMillis, hostExpiryMillis),
                expiresAtMillis = effectiveExpiry,
                pollNotBeforeMillis = notBefore,
            )
        } ?: return PairingAction.StaleGeneration
        return PairingAction.Pending(saved)
    }

    private fun completeApproval(candidate: PairingCandidate, pairing: VersionedPayload.Pairing): PairingAction {
        val hostId = pairing.hostId
        val credential = pairing.credential
        val wakeTarget = pairing.wakeTarget
        val pairingId = pairing.pairingId
        if (hostId.isNullOrEmpty() || hostId != candidate.hostId || credential == null || wakeTarget == null || pairingId.isNullOrEmpty()) {
            terminal(candidate.generation, CandidatePhase.CREDENTIAL_UNCONFIRMED)
            return PairingAction.NeedsAttention(currentCandidate(candidate.generation) ?: candidate, CREDENTIAL_UNCERTAINTY_MESSAGE)
        }
        if (credential.clientId != candidate.clientId || credential.hostId != hostId ||
            !validGrantedScopes(candidate.requestedScopes, credential)
        ) {
            val unresolved = terminal(candidate.generation, CandidatePhase.CREDENTIAL_UNCONFIRMED)
            return unresolved?.let { PairingAction.NeedsAttention(it, CREDENTIAL_UNCERTAINTY_MESSAGE) }
                ?: PairingAction.StaleGeneration
        }
        if (candidate.pairingId != null && candidate.pairingId != pairingId) {
            terminal(candidate.generation, CandidatePhase.IDENTITY_CHANGED)
            return PairingAction.IdentityChanged(candidate.hostId, hostId)
        }
        val storedWake = wakeTarget.toStoredWakeTarget()
        val staged = StagedCredential(
            clientId = credential.clientId,
            hostId = hostId,
            bearerToken = credential.token,
            grantedScopes = credential.scopes,
            wakeTarget = storedWake,
            pairedAtMillis = clock.wallTimeMillis(),
        )
        val committed = transactions.updateCandidate(candidate.generation) {
            it.copy(
                phase = CandidatePhase.APPROVED_STAGED,
                pairingId = pairingId,
                hostId = hostId,
                verificationNonce = null,
                verificationCode = null,
                legacyPairingCode = null,
                payloadSecret = null,
                pairingSession = null,
                stagedCredential = staged,
                pollNotBeforeMillis = null,
            )
        }
        if (committed !is StateCommitResult.Saved) {
            return when (committed) {
                is StateCommitResult.Rejected -> if (committed.code.name == "STALE_GENERATION") PairingAction.StaleGeneration
                    else PairingAction.CredentialCouldNotBeSaved(currentCandidate(candidate.generation))
                is StateCommitResult.Failed, is StateCommitResult.Unavailable -> PairingAction.CredentialCouldNotBeSaved(currentCandidate(candidate.generation))
                is StateCommitResult.Saved -> error("unreachable")
            }
        }
        return finalizeApproved(candidate.generation)
    }

    private fun finalizeApproved(generation: Long): PairingAction = when (val result = transactions.acceptApprovedCandidate(generation)) {
        is StateCommitResult.Saved -> PairingAction.Approved(requireNotNull(result.state.activeHost))
        is StateCommitResult.Failed, is StateCommitResult.Unavailable -> PairingAction.CredentialCouldNotBeSaved(currentCandidate(generation))
        is StateCommitResult.Rejected -> if (result.code.name == "STALE_GENERATION") PairingAction.StaleGeneration
            else PairingAction.NeedsAttention(currentCandidate(generation) ?: return PairingAction.StaleGeneration, "Approved pairing is saved but could not be finalized")
    }

    private fun initialRejection(candidate: PairingCandidate, error: String, message: String, retryAfterMillis: Long?): PairingAction {
        if (error == "rate_limited") {
            val wait = (retryAfterMillis ?: DEFAULT_RATE_LIMIT_DELAY_MILLIS).coerceAtLeast(0)
            val retryAt = safeAdd(clock.wallTimeMillis(), wait)
            val saved = update(candidate.generation) {
                it.copy(
                    phase = CandidatePhase.READY,
                    pairingId = if (it.variant == PairingVariant.ADVANCED_PAYLOAD) it.payloadId else null,
                    pairingSession = null,
                    requestStartedAtMillis = null,
                    localExpiresAtMillis = null,
                    hostExpiresAtMillis = null,
                    expiresAtMillis = it.payloadExpiresAtMillis,
                    pollNotBeforeMillis = retryAt,
                )
            } ?: return PairingAction.StaleGeneration
            return PairingAction.RateLimited(saved, retryAt)
        }
        val phase = if (error == "pairing_expired") CandidatePhase.EXPIRED else CandidatePhase.REJECTED
        return terminal(candidate.generation, phase)?.let { terminal ->
            if (phase == CandidatePhase.EXPIRED) PairingAction.Expired(terminal) else PairingAction.Rejected(terminal, error, message)
        } ?: PairingAction.StaleGeneration
    }

    private fun pollRejection(candidate: PairingCandidate, error: String, message: String, retryAfterMillis: Long?): PairingAction {
        if (error == "rate_limited") {
            val wait = (retryAfterMillis ?: DEFAULT_RATE_LIMIT_DELAY_MILLIS).coerceAtLeast(0)
            val retryAt = safeAdd(clock.wallTimeMillis(), wait)
            val saved = update(candidate.generation) {
                it.copy(phase = CandidatePhase.RATE_LIMITED, pollNotBeforeMillis = retryAt)
            } ?: return PairingAction.StaleGeneration
            return PairingAction.RateLimited(saved, retryAt)
        }
        if (error == "pairing_consumed" || error == "pairing_not_found") {
            return terminal(candidate.generation, CandidatePhase.CREDENTIAL_UNCONFIRMED)?.let {
                PairingAction.NeedsAttention(it, CREDENTIAL_UNCERTAINTY_MESSAGE)
            } ?: PairingAction.StaleGeneration
        }
        if (error == "pairing_expired") {
            return terminal(candidate.generation, CandidatePhase.EXPIRED)?.let(PairingAction::Expired)
                ?: PairingAction.StaleGeneration
        }
        if (error == "pairing_rejected") {
            return terminal(candidate.generation, CandidatePhase.REJECTED)?.let { PairingAction.Rejected(it, error, message) }
                ?: PairingAction.StaleGeneration
        }
        if (error == "pairing_cancelled") {
            return terminal(candidate.generation, CandidatePhase.CANCELLED)?.let {
                PairingAction.Cancelled(true, true, "The host cancelled this request")
            } ?: PairingAction.StaleGeneration
        }
        return PairingAction.NeedsAttention(currentCandidate(candidate.generation) ?: candidate, message)
    }

    private fun resetNotSent(candidate: PairingCandidate, failure: TransportFailure): PairingAction {
        val saved = update(candidate.generation) {
            it.copy(
                phase = CandidatePhase.READY,
                pairingId = if (it.variant == PairingVariant.ADVANCED_PAYLOAD) it.payloadId else null,
                pairingSession = null,
                requestStartedAtMillis = null,
                localExpiresAtMillis = null,
                hostExpiresAtMillis = null,
                expiresAtMillis = it.payloadExpiresAtMillis,
                pollNotBeforeMillis = null,
            )
        } ?: return PairingAction.StaleGeneration
        return PairingAction.Retryable(saved, "Pairing request was not sent (${failure.name.lowercase()}); retry is safe")
    }

    private fun markUnconfirmed(generation: Long, pairingId: String?, hostExpiryMillis: Long?): PairingAction {
        val saved = update(generation) {
            val effective = earlier(it.expiresAtMillis, it.localExpiresAtMillis, it.payloadExpiresAtMillis, hostExpiryMillis)
            it.copy(
                phase = CandidatePhase.REQUEST_UNCONFIRMED,
                pairingId = pairingId ?: if (it.variant == PairingVariant.ADVANCED_PAYLOAD) it.payloadId else it.pairingId,
                pairingSession = null,
                hostExpiresAtMillis = minOfNullable(it.hostExpiresAtMillis, hostExpiryMillis),
                expiresAtMillis = effective,
            )
        } ?: return PairingAction.StaleGeneration
        return PairingAction.RequestUnconfirmed(saved)
    }

    private fun cancelBlocking(generation: Long, cancellation: CancellationSignal): PairingAction {
        val candidate = currentCandidate(generation) ?: return PairingAction.StaleGeneration
        if (candidate.phase == CandidatePhase.APPROVED_STAGED) {
            return PairingAction.NeedsAttention(candidate, "The approved credential is already saved; finish pairing before cancelling")
        }
        val result = transactions.cancelCandidate(generation)
        if (result !is StateCommitResult.Saved) {
            val latest = currentCandidate(generation)
            if (latest?.phase == CandidatePhase.APPROVED_STAGED) {
                return PairingAction.NeedsAttention(latest, "The approved credential is already saved; finish pairing before cancelling")
            }
            return commitFailure(result)
        }

        val canRemoteCancel = candidate.variant == PairingVariant.VERIFICATION &&
            !candidate.pairingId.isNullOrEmpty() && !candidate.verificationNonce.isNullOrEmpty()
        if (!canRemoteCancel) {
            val explanation = if (candidate.variant == PairingVariant.ADVANCED_PAYLOAD) {
                "Cancelled on this phone. The host may keep the payload request until it expires or its owner rejects it."
            } else {
                "Cancelled on this phone. The first reply did not provide a pairing ID, so the host request may remain until it expires."
            }
            return PairingAction.Cancelled(localOnly = true, remoteConfirmed = false, explanation)
        }
        val fields = linkedMapOf<String, JsonValue>(
            "pairing_id" to JsonValue.StringValue(requireNotNull(candidate.pairingId)),
            "verification_nonce" to JsonValue.StringValue(requireNotNull(candidate.verificationNonce)),
            "client_id" to JsonValue.StringValue(candidate.clientId),
        )
        candidate.pairingSession?.let { fields["pairing_session"] = JsonValue.StringValue(it) }
        val request = ApiRequest(HttpMethod.POST, ApiRoute.PAIR_CANCEL.path, JsonValue.ObjectValue(fields))
        val remote = runCatching { gateway.execute(candidate, request, cancellation) }.getOrNull()
        val confirmed = (remote as? RequestOutcome.Reply)?.response.let { reply ->
            val payload = (reply as? ApiReply.Versioned)?.payload as? VersionedPayload.Pairing
            payload?.state == WireValue.Known(PairingState.CANCELLED)
        }
        val explanation = when {
            confirmed -> "Cancelled on this phone and removed from the host's pending requests."
            (remote as? RequestOutcome.Rejected)?.response?.error == "pairing_already_approved" ->
                "Cancelled on this phone, but the host may already have approved it. Remove the abandoned client on the host if it appears."
            else -> "Cancelled on this phone. The request may remain on the host until it expires or its owner rejects it."
        }
        return PairingAction.Cancelled(localOnly = false, remoteConfirmed = confirmed, explanation)
    }

    private fun terminal(generation: Long, phase: CandidatePhase): PairingCandidate? = update(generation) {
        it.copy(
            phase = phase,
            verificationNonce = null,
            verificationCode = null,
            legacyPairingCode = null,
            payloadSecret = null,
            pairingSession = null,
            stagedCredential = null,
            pollNotBeforeMillis = null,
        )
    }

    private fun update(generation: Long, transform: (PairingCandidate) -> PairingCandidate): PairingCandidate? =
        (transactions.updateCandidate(generation, transform) as? StateCommitResult.Saved)?.state?.candidate

    /** Schema-v1 candidates predate the stored comparison code. Backfill it on the worker before use. */
    private fun prepareVerificationCode(candidate: PairingCandidate): PairingAction? {
        if (candidate.variant != PairingVariant.VERIFICATION || candidate.verificationCode != null ||
            candidate.verificationNonce == null || candidate.phase !in setOf(
                CandidatePhase.READY,
                CandidatePhase.REQUESTING,
                CandidatePhase.REQUEST_UNCONFIRMED,
                CandidatePhase.PENDING,
                CandidatePhase.RATE_LIMITED,
            )
        ) return null
        val code = try {
            SasCodeDeriver.derive(candidate.verificationNonce, candidate.certificatePin)
        } catch (_: IllegalArgumentException) {
            return PairingAction.NeedsAttention(candidate, "Saved pairing comparison data is invalid; cancel this request before starting again")
        }
        return when (val result = transactions.updateCandidate(candidate.generation) { current ->
            if (current.verificationNonce != candidate.verificationNonce) {
                throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Pairing nonce changed")
            }
            if (current.verificationCode == null) current.copy(verificationCode = code) else current
        }) {
            is StateCommitResult.Saved -> null
            is StateCommitResult.Rejected -> if (result.code == StateInvariantCode.STALE_GENERATION) PairingAction.StaleGeneration
                else PairingAction.Failed("Saved comparison code could not be restored")
            is StateCommitResult.Unavailable, is StateCommitResult.Failed -> commitFailure(result)
        }
    }

    private fun currentCandidate(generation: Long): PairingCandidate? =
        ((repository.load() as? StateLoadResult.Ready)?.state?.candidate)
            ?.takeIf { it.generation == generation }

    private fun actionForCurrentCandidate(candidate: PairingCandidate): PairingAction = when (candidate.phase) {
        CandidatePhase.READY -> PairingAction.Ready(candidate)
        CandidatePhase.REQUESTING -> PairingAction.InProgress(candidate)
        CandidatePhase.REQUEST_UNCONFIRMED -> PairingAction.RequestUnconfirmed(candidate)
        CandidatePhase.PENDING -> PairingAction.Pending(candidate)
        CandidatePhase.RATE_LIMITED -> candidate.pollNotBeforeMillis?.let { PairingAction.RateLimited(candidate, it) }
            ?: PairingAction.Pending(candidate)
        CandidatePhase.REJECTED -> PairingAction.Rejected(candidate, "pairing_rejected", "Pairing was rejected")
        CandidatePhase.EXPIRED -> PairingAction.Expired(candidate)
        CandidatePhase.IDENTITY_CHANGED -> PairingAction.IdentityChanged(candidate.hostId, null)
        CandidatePhase.CREDENTIAL_UNCONFIRMED -> PairingAction.NeedsAttention(candidate, CREDENTIAL_UNCERTAINTY_MESSAGE)
        CandidatePhase.CANCELLED -> PairingAction.Cancelled(true, true, "The host cancelled this request")
        CandidatePhase.APPROVED_STAGED -> finalizeApproved(candidate.generation)
    }

    private fun commitFailure(result: StateCommitResult): PairingAction = when (result) {
        is StateCommitResult.Unavailable -> PairingAction.StateUnavailable(result.reason)
        is StateCommitResult.Failed -> PairingAction.PersistenceFailed(result.reason)
        is StateCommitResult.Rejected -> if (result.code.name == "STALE_GENERATION") PairingAction.StaleGeneration
            else PairingAction.Failed("Pairing state was rejected (${result.code.name.lowercase()})")
        is StateCommitResult.Saved -> error("Saved state was not expected")
    }

    private fun <T : PairingAction> submit(block: () -> T): CompletableFuture<PairingAction> = CompletableFuture.supplyAsync(
        { try { block() } catch (error: Exception) { PairingAction.Failed(error.message ?: "Pairing operation failed") } },
        executor,
    )

    private fun hostExpiryMillis(value: JsonValue.NumberValue?): Long? {
        val seconds = value?.lexical?.toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds <= 0.0 || seconds > Long.MAX_VALUE.toDouble() / 1_000.0) return null
        return (seconds * 1_000.0).toLong()
    }

    private fun validGrantedScopes(requested: List<String>, credential: PairedCredential): Boolean =
        credential.scopes.isNotEmpty() && credential.scopes.distinct().size == credential.scopes.size &&
            "status.read" in credential.scopes && credential.scopes.all { it in requested }

    private fun PairingWakeTarget.toStoredWakeTarget() = StoredWakeTarget(
        available = available,
        mac = mac,
        interfaceName = interfaceName,
        sourceAddress = sourceAddress,
        reason = reason,
    ).validated()

    private fun sameEndpoint(left: String, right: String): Boolean = try {
        HostEndpoint.parse(left).authority == HostEndpoint.parse(right).authority
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun earlier(vararg values: Long?): Long? = values.filterNotNull().minOrNull()
    private fun minOfNullable(left: Long?, right: Long?): Long? = listOfNotNull(left, right).minOrNull()
    private fun safeAdd(value: Long, increment: Long): Long = if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private companion object {
        const val PAIRING_WINDOW_MILLIS = 120_000L
        const val DEFAULT_POLL_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_RATE_LIMIT_DELAY_MILLIS = 2_000L
        const val CREDENTIAL_UNCERTAINTY_MESSAGE =
            "Pairing could not be saved. The host may already list this client; remove the abandoned client on the host before pairing again."

        val DEFAULT_WORKER: Executor = ThreadPoolExecutor(
            4,
            4,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(64),
            PairingThreadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}

private class PairingThreadFactory : ThreadFactory {
    private val serial = AtomicLong()
    override fun newThread(runnable: Runnable): Thread = Thread(runnable, "companion-pairing-${serial.incrementAndGet()}").apply {
        isDaemon = true
    }
}
