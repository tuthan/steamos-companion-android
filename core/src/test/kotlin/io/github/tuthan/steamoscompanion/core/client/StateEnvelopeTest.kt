package io.github.tuthan.steamoscompanion.core.client

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StateEnvelopeTest {
    @Test
    fun encodesTheFixedBigEndianScasEnvelope() {
        val alias = PersistentStateLimits.STATE_KEY_ALIAS
        val iv = ByteArray(12) { it.toByte() }
        val encrypted = ByteArray(32) { (it + 1).toByte() }
        val bytes = StateEnvelopeCodec.encode(alias, iv, encrypted)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also(input::get)
        assertArrayEquals("SCAS".toByteArray(Charsets.US_ASCII), magic)
        assertEquals(1, input.int)
        val aliasLength = input.short.toInt() and 0xffff
        assertEquals(alias.toByteArray(Charsets.UTF_8).size, aliasLength)
        val parsed = StateEnvelopeCodec.decode(bytes, alias)
        assertEquals(1, parsed.formatVersion)
        assertEquals(alias, parsed.keyAlias)
        assertArrayEquals(iv, parsed.iv)
        assertArrayEquals(encrypted, parsed.ciphertextAndTag)
    }

    @Test
    fun rejectsBadAliasesSizesLengthsTruncationTrailingAndUnknownFormats() {
        val alias = PersistentStateLimits.STATE_KEY_ALIAS
        val valid = StateEnvelopeCodec.encode(alias, ByteArray(12), ByteArray(16))
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.decode(valid, "some-other-app-key") }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.encode("x".repeat(129), ByteArray(12), ByteArray(16)) }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.encode(alias, ByteArray(11), ByteArray(16)) }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.encode(alias, ByteArray(12), ByteArray(15)) }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.decode(valid.copyOf(valid.size - 1), alias) }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.decode(valid + byteArrayOf(0), alias) }

        val badMagic = valid.copyOf().also { it[0] = 'X'.code.toByte() }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.decode(badMagic, alias) }
        val newerFormat = valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(4, 2) }
        assertThrows(UnsupportedStateFormatException::class.java) { StateEnvelopeCodec.decode(newerFormat, alias) }

        val wrongLength = valid.copyOf().also {
            val aliasLength = ByteBuffer.wrap(it, 8, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
            val lengthOffset = 4 + 4 + 2 + aliasLength + 12
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(lengthOffset, Int.MAX_VALUE)
        }
        assertThrows(StateEnvelopeException::class.java) { StateEnvelopeCodec.decode(wrongLength, alias) }
    }
}
