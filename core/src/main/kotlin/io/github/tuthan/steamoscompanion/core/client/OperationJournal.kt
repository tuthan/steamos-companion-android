package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.OperationState
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure

fun interface JournalMutationSender {
    /** Called only after the matching PREPARED entry is durably committed. */
    fun send(host: ActiveHost, request: ApiRequest, exactSerializedBody: ByteArray): RequestOutcome
}

sealed interface JournalDispatchResult {
    data class NotDispatched(val code: StateInvariantCode) : JournalDispatchResult
    data class PersistenceUnavailable(val reason: StateUnavailableReason) : JournalDispatchResult
    data class PersistenceFailed(val reason: StatePersistenceFailure) : JournalDispatchResult
    data class Finished(val entry: JournalEntry, val remoteOutcome: RequestOutcome) : JournalDispatchResult
    /** The pre-dispatch record remains durable; the remote result could not be committed locally. */
    data class ResultNotConfirmed(val entry: JournalEntry, val remoteOutcome: RequestOutcome, val operationId: String?) : JournalDispatchResult
}

sealed interface JournalUpdateResult {
    data object Saved : JournalUpdateResult
    data class Rejected(val code: StateInvariantCode) : JournalUpdateResult
    data class PersistenceUnavailable(val reason: StateUnavailableReason) : JournalUpdateResult
    data class PersistenceFailed(val reason: StatePersistenceFailure) : JournalUpdateResult
}

