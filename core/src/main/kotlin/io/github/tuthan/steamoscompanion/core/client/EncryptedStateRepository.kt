package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.StateCipher
import io.github.tuthan.steamoscompanion.core.ports.StateKeyException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyInvalidatedException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyMissingException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyTemporarilyUnavailableException
import io.github.tuthan.steamoscompanion.core.ports.StateAuthenticationException
import io.github.tuthan.steamoscompanion.core.ports.StateStore
import io.github.tuthan.steamoscompanion.core.ports.StateStoreInvalidDataException
import io.github.tuthan.steamoscompanion.core.protocol.JsonFormatException
import io.github.tuthan.steamoscompanion.core.protocol.DisplayRecoveryProfile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class StateUnavailableReason { DAMAGED, KEY_MISSING, KEY_INVALIDATED, TEMPORARY_KEY_UNAVAILABLE, APP_UPDATE_REQUIRED }
enum class StatePersistenceFailure { IO, CRYPTO, RESET_INCOMPLETE }

sealed interface StateLoadResult {
    data object Empty : StateLoadResult
    data class Ready(val state: PersistentState, val migratedFromSchema: Long? = null) : StateLoadResult
    data class Unavailable(val reason: StateUnavailableReason) : StateLoadResult
    data class Failed(val reason: StatePersistenceFailure) : StateLoadResult
}

sealed interface StateCommitResult {
    data class Saved(val state: PersistentState) : StateCommitResult
    data class Unavailable(val reason: StateUnavailableReason) : StateCommitResult
    data class Failed(val reason: StatePersistenceFailure) : StateCommitResult
    data class Rejected(val code: StateInvariantCode) : StateCommitResult
}

sealed interface StateResetResult {
    data object Cleared : StateResetResult
    data class Failed(val reason: StatePersistenceFailure) : StateResetResult
}

/**
 * Serializes every read/modify/write operation in this process. StateStore.AtomicFile alone is
 * not a lock; all instances share this lock so journal commits cannot overwrite one another.
 */
