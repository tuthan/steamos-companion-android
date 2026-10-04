package io.github.tuthan.steamoscompanion.core.protocol

/**
 * The connector facts the host's kernel publishes, from `GET /v1/drm` (Decky `protocol/schema.json`
 * `$defs/drm`). They are advisory and live only: never persisted or journaled, and only the owner's explicit
 * export composes a redacted report from them (`DiagnosticReport`).
 *
 * The list is RESOLUTIONS ONLY: the kernel publishes no refresh rates, so nothing here supports a
 * conclusion about refresh rate. The HOST joins a connector to a display-bridge output
 * ([LinkConnector.steamOutputId]); a client never matches by name.
 */
data class LinkFacts(
    val available: Boolean,
    val observedAt: String?,
    val reason: String?,
    val connectors: List<LinkConnector>,
)

data class LinkConnector(
    val connector: String,
    /** `drm:cardN`, as the host sends it. */
    val gpuId: String,
    val displayName: String,
    /** Three capital letters from the display's identity, or null when unreadable. */
    val monitorVendor: String?,
    val monitorProductId: Int?,
    val isInternal: Boolean,
    val connected: Boolean,
    /** Deduplicated `"<width>x<height>"` strings in host order; empty when the link listed none. */
    val resolutions: List<String>,
    /** The id of the display-bridge output the HOST joined this connector to; null when unjoined or ambiguous. */
    val steamOutputId: String?,
)

/**
 * Tolerant decoder for the `drm` block. It is advisory, so nothing here may throw and nothing may fail
 * the read it came from: an absent, non-object, mistyped or oversize block is null ("no facts"); a
 * connector that is malformed, oversize or mistyped is dropped; a bad optional field is null; unknown
 * keys are ignored.
 *
 * Two choices keep a decoding gap from becoming a false warning. A connector is dropped as a whole
 * when ANY resolution entry is odd (skipping just that entry could make a listed mode look missing),
 * and a dropped connector still cancels the join of any kept connector claiming the same output id,
 * so "exactly one connector joined to this output" cannot be satisfied by hiding the other one.
 */
internal object LinkFactsDecoder {
    const val MAX_CONNECTORS = 16
    const val MAX_RESOLUTIONS = 256
    private const val MAX_OBSERVED_AT = 64
    private const val MAX_REASON = 256
    private const val MAX_DISPLAY_NAME = 128
    private const val MAX_OUTPUT_ID = 128
    private const val MAX_PRODUCT_ID = 65535

    private val CONNECTOR = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
    private val GPU = Regex("drm:card[0-9]{1,4}")
    private val VENDOR = Regex("[A-Z]{3}")
    private val RESOLUTION = Regex("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}")

    fun decode(value: JsonValue?): LinkFacts? {
        val block = value as? JsonValue.ObjectValue ?: return null
        return try {
            decodeBlock(block)
        } catch (_: RuntimeException) {
            // Belt and braces: advisory data must never be the reason a request fails.
            null
        }
    }

    private fun decodeBlock(block: JsonValue.ObjectValue): LinkFacts? {
        val available = (block["available"] as? JsonValue.BooleanValue)?.value ?: return null
        val observedAt = block["observed_at"].textOrNull(MAX_OBSERVED_AT)
        val reason = block["reason"].textOrNull(MAX_REASON)
        if (!available) return LinkFacts(false, observedAt, reason, emptyList())
        val items = (block["connectors"] as? JsonValue.ArrayValue)?.values ?: return null
        // Over the schema bound is not a conforming host: say nothing rather than reason about a truncated list.
        if (items.size > MAX_CONNECTORS) return null

        val kept = ArrayList<LinkConnector>(items.size)
        val droppedJoins = HashSet<String>()
        for (item in items) {
            val record = item as? JsonValue.ObjectValue ?: continue
            val parsed = connector(record)
            if (parsed != null) kept += parsed else record["steam_output_id"].outputIdOrNull()?.let(droppedJoins::add)
        }
        val connectors = kept.map {
            if (it.steamOutputId != null && it.steamOutputId in droppedJoins) it.copy(steamOutputId = null) else it
        }
        return LinkFacts(true, observedAt, reason, connectors)
    }

    private fun connector(record: JsonValue.ObjectValue): LinkConnector? {
        val name = record["connector"].textOrNull(64)?.takeIf(CONNECTOR::matches) ?: return null
        val gpu = record["gpu_id"].textOrNull(32)?.takeIf(GPU::matches) ?: return null
        val internal = (record["is_internal"] as? JsonValue.BooleanValue)?.value ?: return null
        val connected = (record["connected"] as? JsonValue.BooleanValue)?.value ?: return null
        val resolutions = resolutions(record["resolutions"]) ?: return null
        return LinkConnector(
            connector = name,
            gpuId = gpu,
            displayName = record["display_name"].textOrNull(MAX_DISPLAY_NAME) ?: name,
            monitorVendor = record["monitor_vendor"].textOrNull(3)?.takeIf(VENDOR::matches),
            monitorProductId = (record["monitor_product_id"] as? JsonValue.NumberValue)?.lexical?.toIntOrNull()
                ?.takeIf { it in 0..MAX_PRODUCT_ID },
            isInternal = internal,
            connected = connected,
            resolutions = resolutions,
            steamOutputId = record["steam_output_id"].outputIdOrNull(),
        )
    }

    /** Null (drop the connector) unless every entry is a well-formed `"<width>x<height>"` and there are at most 256. */
    private fun resolutions(value: JsonValue?): List<String>? {
        val entries = (value as? JsonValue.ArrayValue)?.values ?: return null
        if (entries.size > MAX_RESOLUTIONS) return null
        val result = LinkedHashSet<String>()
        for (entry in entries) {
            val text = (entry as? JsonValue.StringValue)?.value ?: return null
            if (!RESOLUTION.matches(text)) return null
            result += text
        }
        return result.toList()
    }

    private fun JsonValue?.outputIdOrNull(): String? = textOrNull(MAX_OUTPUT_ID)

    /** A non-blank string within [maxLength] with no control characters; anything else is "not reported". */
    private fun JsonValue?.textOrNull(maxLength: Int): String? {
        val text = (this as? JsonValue.StringValue)?.value ?: return null
        if (text.isBlank() || text.length > maxLength || text.any { it.isISOControl() }) return null
        return text
    }
}