/** Crash-safe pre-dispatch journal and operation reconciliation; never resends an entry. */
class OperationJournal(
    private val repository: EncryptedStateRepository,
    private val clock: Clock,
) {
    /** Refuse a queued UI action if the selected pairing changed before durable dispatch. */
    fun dispatchForHost(expected: ActiveHost, request: ApiRequest, sender: JournalMutationSender): JournalDispatchResult =
        dispatchInternal(request, sender) { state ->
            val current = state.activeHost
                ?: throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "There is no active paired host")
            if (current.hostId != expected.hostId || current.clientId != expected.clientId ||
                current.endpoint != expected.endpoint || current.certificatePin != expected.certificatePin ||
                current.pairedAtMillis != expected.pairedAtMillis || current.bearerToken != expected.bearerToken
            ) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Selected pairing changed")
            PreparedTarget(current, request, state)
        }

    fun dispatch(request: ApiRequest, sender: JournalMutationSender): JournalDispatchResult = dispatchInternal(request, sender) { state ->
        val host = state.activeHost
            ?: throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "There is no active paired host")
        PreparedTarget(host, request, state)
    }

    /**
     * Discarding an approved replacement first journals its self-revocation with the staged
     * credential, clears the staged secret, and advances the pairing generation in the same
     * durable pre-dispatch commit. The old active credential is never substituted into this
     * request; a complete revoke also clears it if the server identity matches.
     */
    fun revokeAndDiscardApprovedCandidate(
        expectedGeneration: Long,
        requestId: String,
        sender: JournalMutationSender,
    ): JournalDispatchResult {
        val candidate = when (val loaded = repository.load()) {
            StateLoadResult.Empty -> return JournalDispatchResult.NotDispatched(StateInvariantCode.INVALID_CANDIDATE)
            is StateLoadResult.Ready -> loaded.state.candidate
                ?: return JournalDispatchResult.NotDispatched(StateInvariantCode.INVALID_CANDIDATE)
            is StateLoadResult.Unavailable -> return JournalDispatchResult.PersistenceUnavailable(loaded.reason)
            is StateLoadResult.Failed -> return JournalDispatchResult.PersistenceFailed(loaded.reason)
        }
        if (candidate.generation != expectedGeneration || candidate.phase != CandidatePhase.APPROVED_STAGED ||
            candidate.hostId == null || candidate.stagedCredential == null
        ) return JournalDispatchResult.NotDispatched(StateInvariantCode.INVALID_CANDIDATE)

        val staged = candidate.stagedCredential
        val host = ActiveHost(
            hostId = candidate.hostId,
            clientId = staged.clientId,
            endpoint = candidate.endpoint,
            certificatePin = candidate.certificatePin,
            bearerToken = staged.bearerToken,
            grantedScopes = staged.grantedScopes,
            wakeTarget = staged.wakeTarget,
            pairedAtMillis = staged.pairedAtMillis,
        ).validated()
        val request = ApiRequest(
            io.github.tuthan.steamoscompanion.core.protocol.HttpMethod.POST,
            ApiRoute.PAIR_REVOKE_SELF.path,
            io.github.tuthan.steamoscompanion.core.protocol.jsonObject(
                "request_id" to io.github.tuthan.steamoscompanion.core.protocol.JsonValue.StringValue(requestId),
            ),
            staged.bearerToken,
        )
        return dispatchInternal(request, sender) { state ->
            val current = state.candidate
            if (state.pairingGeneration != expectedGeneration || current != candidate) {
                throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Approved candidate changed before cleanup")
            }
            if (state.pairingGeneration == Long.MAX_VALUE) {
                throw StateInvariantException(StateInvariantCode.INVALID_FIELD, "Pairing generation exhausted")
            }
            PreparedTarget(
                host,
                request,
                state.copy(pairingGeneration = state.pairingGeneration + 1, candidate = null),
            )
        }
    }

    private fun dispatchInternal(
        request: ApiRequest,
        sender: JournalMutationSender,
        prepare: (PersistentState) -> PreparedTarget,
    ): JournalDispatchResult {
        val encoded = try {
            ProtocolCodec.encodeRequest(request)
        } catch (_: Exception) {
            return JournalDispatchResult.NotDispatched(StateInvariantCode.INVALID_JOURNAL)
        }
        if (encoded.route.route !in JOURNALED_ROUTES || encoded.body.size > PersistentStateLimits.MAX_OPERATION_BODY_BYTES) {
            return JournalDispatchResult.NotDispatched(StateInvariantCode.INVALID_JOURNAL)
        }

        var preparedEntry: JournalEntry? = null
        var preparedTarget: PreparedTarget? = null
        val now = clock.wallTimeMillis().coerceAtLeast(0)
        val preparedCommit = repository.transact { state ->
            val target = prepare(state)
            val host = target.host
            if (request.bearerToken != host.bearerToken) {
                throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "Mutation credential differs from selected pairing")
            }
            val pruned = pruneExpiredTerminalEntries(state.journal, now)
            if (pruned.any { it.hostId == host.hostId && it.clientId == host.clientId && it.requestId == request.requestId() }) {
                throw StateInvariantException(StateInvariantCode.DUPLICATE_REQUEST_ID, "Request ID is already journaled")
            }
            val entry = JournalEntry.create(
                localGeneration = nextLocalGeneration(state),
                host = host,
                request = request,
                exactBodyBytes = encoded.body,
                nowMillis = now,
            )
            val nextState = target.state.copy(
                schemaVersion = PersistentStateLimits.CURRENT_SCHEMA_VERSION,
                revision = state.revision + 1,
                journal = pruned + entry,
            )
            val room = makeRoom(nextState)
            if (encoded.route.route != ApiRoute.PAIR_REVOKE_SELF) {
                val unresolved = room.filter {
                    it.localGeneration != entry.localGeneration && it.phase.unresolved && it.mayConflictWith(host)
                }
                if (unresolved.isNotEmpty() && !(unresolved.size == 1 &&
                    isOwnedPreviewRecovery(unresolved.single(), host, request))
                ) throw StateInvariantException(StateInvariantCode.OPERATION_UNRESOLVED,
                    "A conflicting host operation is unresolved")
            }
            preparedEntry = entry
            preparedTarget = target
            target.state.copy(journal = room)
        }

        when (preparedCommit) {
            is StateCommitResult.Rejected -> return JournalDispatchResult.NotDispatched(preparedCommit.code)
            is StateCommitResult.Unavailable -> return JournalDispatchResult.PersistenceUnavailable(preparedCommit.reason)
            is StateCommitResult.Failed -> return JournalDispatchResult.PersistenceFailed(preparedCommit.reason)
            is StateCommitResult.Saved -> Unit
        }
        val entry = preparedEntry ?: return JournalDispatchResult.PersistenceFailed(StatePersistenceFailure.CRYPTO)
        val target = preparedTarget ?: return JournalDispatchResult.PersistenceFailed(StatePersistenceFailure.CRYPTO)
        val remoteOutcome = try {
            sender.send(target.host, target.request, entry.bodyBytes())
        } catch (_: Exception) {
            RequestOutcome.Unknown(TransportFailure.IO)
        }
        val operation = (remoteOutcome as? RequestOutcome.Reply)
            ?.response.let { it as? ApiReply.Versioned }
            ?.payload.let { it as? VersionedPayload.Mutation }
            ?.operation
        val operationId = operation?.takeIf {
            it.requestId == entry.requestId && it.kind == entry.expectedOperationKind()
        }?.id
        val postCommit = repository.transact { state ->
            val index = state.journal.indexOfFirst { it.localGeneration == entry.localGeneration }
            if (index < 0) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Prepared journal entry disappeared")
            val old = state.journal[index]
            if (old.requestId != entry.requestId || old.bodySha256 != entry.bodySha256) {
                throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Prepared journal entry changed")
            }
            val updated = applyOutcome(old, remoteOutcome, now)
            val nextJournal = state.journal.toMutableList().also { it[index] = updated }
                .withSupersededPreviewsSettled(now)
            val revokeCompleted = updated.path == ApiRoute.PAIR_REVOKE_SELF.path && updated.phase == JournalPhase.SUCCEEDED
            val active = state.activeHost
            val revocationTargetsActive = active != null && active.hostId == target.host.hostId &&
                active.clientId == target.host.clientId && active.endpoint == target.host.endpoint &&
                active.certificatePin == target.host.certificatePin && active.pairedAtMillis == target.host.pairedAtMillis &&
                active.bearerToken == target.host.bearerToken
            state.copy(
                activeHost = if (revokeCompleted && revocationTargetsActive) null else active,
                journal = nextJournal,
            )
        }
        return when (postCommit) {
            is StateCommitResult.Saved -> {
                val stored = postCommit.state.journal.first { it.localGeneration == entry.localGeneration }
                JournalDispatchResult.Finished(stored, remoteOutcome)
            }
            is StateCommitResult.Unavailable, is StateCommitResult.Failed, is StateCommitResult.Rejected ->
                JournalDispatchResult.ResultNotConfirmed(entry, remoteOutcome, operationId)
        }
    }

    private data class PreparedTarget(
        val host: ActiveHost,
        val request: ApiRequest,
        val state: PersistentState,
    )

    private fun isOwnedPreviewRecovery(entry: JournalEntry, host: ActiveHost, request: ApiRequest): Boolean {
        if (!entry.belongsTo(host) || entry.path != ApiRoute.DISPLAY_PREVIEW.path || entry.operationId == null) return false
        return when (request.path) {
            ApiRoute.DISPLAY_CONFIRM.path ->
                (request.body?.fields?.get("preview_id") as? JsonValue.StringValue)?.value == entry.operationId
            ApiRoute.DISPLAY_RESTORE.path ->
                (request.body?.fields?.get("source") as? JsonValue.StringValue)?.value in setOf("preview", "verified")
            else -> false
        }
    }

    /**
     * Called before a recreated process exposes controls; empty recovery must not create state. It also
     * closes preview entries that a confirmed Keep or a completed revert already ended, which older
     * builds left unresolved.
     */
    fun recoverPreparedEntries(): JournalUpdateResult {
        when (val loaded = repository.load()) {
            StateLoadResult.Empty -> return JournalUpdateResult.Saved
            is StateLoadResult.Ready -> {
                val now = clock.wallTimeMillis().coerceAtLeast(0)
                if (loaded.state.journal.none { it.phase == JournalPhase.PREPARED } &&
                    loaded.state.journal.withSupersededPreviewsSettled(now) == loaded.state.journal
                ) return JournalUpdateResult.Saved
            }
            is StateLoadResult.Unavailable -> return JournalUpdateResult.PersistenceUnavailable(loaded.reason)
            is StateLoadResult.Failed -> return JournalUpdateResult.PersistenceFailed(loaded.reason)
        }
        return update { state ->
            val now = clock.wallTimeMillis().coerceAtLeast(0)
            state.copy(journal = state.journal.map { entry ->
                if (entry.phase == JournalPhase.PREPARED) entry.copy(
                    phase = JournalPhase.UNKNOWN,
                    updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
                    resultSummary = "Process ended before a result was saved; the earlier action may have run.",
                ) else entry
            }.withSupersededPreviewsSettled(now))
        }
    }

    fun applyOperationSnapshot(
        expectedHost: ActiveHost,
        localGeneration: Long,
        responseHostId: String,
        responseClientId: String,
        responseEndpoint: String,
        responseCertificatePin: String,
        operationId: String,
        operationRequestId: String,
        operationKind: String,
        operationState: WireValue<OperationState>,
        outcome: String?,
        nowMillis: Long = clock.wallTimeMillis(),
        /** The host reports it restored and verified the original mode for this preview (timeout or revert). */
        hostRestored: Boolean = false,
    ): JournalUpdateResult = update { state ->
        if (!samePairing(state.activeHost, expectedHost)) {
            throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Selected pairing changed during operation lookup")
        }
        val index = state.journal.indexOfFirst { it.localGeneration == localGeneration }
        if (index < 0) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Journal entry no longer exists")
        val old = state.journal[index]
        if (!old.belongsTo(expectedHost) || old.hostId != responseHostId || old.clientId != responseClientId || old.originalEndpoint != responseEndpoint ||
            old.originalCertificatePin != responseCertificatePin || old.operationId != operationId ||
            old.requestId != operationRequestId || old.expectedOperationKind() != operationKind
        ) throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "Operation reply does not match the journal entry")
        // A preview that ran out of time is restored by the host, which leaves the operation open but
        // reports the verified restore. Nothing is left to keep, so the entry is finished.
        val timedOutAndRestored = hostRestored && old.path == ApiRoute.DISPLAY_PREVIEW.path && old.phase.unresolved &&
            (operationState as? WireValue.Known)?.value?.let { it != OperationState.SUCCEEDED && it != OperationState.FAILED } == true
        val phase = if (timedOutAndRestored) JournalPhase.SUCCEEDED else phaseFor(operationState)
        if (!canTransition(old.phase, phase)) throw StateInvariantException(StateInvariantCode.INVALID_JOURNAL, "Operation state moved backwards")
        val summary = if (timedOutAndRestored) "succeeded: preview timed out; host restored the original mode"
            else summary(operationState, outcome)
        val updated = old.copy(
            phase = phase,
            updatedAtMillis = nowMillis.coerceAtLeast(old.updatedAtMillis),
            resultSummary = summary,
            rejectionCode = operationFailureCode(phase, outcome),
        ).validated()
        state.copy(journal = state.journal.toMutableList().also { it[index] = updated }
            .withSupersededPreviewsSettled(nowMillis))
    }

    /** A 404/not-found lookup is unresolved and never authorizes replay. */
    fun markOperationNotFound(
        expectedHost: ActiveHost,
        localGeneration: Long,
        responseHostId: String,
        responseClientId: String,
        responseEndpoint: String,
        responseCertificatePin: String,
        operationId: String,
        operationRequestId: String,
        nowMillis: Long = clock.wallTimeMillis(),
    ): JournalUpdateResult = update { state ->
        if (!samePairing(state.activeHost, expectedHost)) {
            throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Selected pairing changed during operation lookup")
        }
        val index = state.journal.indexOfFirst { it.localGeneration == localGeneration }
        if (index < 0) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Journal entry no longer exists")
        val old = state.journal[index]
        if (!old.belongsTo(expectedHost) || old.hostId != responseHostId || old.clientId != responseClientId || old.originalEndpoint != responseEndpoint ||
            old.originalCertificatePin != responseCertificatePin || old.operationId != operationId || old.requestId != operationRequestId
        ) throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "Not-found reply does not match the journal entry")
        if (!old.phase.unresolved) throw StateInvariantException(StateInvariantCode.INVALID_JOURNAL, "Terminal operation cannot become unresolved")
        val updated = old.copy(
            phase = JournalPhase.UNKNOWN,
            updatedAtMillis = nowMillis.coerceAtLeast(old.updatedAtMillis),
            resultSummary = "The host no longer has this operation record; the earlier action may have run.",
        )
        state.copy(journal = state.journal.toMutableList().also { it[index] = updated })
    }

    fun acknowledge(localGeneration: Long): JournalUpdateResult = update { state ->
        val index = state.journal.indexOfFirst { it.localGeneration == localGeneration }
        if (index < 0) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Journal entry no longer exists")
        val old = state.journal[index]
        state.copy(journal = state.journal.toMutableList().also { it[index] = old.copy(acknowledged = true) })
    }

    /** Explicit local archival: terminal records or unresolved records the user acknowledged. */
    fun archive(localGeneration: Long): JournalUpdateResult = update { state ->
        val entry = state.journal.firstOrNull { it.localGeneration == localGeneration }
            ?: throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Journal entry no longer exists")
        if (entry.phase.unresolved && !entry.acknowledged) throw StateInvariantException(StateInvariantCode.INVALID_JOURNAL, "Unresolved operation needs acknowledgement")
        state.copy(journal = state.journal.filterNot { it.localGeneration == localGeneration })
    }

    private fun update(transform: (PersistentState) -> PersistentState): JournalUpdateResult = when (val result = repository.transact(transform)) {
        is StateCommitResult.Saved -> JournalUpdateResult.Saved
        is StateCommitResult.Rejected -> JournalUpdateResult.Rejected(result.code)
        is StateCommitResult.Unavailable -> JournalUpdateResult.PersistenceUnavailable(result.reason)
        is StateCommitResult.Failed -> JournalUpdateResult.PersistenceFailed(result.reason)
    }

    private fun pruneExpiredTerminalEntries(entries: List<JournalEntry>, now: Long): List<JournalEntry> = entries.filterNot {
        !it.phase.unresolved && now >= it.updatedAtMillis && now - it.updatedAtMillis >= TERMINAL_RETENTION_MILLIS
    }

    private fun makeRoom(proposedState: PersistentState): List<JournalEntry> {
        val room = proposedState.journal.toMutableList()
        while (true) {
            val candidate = proposedState.copy(journal = room)
            val fitsLimits = try {
                PersistentStateCodec.encode(candidate)
                room.size <= PersistentStateLimits.MAX_JOURNAL_ENTRIES
            } catch (error: StateInvariantException) {
                if (error.code != StateInvariantCode.STATE_TOO_LARGE && error.code != StateInvariantCode.HISTORY_FULL) throw error
                false
            }
            if (fitsLimits) return room
            val removableIndex = room.indices
                .filter { index -> !room[index].phase.unresolved || room[index].acknowledged }
                .minByOrNull { room[it].updatedAtMillis }
                ?: throw StateInvariantException(StateInvariantCode.HISTORY_FULL, "Operation history is full; review unresolved actions")
            room.removeAt(removableIndex)
        }
    }

    private fun applyOutcome(entry: JournalEntry, outcome: RequestOutcome, now: Long): JournalEntry = when (outcome) {
        is RequestOutcome.NotSent -> entry.copy(
            phase = JournalPhase.FAILED,
            updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
            resultSummary = "Not sent: ${outcome.failure.name.lowercase()}",
        )
        is RequestOutcome.Rejected -> entry.copy(
            phase = JournalPhase.FAILED,
            updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
            resultSummary = "Rejected HTTP ${outcome.response.status}: ${outcome.response.error}",
            rejectionCode = rejectionCode(entry, outcome.response),
        )
        is RequestOutcome.Unknown -> entry.copy(
            phase = JournalPhase.UNKNOWN,
            updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
            resultSummary = "No validated result (${outcome.failure.name.lowercase()}); the earlier action may have run.",
        )
        is RequestOutcome.PairingUnresolved -> entry.copy(
            phase = JournalPhase.UNKNOWN,
            updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
            resultSummary = "Mutation outcome was unresolved (${outcome.failure.name.lowercase()}).",
        )
        is RequestOutcome.Reply -> {
            val versioned = outcome.response as? ApiReply.Versioned
            val payload = versioned?.payload as? VersionedPayload.Mutation
            val operation = payload?.operation
                ?: return entry.copy(
                    phase = JournalPhase.UNKNOWN,
                    updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
                    resultSummary = "No operation record was returned; the earlier action may have run.",
                )
            if (operation.requestId != entry.requestId || operation.kind != entry.expectedOperationKind()) return entry.copy(
                phase = JournalPhase.UNKNOWN,
                updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
                resultSummary = "Operation identity did not match the dispatched request; the earlier action may have run.",
            )
            val phase = phaseFor(operation.state)
            entry.copy(
                phase = phase,
                operationId = operation.id,
                updatedAtMillis = now.coerceAtLeast(entry.updatedAtMillis),
                resultSummary = summary(operation.state, operation.outcome),
                rejectionCode = operationFailureCode(phase, operation.outcome),
            )
        }
    }.validated()

    private fun rejectionCode(entry: JournalEntry, reply: ApiReply.Rejected): JournalRejectionCode = when {
        entry.path == ApiRoute.DISPLAY_SAVE_CURRENT.path && reply.status == 404 ->
            JournalRejectionCode.SAVE_CURRENT_UNSUPPORTED
        reply.error == "profile_not_found" -> JournalRejectionCode.PROFILE_NOT_FOUND
        reply.error == "stale_target" || reply.error == "stale_display_target" ||
            reply.error == "stale_display_inventory" -> JournalRejectionCode.STALE_TARGET
        reply.error == "ambiguous_display_identity" -> JournalRejectionCode.AMBIGUOUS_DISPLAY
        reply.error == "gamescope_output_unsupported" -> JournalRejectionCode.ORDER_UNSUPPORTED
        reply.error == "gamescope_output_update_failed" -> JournalRejectionCode.ORDER_UPDATE_FAILED
        reply.error == "mutation_conflict" -> JournalRejectionCode.MUTATION_CONFLICT
        reply.error == "bridge_unavailable" -> JournalRejectionCode.BRIDGE_UNAVAILABLE
        reply.error == "readback_not_confirmed" -> JournalRejectionCode.READBACK_NOT_CONFIRMED
        reply.error == "preview_not_found" -> JournalRejectionCode.PREVIEW_NOT_FOUND
        reply.error == "preview_expired" -> JournalRejectionCode.PREVIEW_EXPIRED
        reply.error == "forbidden" -> JournalRejectionCode.FORBIDDEN
        else -> JournalRejectionCode.OTHER_REJECTION
    }

    private fun operationFailureCode(phase: JournalPhase, outcome: String?): JournalRejectionCode? =
        if (phase != JournalPhase.FAILED) null
        else if (outcome == "readback_not_confirmed") JournalRejectionCode.READBACK_NOT_CONFIRMED
        else if (outcome == "gamescope_output_configured_restart_failed") JournalRejectionCode.ORDER_SAVED_RESTART_FAILED
        else JournalRejectionCode.OPERATION_FAILED

    private fun phaseFor(state: WireValue<OperationState>): JournalPhase = when (state) {
        is WireValue.Unknown -> JournalPhase.UNKNOWN
        is WireValue.Known -> when (state.value) {
            OperationState.ACCEPTED -> JournalPhase.ACCEPTED
            OperationState.DISPATCHED -> JournalPhase.DISPATCHED
            OperationState.OBSERVED_RETURN -> JournalPhase.OBSERVED_RETURN
            OperationState.SUCCEEDED -> JournalPhase.SUCCEEDED
            OperationState.FAILED -> JournalPhase.FAILED
            OperationState.UNKNOWN -> JournalPhase.UNKNOWN
        }
    }

    private fun summary(state: WireValue<OperationState>, outcome: String?): String {
        val label = when (state) {
            is WireValue.Unknown -> "unknown:${state.raw.take(64)}"
            is WireValue.Known -> state.value.name.lowercase()
        }
        return listOfNotNull(label, outcome?.take(128)).joinToString(": ").take(1024)
    }

    private fun canTransition(from: JournalPhase, to: JournalPhase): Boolean = when (from) {
        JournalPhase.PREPARED -> to in setOf(JournalPhase.FAILED, JournalPhase.UNKNOWN, JournalPhase.ACCEPTED, JournalPhase.DISPATCHED, JournalPhase.OBSERVED_RETURN, JournalPhase.SUCCEEDED)
        JournalPhase.ACCEPTED -> to in setOf(JournalPhase.ACCEPTED, JournalPhase.DISPATCHED, JournalPhase.OBSERVED_RETURN, JournalPhase.SUCCEEDED, JournalPhase.FAILED, JournalPhase.UNKNOWN)
        JournalPhase.DISPATCHED -> to in setOf(JournalPhase.DISPATCHED, JournalPhase.OBSERVED_RETURN, JournalPhase.SUCCEEDED, JournalPhase.FAILED, JournalPhase.UNKNOWN)
        JournalPhase.OBSERVED_RETURN -> to in setOf(JournalPhase.OBSERVED_RETURN, JournalPhase.SUCCEEDED, JournalPhase.FAILED, JournalPhase.UNKNOWN)
        JournalPhase.UNKNOWN -> true // a retained operation ID can become visible again; no request is resent.
        JournalPhase.SUCCEEDED, JournalPhase.FAILED -> from == to
    }

    private fun nextLocalGeneration(state: PersistentState): Long {
        val last = maxOf(state.revision, state.journal.maxOfOrNull { it.localGeneration } ?: 0L)
        if (last == Long.MAX_VALUE) throw StateInvariantException(StateInvariantCode.INVALID_FIELD, "Journal generation exhausted")
        return last + 1
    }

    private fun ApiRequest.requestId(): String = body?.fields?.get("request_id")?.let {
        (it as? io.github.tuthan.steamoscompanion.core.protocol.JsonValue.StringValue)?.value
    } ?: throw StateInvariantException(StateInvariantCode.INVALID_JOURNAL, "Mutation request ID is missing")

    private fun samePairing(active: ActiveHost?, expected: ActiveHost): Boolean = active != null &&
        active.hostId == expected.hostId && active.clientId == expected.clientId &&
        active.endpoint == expected.endpoint && active.certificatePin == expected.certificatePin &&
        active.pairedAtMillis == expected.pairedAtMillis && active.bearerToken == expected.bearerToken

    private companion object {
        const val TERMINAL_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        val JOURNALED_ROUTES = setOf(
            ApiRoute.PAIR_REVOKE_SELF,
            ApiRoute.POWER,
            ApiRoute.DISPLAY_ORDER_SET,
            ApiRoute.DISPLAY_ORDER_AUTOMATIC,
            ApiRoute.DISPLAY_PREVIEW,
            ApiRoute.DISPLAY_CONFIRM,
            ApiRoute.DISPLAY_RESTORE,
            ApiRoute.DISPLAY_SAVE_CURRENT,
            ApiRoute.SUNSHINE_RESTART,
        )
    }
}