class EncryptedStateRepository(
    private val store: StateStore,
    private val cipher: StateCipher,
) {
    init {
        require(cipher.keyAlias == PersistentStateLimits.STATE_KEY_ALIAS) { "State key alias must be app-owned" }
    }

    fun load(): StateLoadResult = PROCESS_LOCK.withLock { loadLocked() }

    /** Persist the boot identity from a status response only if the same paired host is still active. */
    fun recordObservedBootId(expectedHost: ActiveHost, bootId: String): StateCommitResult {
        val updatedHost = expectedHost.copy(lastObservedBootId = bootId).validated()
        return transact { state ->
            val active = state.activeHost
                ?: throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "No paired host is active")
            val identityStillMatches = active.hostId == expectedHost.hostId &&
                active.clientId == expectedHost.clientId &&
                active.endpoint == expectedHost.endpoint &&
                active.certificatePin == expectedHost.certificatePin &&
                active.pairedAtMillis == expectedHost.pairedAtMillis && active.bearerToken == expectedHost.bearerToken
            if (!identityStillMatches) {
                throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "Status belongs to a replaced host identity")
            }
            state.copy(activeHost = active.copy(lastObservedBootId = updatedHost.lastObservedBootId))
        }
    }

    /** Cache only a host-read exact recovery target for the same active credential. */
    fun recordRecoveryProfiles(expectedHost: ActiveHost, profiles: List<DisplayRecoveryProfile>): StateCommitResult {
        // The host keeps all verified profiles, while encrypted offline state is bounded.
        // New profiles are appended to the host inventory, so retain its latest entries.
        val snapshot = profiles.takeLast(PersistentStateLimits.MAX_CACHED_RECOVERY_PROFILES)
        try { expectedHost.copy(cachedRecoveryProfiles = snapshot).validated() }
        catch (_: IllegalArgumentException) { return StateCommitResult.Rejected(StateInvariantCode.INVALID_FIELD) }
        val loaded = load()
        if (loaded is StateLoadResult.Ready && sameActiveCredential(loaded.state.activeHost, expectedHost) &&
            loaded.state.activeHost?.cachedRecoveryProfiles == snapshot
        ) return StateCommitResult.Saved(loaded.state)
        return transact { state ->
            val active = state.activeHost
            if (!sameActiveCredential(active, expectedHost)) {
                throw StateInvariantException(StateInvariantCode.INVALID_IDENTITY, "Recovery inventory belongs to a replaced host identity")
            }
            state.copy(activeHost = active!!.copy(cachedRecoveryProfiles = snapshot))
        }
    }

    private fun sameActiveCredential(active: ActiveHost?, expected: ActiveHost): Boolean =
        active != null && active.hostId == expected.hostId && active.clientId == expected.clientId &&
            active.endpoint == expected.endpoint && active.certificatePin == expected.certificatePin &&
            active.pairedAtMillis == expected.pairedAtMillis && active.bearerToken == expected.bearerToken

    fun transact(transform: (PersistentState) -> PersistentState): StateCommitResult = PROCESS_LOCK.withLock {
        val current = when (val loaded = loadLocked()) {
            StateLoadResult.Empty -> PersistentState()
            is StateLoadResult.Ready -> loaded.state
            is StateLoadResult.Unavailable -> return StateCommitResult.Unavailable(loaded.reason)
            is StateLoadResult.Failed -> return StateCommitResult.Failed(loaded.reason)
        }
        if (current.revision == Long.MAX_VALUE) return StateCommitResult.Rejected(StateInvariantCode.INVALID_FIELD)
        val requested = try {
            transform(current)
        } catch (error: StateInvariantException) {
            return StateCommitResult.Rejected(error.code)
        } catch (_: IllegalArgumentException) {
            return StateCommitResult.Rejected(StateInvariantCode.INVALID_FIELD)
        }
        if (requested.pairingGeneration < current.pairingGeneration) {
            return StateCommitResult.Rejected(StateInvariantCode.STALE_GENERATION)
        }
        val next = try {
            requested.copy(schemaVersion = PersistentStateLimits.CURRENT_SCHEMA_VERSION, revision = current.revision + 1).validated()
        } catch (error: StateInvariantException) {
            return StateCommitResult.Rejected(error.code)
        }
        when (val written = writeLocked(next)) {
            WriteResult.Saved -> StateCommitResult.Saved(next)
            is WriteResult.Unavailable -> StateCommitResult.Unavailable(written.reason)
            is WriteResult.Failed -> StateCommitResult.Failed(written.reason)
        }
    }

    /** Removes the file first, then its app-owned Keystore key; reports success only when both do. */
    fun reset(): StateResetResult = PROCESS_LOCK.withLock {
        try {
            store.clear()
        } catch (_: Exception) {
            return StateResetResult.Failed(StatePersistenceFailure.IO)
        }
        try {
            cipher.deleteKey()
        } catch (_: Exception) {
            return StateResetResult.Failed(StatePersistenceFailure.RESET_INCOMPLETE)
        }
        StateResetResult.Cleared
    }

    private fun loadLocked(): StateLoadResult {
        val envelopeBytes = try {
            store.read()
        } catch (_: StateStoreInvalidDataException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        } catch (_: Exception) {
            return StateLoadResult.Failed(StatePersistenceFailure.IO)
        } ?: return StateLoadResult.Empty
        if (envelopeBytes.size > PersistentStateLimits.MAX_ENVELOPE_BYTES) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        }

        val envelope = try {
            StateEnvelopeCodec.decode(envelopeBytes, cipher.keyAlias)
        } catch (error: UnsupportedStateFormatException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED)
        } catch (_: Exception) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        }

        val plaintext = try {
            cipher.decrypt(envelope.iv, envelope.ciphertextAndTag, PersistentStateLimits.STATE_AAD.toByteArray(Charsets.UTF_8))
        } catch (_: StateKeyMissingException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.KEY_MISSING)
        } catch (_: StateKeyInvalidatedException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.KEY_INVALIDATED)
        } catch (_: StateKeyTemporarilyUnavailableException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE)
        } catch (_: StateAuthenticationException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        } catch (_: StateKeyException) {
            return StateLoadResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE)
        } catch (_: Exception) {
            return StateLoadResult.Failed(StatePersistenceFailure.CRYPTO)
        }
        if (plaintext.size > PersistentStateLimits.MAX_PLAINTEXT_BYTES) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        }

        val document = try {
            PersistentStateCodec.parseDocument(plaintext)
        } catch (_: Exception) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        }
        val schema = try {
            PersistentStateCodec.schemaVersion(document)
        } catch (_: Exception) {
            return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        }
        if (schema > PersistentStateLimits.CURRENT_SCHEMA_VERSION) {
            return StateLoadResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED)
        }
        if (schema < 0) return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)

        if (schema < PersistentStateLimits.CURRENT_SCHEMA_VERSION) {
            val migrated = try {
                PersistentStateCodec.migrateKnownDocument(document)
                    ?: return StateLoadResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED)
            } catch (_: Exception) {
                return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
            }
            val migratedState = try {
                PersistentStateCodec.decodeCurrent(migrated)
            } catch (_: Exception) {
                return StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
            }
            return when (val written = writeLocked(migratedState)) {
                WriteResult.Saved -> StateLoadResult.Ready(migratedState, migratedFromSchema = schema)
                is WriteResult.Unavailable -> StateLoadResult.Unavailable(written.reason)
                is WriteResult.Failed -> StateLoadResult.Failed(written.reason)
            }
        }

        return try {
            StateLoadResult.Ready(PersistentStateCodec.decodeCurrent(document))
        } catch (_: JsonFormatException) {
            StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        } catch (_: Exception) {
            StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED)
        }
    }

    private fun writeLocked(state: PersistentState): WriteResult {
        val plaintext = try {
            PersistentStateCodec.encode(state)
        } catch (_: Exception) {
            return WriteResult.Failed(StatePersistenceFailure.CRYPTO)
        }
        val encrypted = try {
            cipher.encrypt(plaintext, PersistentStateLimits.STATE_AAD.toByteArray(Charsets.UTF_8))
        } catch (_: StateKeyMissingException) {
            return WriteResult.Unavailable(StateUnavailableReason.KEY_MISSING)
        } catch (_: StateKeyInvalidatedException) {
            return WriteResult.Unavailable(StateUnavailableReason.KEY_INVALIDATED)
        } catch (_: StateKeyTemporarilyUnavailableException) {
            return WriteResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE)
        } catch (_: StateKeyException) {
            return WriteResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE)
        } catch (_: Exception) {
            return WriteResult.Failed(StatePersistenceFailure.CRYPTO)
        }
        val envelope = try {
            StateEnvelopeCodec.encode(cipher.keyAlias, encrypted.iv, encrypted.bytes)
        } catch (_: Exception) {
            return WriteResult.Failed(StatePersistenceFailure.CRYPTO)
        }
        return try {
            store.writeAtomically(envelope)
            WriteResult.Saved
        } catch (_: Exception) {
            WriteResult.Failed(StatePersistenceFailure.IO)
        }
    }

    private companion object {
        val PROCESS_LOCK = ReentrantLock()
    }

    private sealed interface WriteResult {
        data object Saved : WriteResult
        data class Unavailable(val reason: StateUnavailableReason) : WriteResult
        data class Failed(val reason: StatePersistenceFailure) : WriteResult
    }
}
