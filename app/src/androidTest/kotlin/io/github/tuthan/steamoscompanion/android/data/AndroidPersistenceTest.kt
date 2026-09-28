package io.github.tuthan.steamoscompanion.android.data

import android.os.Build
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.PersistentStateLimits
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateLoadResult
import io.github.tuthan.steamoscompanion.core.client.StateResetResult
import io.github.tuthan.steamoscompanion.core.client.StateUnavailableReason
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.ConnectionReplacement
import io.github.tuthan.steamoscompanion.core.client.ReplacementDiscardResult
import io.github.tuthan.steamoscompanion.core.client.DisplayRecoveryPresentation
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.OperationJournal
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingStateTransactions
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.client.PendingDisplayResult
import io.github.tuthan.steamoscompanion.core.client.PreviewPresentation
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StagedCredential
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayRecoveryProfile
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.RequestOutcome
import io.github.tuthan.steamoscompanion.core.transport.TransportFailure
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises AtomicFile recovery and the real AndroidKeyStore provider without network access. */
@RunWith(AndroidJUnit4::class)
class AndroidPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var baseFile: File
    private lateinit var store: AndroidAtomicStateStore
    private lateinit var cipher: AndroidKeystoreStateCipher
    private lateinit var repository: EncryptedStateRepository

    @Before
    fun prepare() {
        val productionStore = AndroidAtomicStateStore(context)
        assertEquals(File(context.filesDir, AndroidAtomicStateStore.STATE_FILE_NAME), productionStore.testBaseFile())

        val directory = File(context.cacheDir, "phase04-persistence").apply {
            deleteRecursively()
            mkdirs()
        }
        baseFile = File(directory, "companion-state.bin")
        store = AndroidAtomicStateStore(baseFile)
        cipher = AndroidKeystoreStateCipher()
        repository = EncryptedStateRepository(store, cipher)
        assertEquals(StateResetResult.Cleared, repository.reset())
    }

    @After
    fun cleanup() {
        repository.reset()
        baseFile.parentFile?.deleteRecursively()
    }

    @Test
    fun keystoreCipherRoundTripsWithFreshProviderIvAndDoesNotPersistPlaintext() {
        val host = activeHost()
        val first = repository.transact { it.copy(activeHost = host) } as StateCommitResult.Saved
        val firstBytes = baseFile.readBytes()
        val plaintext = io.github.tuthan.steamoscompanion.core.client.PersistentStateCodec.encode(first.state)
        assertFalse(firstBytes.toString(Charsets.ISO_8859_1).contains(host.bearerToken))
        assertFalse(firstBytes.toString(Charsets.ISO_8859_1).contains("schema_version"))
        assertEquals(first.state, (repository.load() as StateLoadResult.Ready).state)

        repository.transact { it.copy(pairingGeneration = it.pairingGeneration + 1) }
        val secondBytes = baseFile.readBytes()
        assertNotEquals(iv(firstBytes).toList(), iv(secondBytes).toList())
        assertTrue(plaintext.size <= PersistentStateLimits.MAX_PLAINTEXT_BYTES)

        val hardware = cipher.hardwareBacked()
        Log.i("Phase04Storage", "Android Keystore AES key hardware backed=$hardware api=${Build.VERSION.SDK_INT} aes=${KeyProperties.KEY_ALGORITHM_AES}")
    }

    @Test
    fun reopeningAndroidStorePreservesRecoveryProfileAndPendingPreviewCard() {
        val host = activeHost().copy(grantedScopes = listOf("status.read", "power.control", "display.control"))
        val profile = DisplayRecoveryProfile("profile-persistence", "1",
            DisplayModeSummary("mode-current", 1920, 1080, "60"))
        val request = ApiRequest(HttpMethod.POST, ApiRoute.DISPLAY_PREVIEW.path,
            jsonObject(
                "request_id" to JsonValue.StringValue("preview-persistence"),
                "output_id" to JsonValue.StringValue("1"),
                "mode_id" to JsonValue.StringValue("mode-target"),
                "generation" to JsonValue.NumberValue("4"),
            ), host.bearerToken)
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 1_790_000_000_100)
            .copy(phase = JournalPhase.ACCEPTED, operationId = "op-persistence")
        assertTrue(repository.transact { it.copy(activeHost = host, journal = listOf(entry)) } is StateCommitResult.Saved)
        assertTrue(repository.recordRecoveryProfiles(host, listOf(profile)) is StateCommitResult.Saved)

        val reopened = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), AndroidKeystoreStateCipher())
        val state = (reopened.load() as StateLoadResult.Ready).state
        assertEquals(listOf(profile), state.activeHost?.cachedRecoveryProfiles)
        assertEquals(entry, state.journal.single())
        assertEquals(PreviewPresentation.Pending(PendingDisplayResult.CHECKING_PREVIEW, 1),
            DisplayRecoveryPresentation.project(host, state, StatusPollState(), 1_000_000_000L))
    }

    @Test
    fun stagedReplacementKeepsOldHostUntilAtomicConfirmAcrossReopenedAndroidStores() {
        val oldHost = activeHost()
        val oldRequest = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject("request_id" to JsonValue.StringValue("old-power"),
                "action" to JsonValue.StringValue("suspend")), oldHost.bearerToken)
        val oldEntry = JournalEntry.create(1, oldHost, oldRequest, BoundedJson.encode(oldRequest.body!!),
            1_790_000_000_100)
        assertTrue(repository.transact { it.copy(activeHost = oldHost, journal = listOf(oldEntry)) } is StateCommitResult.Saved)
        val candidate = PairingCandidate(
            generation = 0, variant = PairingVariant.VERIFICATION,
            clientId = "replacement-client", clientName = "Android Companion",
            requestedScopes = listOf("status.read"), endpoint = "new-deck.local:18443",
            certificatePin = "sha256:" + "cd".repeat(32), hostId = null,
            createdAtMillis = 1_790_000_000_000, expiresAtMillis = null,
            phase = CandidatePhase.READY, verificationNonce = "AbCdEfGhIjKlMnOpQrStUv",
        )
        val begun = PairingStateTransactions(repository).beginCandidate(candidate) as StateCommitResult.Saved
        val generation = requireNotNull(begun.state.candidate).generation

        val afterStart = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), AndroidKeystoreStateCipher())
        assertEquals(oldHost, (afterStart.load() as StateLoadResult.Ready).state.activeHost)
        val staged = StagedCredential("replacement-client", "replacement-host", "replacement-token-0001",
            listOf("status.read"), StoredWakeTarget(false, null, null, null, null), 1_790_000_000_050)
        assertTrue(PairingStateTransactions(afterStart).updateCandidate(generation) {
            it.copy(hostId = staged.hostId, pairingId = "replacement-pair", phase = CandidatePhase.APPROVED_STAGED,
                verificationNonce = null, stagedCredential = staged)
        } is StateCommitResult.Saved)

        val afterApproval = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), AndroidKeystoreStateCipher())
        val beforeConfirm = (afterApproval.load() as StateLoadResult.Ready).state
        assertEquals(oldHost, beforeConfirm.activeHost)
        assertEquals(CandidatePhase.APPROVED_STAGED, beforeConfirm.candidate?.phase)
        val replacement = ConnectionReplacement(PairingStateTransactions(afterApproval),
            OperationJournal(afterApproval, AndroidClock()))
        assertTrue(replacement.confirm(oldHost, generation) is StateCommitResult.Saved)

        val afterSwap = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), AndroidKeystoreStateCipher())
        val swapped = (afterSwap.load() as StateLoadResult.Ready).state
        assertEquals("replacement-host", swapped.activeHost?.hostId)
        assertEquals(null, swapped.candidate)
        assertEquals(listOf(oldEntry), swapped.journal)
    }

    @Test
    fun lostDiscardCleanupRetainsOldHostAndItsMutationLaneAcrossReopen() {
        val oldHost = activeHost()
        val oldRequest = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject("request_id" to JsonValue.StringValue("old-before-discard"),
                "action" to JsonValue.StringValue("suspend")), oldHost.bearerToken)
        val oldEntry = JournalEntry.create(1, oldHost, oldRequest, BoundedJson.encode(oldRequest.body!!),
            AndroidClock().wallTimeMillis())
            .copy(phase = JournalPhase.FAILED, resultSummary = "Not sent")
        assertTrue(repository.transact { it.copy(activeHost = oldHost, journal = listOf(oldEntry)) } is StateCommitResult.Saved)
        val candidate = PairingCandidate(
            generation = 0, variant = PairingVariant.VERIFICATION,
            clientId = "discard-client", clientName = "Android Companion",
            requestedScopes = listOf("status.read"), endpoint = "discard-deck.local:18443",
            certificatePin = "sha256:" + "cd".repeat(32), hostId = null,
            createdAtMillis = 1_790_000_000_000, expiresAtMillis = null,
            phase = CandidatePhase.READY, verificationNonce = "AbCdEfGhIjKlMnOpQrStUv",
        )
        val begun = PairingStateTransactions(repository).beginCandidate(candidate) as StateCommitResult.Saved
        val generation = requireNotNull(begun.state.candidate).generation
        val staged = StagedCredential("discard-client", "discard-host", "discard-token-0001",
            listOf("status.read"), StoredWakeTarget(false, null, null, null, null), 1_790_000_000_050)
        assertTrue(PairingStateTransactions(repository).updateCandidate(generation) {
            it.copy(hostId = staged.hostId, pairingId = "discard-pair", phase = CandidatePhase.APPROVED_STAGED,
                verificationNonce = null, stagedCredential = staged)
        } is StateCommitResult.Saved)

        val afterApproval = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), AndroidKeystoreStateCipher())
        val replacement = ConnectionReplacement(PairingStateTransactions(afterApproval),
            OperationJournal(afterApproval, AndroidClock()))
        var cleanupSends = 0
        val discard = replacement.discard(generation) { target, request, _ ->
            cleanupSends++
            assertEquals(staged.hostId, target.hostId)
            assertEquals(staged.bearerToken, target.bearerToken)
            assertEquals(ApiRoute.PAIR_REVOKE_SELF.path, request.path)
            RequestOutcome.Unknown(TransportFailure.IO)
        } as ReplacementDiscardResult.HostRemovalUnconfirmed
        assertEquals(1, cleanupSends)
        assertEquals(JournalPhase.UNKNOWN, discard.entry.phase)

        val reopened = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), AndroidKeystoreStateCipher())
        val state = (reopened.load() as StateLoadResult.Ready).state
        assertEquals(oldHost, state.activeHost)
        assertEquals(null, state.candidate)
        assertEquals(2, state.journal.size)
        assertEquals(oldEntry, state.journal.single { it.hostId == oldHost.hostId })
        val cleanup = state.journal.single { it.hostId == staged.hostId }
        assertEquals(JournalPhase.UNKNOWN, cleanup.phase)
        assertEquals(staged.clientId, cleanup.clientId)

        var oldHostSends = 0
        val newRequest = oldRequest.copy(body = jsonObject(
            "request_id" to JsonValue.StringValue("old-after-discard"),
            "action" to JsonValue.StringValue("restart")))
        val oldHostAction = OperationJournal(reopened, AndroidClock()).dispatchForHost(oldHost, newRequest) { target, _, _ ->
            oldHostSends++
            assertEquals(oldHost, target)
            RequestOutcome.NotSent(TransportFailure.CONNECT)
        }
        assertTrue(oldHostAction is io.github.tuthan.steamoscompanion.core.client.JournalDispatchResult.Finished)
        assertEquals(1, oldHostSends)
    }

    @Test
    fun failedAtomicWriteReopensTheCompleteOldState() {
        val saved = repository.transact { it.copy(activeHost = activeHost()) } as StateCommitResult.Saved
        val atomicFile = AtomicFile(baseFile)
        val partial = atomicFile.startWrite()
        partial.write(byteArrayOf(0x53, 0x43, 0x41, 0x53, 0x00))
        atomicFile.failWrite(partial)

        assertEquals(saved.state, (repository.load() as StateLoadResult.Ready).state)
        assertTrue(baseFile.length() in 1..PersistentStateLimits.MAX_ENVELOPE_BYTES.toLong())
        assertFalse(File(baseFile.path + ".bak").exists())
        assertFalse(File(baseFile.path + ".new").exists())
    }

    @Test
    fun abruptStopWithBackupAndPartialNewFileRecoversTheCompleteOldState() {
        val saved = repository.transact { it.copy(activeHost = activeHost()) } as StateCommitResult.Saved
        val partial = AtomicFile(baseFile).startWrite()
        partial.write(byteArrayOf(0x53, 0x43, 0x41, 0x53, 0x01, 0x02))
        partial.close() // Simulate process loss: deliberately do not call finishWrite or failWrite.
        assertTrue(
            File(baseFile.path + ".bak").exists() || baseFile.exists() && File(baseFile.path + ".new").exists(),
        )
        assertTrue(baseFile.exists() || File(baseFile.path + ".new").exists())

        val reopened = EncryptedStateRepository(AndroidAtomicStateStore(baseFile), cipher)
        assertEquals(saved.state, (reopened.load() as StateLoadResult.Ready).state)
        assertFalse(File(baseFile.path + ".bak").exists())
        assertFalse(File(baseFile.path + ".new").exists())
    }

    @Test
    fun abruptFirstWriteWithoutOldStateIsRemovedAndLoadsAsEmpty() {
        File(baseFile.path + AndroidAtomicStateStore.FIRST_WRITE_MARKER_SUFFIX).writeBytes(byteArrayOf(1))
        val partial = AtomicFile(baseFile).startWrite()
        partial.write(byteArrayOf(0x53, 0x43, 0x41, 0x53, 0x01))
        partial.close()
        assertTrue(baseFile.exists() || File(baseFile.path + ".new").exists())

        val load = repository.load()
        assertEquals(StateLoadResult.Empty, load)
        assertFalse(baseFile.exists())
        assertFalse(File(baseFile.path + ".new").exists())
        assertFalse(File(baseFile.path + AndroidAtomicStateStore.FIRST_WRITE_MARKER_SUFFIX).exists())
    }

    @Test
    fun tagCorruptionAndMissingKeyAreExplicitAndNeverAutoReset() {
        repository.transact { it.copy(activeHost = activeHost()) }
        val valid = baseFile.readBytes()
        val tampered = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        baseFile.writeBytes(tampered)
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED), repository.load())
        assertArrayEquals(tampered, baseFile.readBytes())

        baseFile.writeBytes(valid)
        assertTrue(keyStore().containsAlias(PersistentStateLimits.STATE_KEY_ALIAS))
        cipher.deleteKey()
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.KEY_MISSING), repository.load())
        assertFalse(keyStore().containsAlias(PersistentStateLimits.STATE_KEY_ALIAS))
        assertArrayEquals(valid, baseFile.readBytes())
    }

    @Test
    fun oversizedEnvelopeIsDamagedRatherThanTransientIoAndResetRemovesEveryAtomicFile() {
        baseFile.writeBytes(ByteArray(PersistentStateLimits.MAX_ENVELOPE_BYTES + 1))
        assertEquals(StateLoadResult.Unavailable(StateUnavailableReason.DAMAGED), repository.load())
        assertTrue(baseFile.exists())

        baseFile.delete()
        repository.transact { it.copy(activeHost = activeHost()) }
        File(baseFile.path + ".bak").writeBytes(byteArrayOf(1))
        File(baseFile.path + ".new").writeBytes(byteArrayOf(2))
        assertEquals(StateResetResult.Cleared, repository.reset())
        assertFalse(baseFile.exists())
        assertFalse(File(baseFile.path + ".bak").exists())
        assertFalse(File(baseFile.path + ".new").exists())
        assertFalse(keyStore().containsAlias(PersistentStateLimits.STATE_KEY_ALIAS))
        assertEquals(StateLoadResult.Empty, repository.load())
    }

    private fun iv(envelope: ByteArray): ByteArray {
        assertEquals("SCAS", String(envelope.copyOfRange(0, 4), Charsets.US_ASCII))
        val aliasLength = ByteBuffer.wrap(envelope, 8, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        return envelope.copyOfRange(10 + aliasLength, 22 + aliasLength)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun activeHost() = ActiveHost(
        hostId = "host-persistence-0001",
        clientId = "android-persistence",
        endpoint = "deck.local:18443",
        certificatePin = "sha256:" + "ab".repeat(32),
        bearerToken = "phase04-token-0001-xxxxxxxxxxxxxxxx",
        grantedScopes = listOf("status.read", "power.control"),
        wakeTarget = StoredWakeTarget(false, null, null, null, "No usable wake interface"),
        pairedAtMillis = 1_790_000_000_000,
    )
}