/**
 * The host ends its preview when a Keep is confirmed or a preview revert completes, but it leaves the
 * original preview operation open. Without this, that entry stays unresolved forever, keeps showing
 * "Result not confirmed", and blocks every other display action. Only the host's own terminal success
 * for the same credential, recorded after the preview, closes it.
 */
internal fun List<JournalEntry>.withSupersededPreviewsSettled(nowMillis: Long): List<JournalEntry> {
    val closers = filter { it.phase == JournalPhase.SUCCEEDED && it.closesPreview() }
    if (closers.isEmpty()) return this
    return map { entry ->
        if (entry.path != ApiRoute.DISPLAY_PREVIEW.path || !entry.phase.unresolved || entry.operationId == null) entry
        else {
            val closer = closers.firstOrNull { it.localGeneration > entry.localGeneration && it.sameCredential(entry) &&
                (it.path == ApiRoute.DISPLAY_RESTORE.path || it.confirmedPreviewId() == entry.operationId) }
            if (closer == null) entry else entry.copy(
                phase = JournalPhase.SUCCEEDED,
                updatedAtMillis = nowMillis.coerceAtLeast(entry.updatedAtMillis),
                resultSummary = if (closer.path == ApiRoute.DISPLAY_CONFIRM.path) "succeeded: preview kept" else "succeeded: preview reverted",
            )
        }
    }
}

private fun JournalEntry.closesPreview(): Boolean = when (path) {
    ApiRoute.DISPLAY_CONFIRM.path -> true
    ApiRoute.DISPLAY_RESTORE.path -> bodyString("source") == "preview"
    else -> false
}

internal fun JournalEntry.confirmedPreviewId(): String? = bodyString("preview_id")

private fun JournalEntry.bodyString(field: String): String? = try {
    (BoundedJson.parseObject(bodyBytes())[field] as? JsonValue.StringValue)?.value
} catch (_: Exception) { null }

private fun JournalEntry.sameCredential(other: JournalEntry): Boolean =
    hostId == other.hostId && clientId == other.clientId && originalEndpoint == other.originalEndpoint &&
        originalCertificatePin == other.originalCertificatePin && originalPairedAtMillis == other.originalPairedAtMillis &&
        originalBearerSha256 != null && originalBearerSha256 == other.originalBearerSha256
