package io.github.tuthan.steamoscompanion.core.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Small immutable JSON tree used by the JVM protocol layer; it has no platform or parser dependency. */
sealed interface JsonValue {
    data class ObjectValue(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(name: String): JsonValue? = fields[name]
    }

    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val lexical: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

data class JsonLimits(
    val maxBytes: Int = 256 * 1024,
    val maxDepth: Int = 32,
    val maxStringCodePoints: Int = 4096,
    val maxContainerItems: Int = 4096,
    val maxNodes: Int = 32_768,
    val maxNumberChars: Int = 128,
)

class JsonFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Strict UTF-8 JSON parser. It rejects duplicate keys, malformed Unicode, excess depth, and trailing input. */
object BoundedJson {
    fun parseObject(bytes: ByteArray, limits: JsonLimits = JsonLimits()): JsonValue.ObjectValue {
        if (bytes.size > limits.maxBytes) throw JsonFormatException("JSON body exceeds ${limits.maxBytes} bytes")
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw JsonFormatException("JSON body is not valid UTF-8", error)
        }
        val value = Parser(text, limits).parse()
        return value as? JsonValue.ObjectValue ?: throw JsonFormatException("JSON response must be an object")
    }

    fun encode(value: JsonValue): ByteArray {
        val out = StringBuilder()
        write(value, out)
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.ObjectValue -> {
                out.append('{')
                value.fields.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    writeString(entry.key, out)
                    out.append(':')
                    write(entry.value, out)
                }
                out.append('}')
            }
            is JsonValue.ArrayValue -> {
                out.append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonValue.StringValue -> writeString(value.value, out)
            is JsonValue.NumberValue -> {
                if (!validNumber(value.lexical)) throw JsonFormatException("Invalid JSON number")
                out.append(value.lexical)
            }
            is JsonValue.BooleanValue -> out.append(if (value.value) "true" else "false")
            JsonValue.NullValue -> out.append("null")
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\b' -> out.append("\\b")
                ch == '\u000c' -> out.append("\\f")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch < ' ' -> out.append("\\u%04x".format(ch.code))
                ch.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        throw JsonFormatException("String contains an unpaired surrogate")
                    }
                    out.append(ch).append(value[index + 1])
                    index++
                }
                ch.isLowSurrogate() -> throw JsonFormatException("String contains an unpaired surrogate")
                else -> out.append(ch)
            }
            index++
        }
        out.append('"')
    }

    private fun validNumber(value: String): Boolean = NUMBER.matches(value)

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    private class Parser(private val text: String, private val limits: JsonLimits) {
        private var offset = 0
        private var nodes = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val result = value(0)
            skipWhitespace()
            if (offset != text.length) fail("Trailing data after JSON value")
            return result
        }

        private fun value(depth: Int): JsonValue {
            if (depth > limits.maxDepth) fail("JSON nesting exceeds ${limits.maxDepth}")
            if (++nodes > limits.maxNodes) fail("JSON node count exceeds ${limits.maxNodes}")
            if (offset >= text.length) fail("Unexpected end of JSON")
            return when (text[offset]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonValue.StringValue(string())
                't' -> literal("true", JsonValue.BooleanValue(true))
                'f' -> literal("false", JsonValue.BooleanValue(false))
                'n' -> literal("null", JsonValue.NullValue)
                '-', in '0'..'9' -> number()
                else -> fail("Unexpected character in JSON")
            }
        }

        private fun objectValue(depth: Int): JsonValue.ObjectValue {
            offset++
            skipWhitespace()
            val fields = LinkedHashMap<String, JsonValue>()
            if (take('}')) return JsonValue.ObjectValue(fields)
            while (true) {
                if (fields.size >= limits.maxContainerItems) fail("JSON object has too many fields")
                if (offset >= text.length || text[offset] != '"') fail("Object key must be a string")
                val key = string()
                if (fields.containsKey(key)) fail("Duplicate JSON object key")
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = value(depth + 1)
                skipWhitespace()
                if (take('}')) return JsonValue.ObjectValue(fields)
                expect(',')
                skipWhitespace()
            }
        }

        private fun arrayValue(depth: Int): JsonValue.ArrayValue {
            offset++
            skipWhitespace()
            val values = ArrayList<JsonValue>()
            if (take(']')) return JsonValue.ArrayValue(values)
            while (true) {
                if (values.size >= limits.maxContainerItems) fail("JSON array has too many items")
                values += value(depth + 1)
                skipWhitespace()
                if (take(']')) return JsonValue.ArrayValue(values)
                expect(',')
                skipWhitespace()
            }
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            var codePoints = 0
            while (offset < text.length) {
                val ch = text[offset++]
                when {
                    ch == '"' -> return result.toString()
                    ch == '\\' -> {
                        if (offset >= text.length) fail("Incomplete string escape")
                        when (val escaped = text[offset++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                val first = unicodeEscape()
                                when {
                                    first.isHighSurrogate() -> {
                                        if (offset + 1 >= text.length || text[offset] != '\\' || text[offset + 1] != 'u') {
                                            fail("Unpaired high surrogate in string")
                                        }
                                        offset += 2
                                        val second = unicodeEscape()
                                        if (!second.isLowSurrogate()) fail("Unpaired high surrogate in string")
                                        result.append(first).append(second)
                                    }
                                    first.isLowSurrogate() -> fail("Unpaired low surrogate in string")
                                    else -> result.append(first)
                                }
                            }
                            else -> fail("Invalid string escape")
                        }
                    }
                    ch < ' ' -> fail("Unescaped control character in string")
                    ch.isHighSurrogate() -> {
                        if (offset >= text.length || !text[offset].isLowSurrogate()) fail("Unpaired high surrogate in string")
                        result.append(ch).append(text[offset++])
                    }
                    ch.isLowSurrogate() -> fail("Unpaired low surrogate in string")
                    else -> result.append(ch)
                }
                codePoints++
                if (codePoints > limits.maxStringCodePoints) fail("JSON string exceeds ${limits.maxStringCodePoints} code points")
            }
            fail("Unterminated JSON string")
        }

        private fun unicodeEscape(): Char {
            if (offset + 4 > text.length) fail("Incomplete Unicode escape")
            var value = 0
            repeat(4) {
                val digit = text[offset++].digitToIntOrNull(16) ?: fail("Invalid Unicode escape")
                value = (value shl 4) or digit
            }
            return value.toChar()
        }

        private fun number(): JsonValue.NumberValue {
            val start = offset
            if (take('-') && offset >= text.length) fail("Incomplete JSON number")
            if (take('0')) {
                if (offset < text.length && text[offset].isDigit()) fail("Leading zero in JSON number")
            } else {
                if (offset >= text.length || text[offset] !in '1'..'9') fail("Invalid JSON number")
                while (offset < text.length && text[offset] in '0'..'9') offset++
            }
            if (take('.')) {
                val fractionStart = offset
                while (offset < text.length && text[offset] in '0'..'9') offset++
                if (fractionStart == offset) fail("JSON fraction has no digits")
            }
            if (offset < text.length && (text[offset] == 'e' || text[offset] == 'E')) {
                offset++
                if (offset < text.length && (text[offset] == '+' || text[offset] == '-')) offset++
                val exponentStart = offset
                while (offset < text.length && text[offset] in '0'..'9') offset++
                if (exponentStart == offset) fail("JSON exponent has no digits")
            }
            val lexical = text.substring(start, offset)
            if (lexical.length > limits.maxNumberChars) fail("JSON number is too long")
            return JsonValue.NumberValue(lexical)
        }

        private fun <T : JsonValue> literal(expected: String, value: T): T {
            if (!text.regionMatches(offset, expected, 0, expected.length)) fail("Invalid JSON literal")
            offset += expected.length
            return value
        }

        private fun skipWhitespace() {
            while (offset < text.length && text[offset] in charArrayOf(' ', '\t', '\r', '\n')) offset++
        }

        private fun expect(ch: Char) {
            if (!take(ch)) fail("Expected '$ch'")
        }

        private fun take(ch: Char): Boolean {
            if (offset < text.length && text[offset] == ch) {
                offset++
                return true
            }
            return false
        }

        private fun fail(message: String): Nothing = throw JsonFormatException("$message at character $offset")
    }
}

