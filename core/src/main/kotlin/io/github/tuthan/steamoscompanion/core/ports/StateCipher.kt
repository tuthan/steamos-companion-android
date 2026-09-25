package io.github.tuthan.steamoscompanion.core.ports

/** Authenticated-encryption boundary for the durable state repository. */
interface StateCipher {
    /** App-owned alias. It is checked against the envelope and is never selected from untrusted bytes. */
    val keyAlias: String

    /** Implementations must use a fresh provider-generated IV for every encryption. */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Ciphertext

    fun decrypt(iv: ByteArray, ciphertextAndTag: ByteArray, associatedData: ByteArray): ByteArray

    fun deleteKey()
}

data class Ciphertext(val iv: ByteArray, val bytes: ByteArray)

open class StateKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)
class StateKeyMissingException : StateKeyException("Saved-state key is missing")
class StateKeyInvalidatedException(cause: Throwable? = null) : StateKeyException("Saved-state key is permanently invalidated", cause)
class StateKeyTemporarilyUnavailableException(cause: Throwable? = null) : StateKeyException("Saved-state key is temporarily unavailable", cause)
class StateAuthenticationException(cause: Throwable? = null) : StateKeyException("Saved-state authentication failed", cause)
