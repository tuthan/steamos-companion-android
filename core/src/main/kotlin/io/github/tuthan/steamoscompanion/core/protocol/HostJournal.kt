package io.github.tuthan.steamoscompanion.core.protocol

/**
 * The host's operation journal as `GET /v1/operations` returns it (Decky `protocol/schema.json`
 * `$defs/operation`), rebuilt from an allowlist. It is diagnostic evidence of what the host recorded:
 * it lives in memory only and is never persisted, journaled, or exported, and nothing here decides
 * a mutation or proves a visible picture.
 *
 * [records] keep the host's order (newest first by the contract); they are not re-sorted here.
 */
data class HostJournal(val records: List<HostJournalRecord>)

/**
 * One journal record. [id], [kind] and [state] are validated tokens, so an unknown kind or state is
 * still a safe string to show. Every other field is optional free text, bounded and stripped of
 * control characters. `request_id` and `preview_id` are in the schema but are not needed to show
 * the journal, so they are not kept.
 */
data class HostJournalRecord(
    val id: String,
    val kind: String,
    val state: String,
    val createdAt: String?,
    val updatedAt: String?,
    val outcome: String?,
    val reason: String?,
    val restoreState: String?,
    val restored: Boolean?,
    val resolvedBy: String?,
    /** What the host says was requested; empty when absent or unusable. At most [HostJournalDecoder.MAX_TARGET_KEYS] keys. */
    val target: Map<String, JournalValue>,
)

/** A target value: a scalar, a short list of strings, or one level of scalars. Nothing deeper is kept. */
sealed interface JournalValue {
    sealed interface Scalar : JournalValue
    data class Flag(val value: Boolean) : Scalar
    data class Whole(val value: Long) : Scalar
    data class Real(val value: Double) : Scalar
    data class Text(val value: String) : Scalar
    data class TextList(val values: List<String>) : JournalValue
    data class Group(val entries: Map<String, Scalar>) : JournalValue
}

/**
 * Tolerant decoder for the `operations` array. It is diagnostic, so nothing here may throw and nothing
 * may fail the read it came from: a list that is absent, not an array or over the schema's 256 records is
 * null ("no journal"); a record that is not an object or whose `id`, `kind` or `state` is not a safe token
 * is dropped; any other bad field is null or dropped; unknown keys are ignored. Every kept record is
 * rebuilt field by field, never passed through.
 */
internal object HostJournalDecoder {
    /** The schema's `maxItems`; a longer list is not a conforming host, so it is not shown at all. */
    const val MAX_WIRE_RECORDS = 256
    const val MAX_TARGET_KEYS = 8
    const val MAX_TARGET_LIST = 16
    const val MAX_TARGET_GROUP = 6
    const val MAX_TARGET_TEXT = 128
    private const val MAX_TIMESTAMP = 64
    private const val MAX_OUTCOME = 128
    private const val MAX_REASON = 256
    private const val MAX_RESTORE_STATE = 64
    private const val MAX_RESOLVED_BY = 128
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_992.0 // 2^53, as the Decky client bounds numbers

    private val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val WHOLE = Regex("-?[0-9]{1,16}")

    fun decode(value: JsonValue?): HostJournal? {
        val items = (value as? JsonValue.ArrayValue)?.values ?: return null
        if (items.size > MAX_WIRE_RECORDS) return null
        return try {
            HostJournal(items.mapNotNull(::record))
        } catch (_: RuntimeException) {
            // Belt and braces: diagnostic data must never be the reason a request fails.
            null
        }
    }

    private fun record(item: JsonValue): HostJournalRecord? {
        val fields = item as? JsonValue.ObjectValue ?: return null
        val id = token(fields["id"]) ?: return null
        val kind = token(fields["kind"]) ?: return null
        val state = token(fields["state"]) ?: return null
        return HostJournalRecord(
            id = id,
            kind = kind,
            state = state,
            createdAt = text(fields["created_at"], MAX_TIMESTAMP),
            updatedAt = text(fields["updated_at"], MAX_TIMESTAMP),
            outcome = text(fields["outcome"], MAX_OUTCOME),
            reason = text(fields["reason"], MAX_REASON),
            restoreState = text(fields["restore_state"], MAX_RESTORE_STATE),
            restored = (fields["restored"] as? JsonValue.BooleanValue)?.value,
            resolvedBy = text(fields["resolved_by"], MAX_RESOLVED_BY),
            target = target(fields["target"]),
        )
    }

