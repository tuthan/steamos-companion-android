package io.github.tuthan.steamoscompanion.core.client

/** Generation-checked candidate updates and atomic host replacement/Forget transactions. */
class PairingStateTransactions(private val repository: EncryptedStateRepository) {
    fun beginCandidate(candidate: PairingCandidate): StateCommitResult = repository.transact { state ->
        if (state.candidate?.phase == CandidatePhase.APPROVED_STAGED) {
            throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Approved credentials must be self-revoked before replacement")
        }
        if (state.pairingGeneration == Long.MAX_VALUE) {
            throw StateInvariantException(StateInvariantCode.INVALID_FIELD, "Pairing generation exhausted")
        }
        val nextGeneration = state.pairingGeneration + 1
        val fresh = candidate.copy(generation = nextGeneration, phase = CandidatePhase.READY, pairingId = null, pairingSession = null, stagedCredential = null)
        fresh.validated(nextGeneration)
        state.copy(pairingGeneration = nextGeneration, candidate = fresh)
    }

    /** Stale network callbacks fail before they can overwrite a replacement or cancelled candidate. */
    fun updateCandidate(
        expectedGeneration: Long,
        transform: (PairingCandidate) -> PairingCandidate,
    ): StateCommitResult = repository.transact { state ->
        val current = state.candidate
        if (current == null || state.pairingGeneration != expectedGeneration || current.generation != expectedGeneration) {
            throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Pairing callback is stale")
        }
        val updated = transform(current)
        if (updated.generation != expectedGeneration) throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Pairing callback changed its generation")
        if (current.phase == CandidatePhase.APPROVED_STAGED && updated != current) {
            throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Approved credentials must be self-revoked before they are changed")
        }
        updated.validated(expectedGeneration)
        state.copy(candidate = updated)
    }

    fun cancelCandidate(expectedGeneration: Long): StateCommitResult = repository.transact { state ->
        val current = state.candidate
        if (current == null || current.generation != expectedGeneration || state.pairingGeneration != expectedGeneration) {
            throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Pairing candidate is no longer current")
        }
        if (current.phase == CandidatePhase.APPROVED_STAGED) {
            throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Approved credentials must be self-revoked before discard")
        }
        state.copy(pairingGeneration = increment(state.pairingGeneration), candidate = null)
    }

    /** The old credential remains active unless the approved candidate and its credential commit together. */
    fun acceptApprovedCandidate(expectedGeneration: Long): StateCommitResult = repository.transact { state ->
        val candidate = state.candidate
        if (candidate == null || state.pairingGeneration != expectedGeneration || candidate.generation != expectedGeneration) {
            throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Approved candidate is no longer current")
        }
        if (candidate.phase != CandidatePhase.APPROVED_STAGED || candidate.hostId == null || candidate.stagedCredential == null) {
            throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Candidate has no staged approved credential")
        }
        val staged = candidate.stagedCredential
        staged.validated(candidate.clientId, candidate.hostId)
        val active = ActiveHost(
            hostId = staged.hostId,
            clientId = staged.clientId,
            endpoint = candidate.endpoint,
            certificatePin = candidate.certificatePin,
            bearerToken = staged.bearerToken,
            grantedScopes = staged.grantedScopes,
            wakeTarget = staged.wakeTarget,
            pairedAtMillis = staged.pairedAtMillis,
        ).validated()
        state.copy(pairingGeneration = increment(state.pairingGeneration), activeHost = active, candidate = null)
    }

    /** Explicit Forget clears only the selected host's journal context and invalidates callbacks. */
    fun forgetActiveHost(expectedHostId: String): StateCommitResult = repository.transact { state ->
        val active = state.activeHost
        if (active == null || active.hostId != expectedHostId) {
            throw StateInvariantException(StateInvariantCode.STALE_GENERATION, "Selected host changed")
        }
        if (state.candidate?.phase == CandidatePhase.APPROVED_STAGED) {
            throw StateInvariantException(StateInvariantCode.INVALID_CANDIDATE, "Approved credentials must be self-revoked before Forget")
        }
        state.copy(
            pairingGeneration = increment(state.pairingGeneration),
            activeHost = null,
            candidate = null,
            journal = state.journal.filterNot { it.hostId == active.hostId && it.clientId == active.clientId },
        )
    }

    /**
     * Damaged/missing keys require explicit user reset; success means both file and key were
     * removed. This is local-only and callers must explain that a remote pairing may remain.
     */
    fun resetLocalPairing(): StateResetResult = repository.reset()

    private fun increment(value: Long): Long {
        if (value == Long.MAX_VALUE) throw StateInvariantException(StateInvariantCode.INVALID_FIELD, "Pairing generation exhausted")
        return value + 1
    }
}
