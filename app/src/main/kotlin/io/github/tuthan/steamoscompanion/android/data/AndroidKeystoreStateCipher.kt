package io.github.tuthan.steamoscompanion.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import io.github.tuthan.steamoscompanion.core.client.PersistentStateLimits
import io.github.tuthan.steamoscompanion.core.ports.Ciphertext
import io.github.tuthan.steamoscompanion.core.ports.StateAuthenticationException
import io.github.tuthan.steamoscompanion.core.ports.StateCipher
import io.github.tuthan.steamoscompanion.core.ports.StateKeyInvalidatedException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyMissingException
import io.github.tuthan.steamoscompanion.core.ports.StateKeyTemporarilyUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256/GCM with an Android Keystore key that does not require biometric authentication. */
class AndroidKeystoreStateCipher internal constructor(
    override val keyAlias: String = PersistentStateLimits.STATE_KEY_ALIAS,
) : StateCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Ciphertext {
        if (plaintext.size > PersistentStateLimits.MAX_PLAINTEXT_BYTES) {
            throw GeneralSecurityException("Plaintext exceeds saved-state limit")
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(associatedData)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw GeneralSecurityException("Keystore generated an invalid AES-GCM IV")
            return Ciphertext(iv.copyOf(), cipher.doFinal(plaintext))
        } catch (error: Exception) {
            throw translate(error)
        }
    }

    override fun decrypt(iv: ByteArray, ciphertextAndTag: ByteArray, associatedData: ByteArray): ByteArray {
        if (iv.size != IV_BYTES || ciphertextAndTag.size < TAG_BYTES ||
            ciphertextAndTag.size > PersistentStateLimits.MAX_PLAINTEXT_BYTES + TAG_BYTES
        ) throw StateAuthenticationException()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, requireExistingKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertextAndTag)
        } catch (error: Exception) {
            throw translate(error)
        }
    }

    override fun deleteKey() {
        try {
            val store = keyStore()
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        } catch (error: Exception) {
            throw StateKeyTemporarilyUnavailableException(error)
        }
    }

    /** Null means the key is not available for inspection; hardware backing is not required. */
    @Suppress("DEPRECATION") // KeyInfo exposes this property on the API 26 minimum device.
    fun hardwareBacked(): Boolean? = try {
        val key = requireExistingKey()
        val factory = javax.crypto.SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.isInsideSecureHardware
    } catch (_: Exception) {
        null
    }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        val existing = store.getKey(keyAlias, null)
        if (existing is SecretKey) return existing
        if (store.containsAlias(keyAlias)) throw GeneralSecurityException("Saved-state key alias has the wrong type")

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(KEY_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun requireExistingKey(): SecretKey {
        val store = keyStore()
        if (!store.containsAlias(keyAlias)) throw StateKeyMissingException()
        val key = store.getKey(keyAlias, null) as? SecretKey ?: throw StateKeyMissingException()
        return key
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun translate(error: Exception): Exception {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        return when {
            causes.any { it is KeyPermanentlyInvalidatedException } -> StateKeyInvalidatedException(error)
            causes.any { it is UserNotAuthenticatedException } -> StateKeyTemporarilyUnavailableException(error)
            causes.any { it is UnrecoverableKeyException } -> StateKeyInvalidatedException(error)
            causes.any { it is AEADBadTagException || it is BadPaddingException } -> StateAuthenticationException(error)
            error is StateKeyMissingException || error is StateKeyInvalidatedException ||
                error is StateKeyTemporarilyUnavailableException || error is StateAuthenticationException -> error
            else -> StateKeyTemporarilyUnavailableException(error)
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