    private fun token(value: JsonValue?): String? =
        (value as? JsonValue.StringValue)?.value?.takeIf(TOKEN::matches)

    private fun text(value: JsonValue?, maxLength: Int): String? =
        (value as? JsonValue.StringValue)?.value?.let { JournalText.clean(it, maxLength) }

    /** Up to [MAX_TARGET_KEYS] entries of the first [MAX_TARGET_KEYS] the host sent; an entry that does not fit the shapes is dropped. */
    private fun target(value: JsonValue?): Map<String, JournalValue> {
        val fields = (value as? JsonValue.ObjectValue)?.fields ?: return emptyMap()
        val result = LinkedHashMap<String, JournalValue>()
        for ((key, item) in fields.entries.take(MAX_TARGET_KEYS)) {
            if (!TOKEN.matches(key)) continue
            val kept: JournalValue = when (item) {
                is JsonValue.ArrayValue -> textList(item)
                is JsonValue.ObjectValue -> group(item)
                else -> scalar(item)
            } ?: continue
            result[key] = kept
        }
        return result
    }

    /** All entries must be strings and there must be at most [MAX_TARGET_LIST]: a list is never shown cut short. */
    private fun textList(array: JsonValue.ArrayValue): JournalValue.TextList? {
        if (array.values.size > MAX_TARGET_LIST) return null
        val texts = array.values.map { entry ->
            (entry as? JsonValue.StringValue)?.value?.let { JournalText.clean(it, MAX_TARGET_TEXT) } ?: return null
        }
        return texts.takeIf { it.isNotEmpty() }?.let(JournalValue::TextList)
    }

    private fun group(item: JsonValue.ObjectValue): JournalValue.Group? {
        val entries = LinkedHashMap<String, JournalValue.Scalar>()
        for ((key, inner) in item.fields.entries.take(MAX_TARGET_GROUP)) {
            if (!TOKEN.matches(key)) continue
            entries[key] = scalar(inner) ?: continue
        }
        return entries.takeIf { it.isNotEmpty() }?.let(JournalValue::Group)
    }

    private fun scalar(value: JsonValue?): JournalValue.Scalar? = when (value) {
        is JsonValue.BooleanValue -> JournalValue.Flag(value.value)
        is JsonValue.StringValue -> JournalText.clean(value.value, MAX_TARGET_TEXT)?.let(JournalValue::Text)
        is JsonValue.NumberValue -> number(value.lexical)
        else -> null
    }

    /** Integers and finite decimals below 2^53 in magnitude; anything else is not a fact worth showing. */
    private fun number(lexical: String): JournalValue.Scalar? {
        if (WHOLE.matches(lexical)) {
            val whole = lexical.toLongOrNull() ?: return null
            return if (Math.abs(whole.toDouble()) < MAX_SAFE_INTEGER) JournalValue.Whole(whole) else null
        }
        val real = lexical.toDoubleOrNull() ?: return null
        return if (real.isFinite() && Math.abs(real) < MAX_SAFE_INTEGER) JournalValue.Real(real) else null
    }
}

/** Text from the host is shown to a person, so it is bounded and stripped of anything that is not plain text. */
internal object JournalText {
    /**
     * At most [maxCodePoints] code points. Tabs and line breaks become one space; other control, format
     * (including bidirectional overrides), separator, surrogate, private-use and unassigned characters are
     * removed; runs of spaces collapse and the ends are trimmed. Null when nothing is left.
     */
    fun clean(value: String, maxCodePoints: Int): String? {
        val out = StringBuilder()
        var count = 0
        var index = 0
        while (index < value.length && count < maxCodePoints) {
            val codePoint = value.codePointAt(index)
            index += Character.charCount(codePoint)
            val kept = when {
                codePoint == '\t'.code || codePoint == '\n'.code || codePoint == '\r'.code -> ' '.code
                else -> when (Character.getType(codePoint).toByte()) {
                    Character.SPACE_SEPARATOR -> ' '.code
                    Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> continue
                    else -> codePoint
                }
            }
            if (kept == ' '.code && (out.isEmpty() || out.last() == ' ')) continue
            out.appendCodePoint(kept)
            count++
        }
        return out.toString().trimEnd().takeIf { it.isNotEmpty() }
    }
}
