package io.github.tuthan.steamoscompanion.core.client

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class StateEnvelope(val formatVersion: Int, val keyAlias: String, val iv: ByteArray, val ciphertextAndTag: ByteArray)

open class StateEnvelopeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
class UnsupportedStateFormatException(val version: Long) : StateEnvelopeException("Saved state needs a newer app")

/** Bounded fixed-layout SCAS envelope; alias validation never selects a key. */
object StateEnvelopeCodec {
    private val magic = byteArrayOf('S'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte())
    private const val IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val MAX_ALIAS_BYTES = 128

    fun encode(keyAlias: String, iv: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        val alias = keyAlias.toByteArray(StandardCharsets.UTF_8)
        if (alias.isEmpty() || alias.size > MAX_ALIAS_BYTES || iv.size != IV_BYTES ||
            ciphertextAndTag.size !in GCM_TAG_BYTES..(PersistentStateLimits.MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES)
        ) throw StateEnvelopeException("Encrypted state fields exceed their limits")

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(magic)
            data.writeInt(PersistentStateLimits.ENVELOPE_FORMAT_VERSION)
            data.writeShort(alias.size)
            data.write(alias)
            data.write(iv)
            data.writeInt(ciphertextAndTag.size)
            data.write(ciphertextAndTag)
        }
        val bytes = output.toByteArray()
        if (bytes.size > PersistentStateLimits.MAX_ENVELOPE_BYTES) throw StateEnvelopeException("Encrypted state envelope exceeds 65 KiB")
        return bytes
    }

    fun decode(bytes: ByteArray, expectedKeyAlias: String): StateEnvelope {
        if (bytes.size > PersistentStateLimits.MAX_ENVELOPE_BYTES || bytes.size < 4 + 4 + 2 + 1 + IV_BYTES + 4 + GCM_TAG_BYTES) {
            throw StateEnvelopeException("Encrypted state envelope has an invalid size")
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size).also(input::get)
        if (!actualMagic.contentEquals(magic)) throw StateEnvelopeException("Encrypted state magic is invalid")
        val formatLong = Integer.toUnsignedLong(input.int)
        if (formatLong != PersistentStateLimits.ENVELOPE_FORMAT_VERSION.toLong()) {
            if (formatLong > PersistentStateLimits.ENVELOPE_FORMAT_VERSION) throw UnsupportedStateFormatException(formatLong)
            throw StateEnvelopeException("Encrypted state format is invalid")
        }
        val aliasLength = input.short.toInt() and 0xffff
        if (aliasLength !in 1..MAX_ALIAS_BYTES || input.remaining() < aliasLength + IV_BYTES + 4 + GCM_TAG_BYTES) {
            throw StateEnvelopeException("Encrypted state key alias length is invalid")
        }
        val aliasBytes = ByteArray(aliasLength).also(input::get)
        val alias = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(aliasBytes)).toString()
        } catch (error: CharacterCodingException) {
            throw StateEnvelopeException("Encrypted state key alias is malformed", error)
        }
        if (alias != expectedKeyAlias) throw StateEnvelopeException("Encrypted state key alias does not match this app")
        val iv = ByteArray(IV_BYTES).also(input::get)
        val ciphertextLength = Integer.toUnsignedLong(input.int)
        if (ciphertextLength !in GCM_TAG_BYTES.toLong()..(PersistentStateLimits.MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES).toLong()) {
            throw StateEnvelopeException("Encrypted state ciphertext length is invalid")
        }
        if (input.remaining().toLong() != ciphertextLength) throw StateEnvelopeException("Encrypted state is truncated or has trailing bytes")
        val ciphertext = ByteArray(ciphertextLength.toInt()).also(input::get)
        return StateEnvelope(PersistentStateLimits.ENVELOPE_FORMAT_VERSION, alias, iv, ciphertext)
    }
}
