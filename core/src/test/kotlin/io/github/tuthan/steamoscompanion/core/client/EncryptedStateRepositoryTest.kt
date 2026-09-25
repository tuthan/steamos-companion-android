package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedStateRepositoryTest {
    @Test
    fun commitsAndReopensOnlyCiphertextAndKeepsOldStateAfterWriteFailure() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        assertEquals(StateLoadResult.Empty, repository.load())

        val first = repository.transact { it.copy(activeHost = testActiveHost()) } as StateCommitResult.Saved
        val firstBytes = store.rawBytes()!!
        assertTrue(!firstBytes.toString(Charsets.ISO_8859_1).contains(testActiveHost().bearerToken))
        assertEquals(first.state, (repository.load() as StateLoadResult.Ready).state)

        store.failNextWrite = true
        val failure = repository.transact { it.copy(revision = 500) }
        assertEquals(StateCommitResult.Failed(StatePersistenceFailure.IO), failure)
        assertArrayEquals(firstBytes, store.rawBytes())
        assertEquals(first.state, (repository.load() as StateLoadResult.Ready).state)
    }

    @Test
    fun corruptMissingInvalidatedAndTemporarilyUnavailableKeysNeverTriggerReset() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        repository.transact { it.copy(activeHost = testActiveHost()) }
        val original = store.rawBytes()!!
        val encryptCount = cipher.encryptCount

        store.replaceRaw(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() })
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED), repository.load())
        assertEquals(original.size, store.rawBytes()!!.size)
        assertEquals(encryptCount, cipher.encryptCount)

        store.replaceRaw(original)
        cipher.makeKeyMissing()
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.KEY_MISSING), repository.load())
        assertEquals(encryptCount, cipher.encryptCount)

        cipher.invalidated = true
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.KEY_INVALIDATED), repository.load())
        cipher.invalidated = false
        cipher.temporarilyUnavailable = true
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.TEMPORARY_KEY_UNAVAILABLE), repository.load())
        cipher.temporarilyUnavailable = false
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.KEY_MISSING), repository.load())
    }

    @Test
    fun adapterReportedOversizedEnvelopeIsDamagedAndIsNeverDeletedAutomatically() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        repository.transact { it.copy(activeHost = testActiveHost()) }
        val original = store.rawBytes()!!

        store.invalidData = true
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED), repository.load())
        store.invalidData = false
        assertArrayEquals(original, store.rawBytes())
        assertTrue(repository.load() is StateLoadResult.Ready)
    }

    @Test
    fun migratesOnlyAfterValidationAndNeverOverwritesAnUnknownNewerSchema() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        val legacy = jsonObject(
            "schema_version" to JsonValue.NumberValue("0"),
            "revision" to JsonValue.NumberValue("9"),
            "active_host" to JsonValue.NullValue,
            "candidate" to JsonValue.NullValue,
            "journal" to JsonValue.ArrayValue(emptyList()),
        )
        FakePreparedEnvelope(cipher, store, io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(legacy))
        val oldBytes = store.rawBytes()!!
        val migrated = repository.load() as StateLoadResult.Ready
        assertEquals(0L, migrated.migratedFromSchema)
        assertEquals(9L, migrated.state.revision)
        assertNotEquals(oldBytes.toList(), store.rawBytes()!!.toList())

        val newer = jsonObject("schema_version" to JsonValue.NumberValue("3"), "future" to JsonValue.StringValue("retain"))
        FakePreparedEnvelope(cipher, store, io.github.tuthan.steamoscompanion.core.protocol.BoundedJson.encode(newer))
        val newerBytes = store.rawBytes()!!
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED), repository.load())
        assertEquals(
            StateCommitResult.Unavailable(StateUnavailableReason.APP_UPDATE_REQUIRED),
            repository.transact { it.copy(activeHost = testActiveHost()) },
        )
        assertArrayEquals(newerBytes, store.rawBytes())
    }

    @Test
    fun resetRequiresBothFileAndKeyRemovalAndCanBeRetried() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        repository.transact { it.copy(activeHost = testActiveHost()) }

        cipher.deleteFails = true
        assertEquals(StateResetResult.Failed(StatePersistenceFailure.RESET_INCOMPLETE), repository.reset())
        assertNull(store.rawBytes())
        cipher.deleteFails = false
        assertEquals(StateResetResult.Cleared, repository.reset())
        assertEquals(StateLoadResult.Empty, repository.load())

        repository.transact { it.copy(activeHost = testActiveHost()) }
        store.failClear = true
        assertEquals(StateResetResult.Failed(StatePersistenceFailure.IO), repository.reset())
        assertTrue(store.rawBytes() != null)
    }

    @Test
    fun allRepositoryInstancesShareOneProcessTransactionLock() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val first = EncryptedStateRepository(store, cipher)
        val second = EncryptedStateRepository(store, cipher)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 40).map { index ->
                executor.submit {
                    val repository = if (index % 2 == 0) first else second
                    val result = repository.transact { it.copy(pairingGeneration = it.pairingGeneration + 1) }
                    assertTrue("transaction $index failed: $result", result is StateCommitResult.Saved)
                }
            }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        val final = (first.load() as StateLoadResult.Ready).state
        assertEquals(40L, final.pairingGeneration)
        assertEquals(40L, final.revision)
    }
}