fun jsonObject(vararg entries: Pair<String, JsonValue>): JsonValue.ObjectValue =
    JsonValue.ObjectValue(linkedMapOf(*entries))

fun String.asJson(): JsonValue.StringValue = JsonValue.StringValue(this)
fun Boolean.asJson(): JsonValue.BooleanValue = JsonValue.BooleanValue(this)
fun Long.asJson(): JsonValue.NumberValue = JsonValue.NumberValue(toString())

fun JsonValue?.asString(field: String): String =
    (this as? JsonValue.StringValue)?.value ?: throw JsonFormatException("'$field' must be a string")

fun JsonValue?.asLong(field: String): Long =
    (this as? JsonValue.NumberValue)?.lexical?.toLongOrNull()
        ?: throw JsonFormatException("'$field' must be an integer")

fun JsonValue?.asObject(field: String): JsonValue.ObjectValue =
    this as? JsonValue.ObjectValue ?: throw JsonFormatException("'$field' must be an object")

fun JsonValue?.asArray(field: String): List<JsonValue> =
    (this as? JsonValue.ArrayValue)?.values ?: throw JsonFormatException("'$field' must be an array")

private fun Char.isHighSurrogate() = this in '\uD800'..'\uDBFF'
private fun Char.isLowSurrogate() = this in '\uDC00'..'\uDFFF'
