package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Ciphertext
import io.github.tuthan.steamoscompanion.core.ports.StateCipher
import io.github.tuthan.steamoscompanion.core.ports.StateKeyException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyMissingException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyInvalidatedException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyTemporarilyUnavailableException
import io.github.tuthan.steamoscompanion.core.ports.StateAuthenticationException
import io.github.tuthan.steamoscompanion.core.ports.StateStore
import io.github.tuthan.steamoscompanion.core.ports.StateStoreInvalidDataException
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class MemoryStateStore : StateStore {
    private var bytes: ByteArray? = null
    var failNextWrite = false
    var failClear = false
    var invalidData = false

    @Synchronized override fun read(): ByteArray? {
        if (invalidData) throw StateStoreInvalidDataException("simulated oversized envelope")
        return bytes?.copyOf()
    }

    @Synchronized override fun writeAtomically(value: ByteArray) {
        if (failNextWrite) {
            failNextWrite = false
            throw java.io.IOException("simulated disk full")
        }
        bytes = value.copyOf()
    }

    @Synchronized override fun clear() {
        if (failClear) throw java.io.IOException("simulated delete failure")
        bytes = null
    }

    @Synchronized fun rawBytes(): ByteArray? = bytes?.copyOf()
    @Synchronized fun replaceRaw(value: ByteArray?) { bytes = value?.copyOf() }
}

internal class JvmAesGcmStateCipher : StateCipher {
    override val keyAlias = PersistentStateLimits.STATE_KEY_ALIAS
    private var key: SecretKeySpec? = null
    private val random = SecureRandom()
    var encryptCount = 0
    var deleteFails = false
    var invalidated = false
    var temporarilyUnavailable = false

    @Synchronized override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Ciphertext {
        checkAccess()
        val activeKey = key ?: SecretKeySpec(ByteArray(32) { 0x55 }, "AES").also { key = it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, activeKey)
        cipher.updateAAD(associatedData)
        encryptCount++
        return Ciphertext(cipher.iv, cipher.doFinal(plaintext))
    }

    @Synchronized override fun decrypt(iv: ByteArray, ciphertextAndTag: ByteArray, associatedData: ByteArray): ByteArray {
        checkAccess()
        val activeKey = key ?: throw StateKeyMissingException()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, activeKey, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertextAndTag)
        } catch (error: AEADBadTagException) {
            throw StateAuthenticationException(error)
        }
    }

    @Synchronized override fun deleteKey() {
        if (deleteFails) throw StateKeyTemporarilyUnavailableException()
        key = null
    }

    @Synchronized fun makeKeyMissing() { key = null }

    private fun checkAccess() {
        if (invalidated) throw StateKeyInvalidatedException()
        if (temporarilyUnavailable) throw StateKeyTemporarilyUnavailableException()
    }
}

internal fun testActiveHost(
    hostId: String = "host-fixture0001xxxxxxxxxxxxx",
    clientId: String = "client-android",
    endpoint: String = "deck.local:18443",
    token: String = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx",
) = ActiveHost(
    hostId = hostId,
    clientId = clientId,
    endpoint = endpoint,
    certificatePin = "sha256:" + "ab".repeat(32),
    bearerToken = token,
    grantedScopes = listOf("status.read", "power.control", "display.control"),
    wakeTarget = StoredWakeTarget(false, "", null, null, "No usable wake interface"),
    pairedAtMillis = 1_780_000_000_000,
)

internal fun testPowerRequest(requestId: String = "power-1") = ApiRequest(
    HttpMethod.POST,
    "/v1/power",
    jsonObject(
        "request_id" to JsonValue.StringValue(requestId),
        "action" to JsonValue.StringValue("suspend"),
    ),
    testActiveHost().bearerToken,
)

internal fun testCandidate(
    clientId: String = "client-new",
    nonce: String = "AbCdEfGhIjKlMnOpQrStUv",
) = PairingCandidate(
    generation = 0,
    variant = PairingVariant.VERIFICATION,
    clientId = clientId,
    clientName = "Android Companion",
    requestedScopes = listOf("status.read", "power.control"),
    endpoint = "new-deck.local:18443",
    certificatePin = "sha256:" + "cd".repeat(32),
    hostId = null,
    createdAtMillis = 1_790_000_000_000,
    expiresAtMillis = null,
    phase = CandidatePhase.READY,
    verificationNonce = nonce,
)

internal fun FakePreparedEnvelope(cipher: StateCipher, store: MemoryStateStore, plaintext: ByteArray) {
    val encrypted = cipher.encrypt(plaintext, PersistentStateLimits.STATE_AAD.toByteArray(Charsets.UTF_8))
    store.replaceRaw(StateEnvelopeCodec.encode(cipher.keyAlias, encrypted.iv, encrypted.bytes))
}
