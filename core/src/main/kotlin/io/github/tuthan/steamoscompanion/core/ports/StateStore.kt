package io.github.tuthan.steamoscompanion.core.ports

/** Durable bytes are owned and validated by core; implementations must replace atomically. */
interface StateStore {
    fun read(): ByteArray?
    fun writeAtomically(value: ByteArray)
    fun clear()
}

/** The stored byte sequence violates the adapter's bounded encrypted-envelope contract. */
class StateStoreInvalidDataException(message: String, cause: Throwable? = null) : Exception(message, cause)
