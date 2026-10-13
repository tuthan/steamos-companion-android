package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 04: an unreadable state is never presented as "not paired". Only a state that can never be opened again offers
 * Reset local pairing; a transient key problem or an unknown newer schema gets Retry and is never erased.
 */
class SavedPairingProblemTest {
    private fun problem(load: StateLoadResult) = SavedPairingProblem.from(load)

    @Test fun anEmptyOrReadyStateIsNotAProblem() {
        assertNull(problem(StateLoadResult.Empty))
        assertNull(problem(StateLoadResult.Ready(PersistentState())))
    }

    @Test fun damagedMissingAndInvalidatedKeysOfferResetOnly() {
        for (reason in listOf(StateUnavailableReason.DAMAGED, StateUnavailableReason.KEY_MISSING,
            StateUnavailableReason.KEY_INVALIDATED)) {
            val result = problem(StateLoadResult.Unavailable(reason))!!
            assertEquals(SavedPairingProblemKind.RESET_REQUIRED, result.kind)
            assertTrue("$reason offers a reset", result.canReset)
            assertEquals("$reason has nothing to retry", false, result.canRetry)
        }
    }

    @Test fun aTemporarilyUnavailableKeyAndAFailedReadOfferRetryAndNeverReset() {
        val temporary = problem(StateLoadResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE))!!
        assertEquals(SavedPairingProblemKind.RETRY, temporary.kind)
        assertTrue(temporary.canRetry)
        assertEquals(false, temporary.canReset)
        for (failure in StatePersistenceFailure.entries) {
            val failed = problem(StateLoadResult.Failed(failure))!!
            assertEquals(SavedPairingProblemKind.RETRY, failed.kind)
            assertEquals("$failure must not erase state", false, failed.canReset)
            assertTrue(failed.canRetry)
        }
    }

    @Test fun anUnknownNewerSchemaIsAVersionMismatchThatIsNeverOverwritten() {
        val result = problem(StateLoadResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED))!!
        assertEquals(SavedPairingProblemKind.APP_UPDATE_REQUIRED, result.kind)
        assertEquals(false, result.canReset)
        assertTrue(result.canRetry)
    }

    @Test fun everyRealRepositoryOutcomeMapsToTheDocumentedWayOut() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        assertNull("nothing paired yet is the ordinary unpaired flow", problem(repository.load()))
        repository.transact { it.copy(activeHost = testActiveHost()) }
        assertNull(problem(repository.load()))
        val original = store.rawBytes()!!

        store.replaceRaw(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertEquals(SavedPairingProblemKind.RESET_REQUIRED, problem(repository.load())!!.kind)

        store.replaceRaw(original)
        cipher.temporarilyUnavailable = true
        assertEquals(SavedPairingProblemKind.RETRY, problem(repository.load())!!.kind)
        cipher.temporarilyUnavailable = false

        cipher.invalidated = true
        assertEquals(SavedPairingProblemKind.RESET_REQUIRED, problem(repository.load())!!.kind)
        cipher.invalidated = false

        val newer = jsonObject("schema_version" to JsonValue.NumberValue("99"), "future" to JsonValue.StringValue("keep"))
        FakePreparedEnvelope(cipher, store, BoundedJson.encode(newer))
        val newerBytes = store.rawBytes()!!
        assertEquals(SavedPairingProblemKind.APP_UPDATE_REQUIRED, problem(repository.load())!!.kind)
        assertTrue("an unknown newer schema is left byte for byte", newerBytes.contentEquals(store.rawBytes()!!))
    }

    @Test fun resettingAnUnreadableStateReturnsToTheEmptyUnpairedFlow() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        repository.transact { it.copy(activeHost = testActiveHost()) }
        cipher.invalidated = true
        assertEquals(SavedPairingProblemKind.RESET_REQUIRED, problem(repository.load())!!.kind)

        assertEquals(StateResetResult.Cleared, PairingStateTransactions(repository).resetLocalPairing())
        cipher.invalidated = false
        assertEquals(StateLoadResult.Empty, repository.load())
        assertNull(problem(repository.load()))
    }
}
