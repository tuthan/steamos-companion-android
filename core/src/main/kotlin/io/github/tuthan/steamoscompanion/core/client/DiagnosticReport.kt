package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.protocol.JournalValue
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.LinkConnector
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a diagnostic report is composed from: the readings the app already holds for the paired host. Every
 * one is optional; a reading that was never taken, or that the app's decoder refused, is a null section.
 *
 * [status] and [outputs] are the decoded payloads whose retained response bodies the report reads (the
 * decoded models drop facts the report names, such as a display's description and the literal plugin
 * version "unknown"); [linkFacts] and [journal] are the decoded, allowlisted models, the only form in
 * which the app keeps them.
 */
data class DiagnosticReadings(
    val status: VersionedPayload.Status? = null,
    val outputs: VersionedPayload.DisplayOutputs? = null,
    val linkFacts: LinkFacts? = null,
    val journal: HostJournal? = null,
) {
    companion object {
        /**
         * The readings that belong to [host], and only to it: the status read must have been observed for this
         * very pairing, the link facts for this pairing and its current output topology, and the journal for
         * this pairing. A reading held for any other credential, or none at all, is simply absent.
         */
        fun forHost(host: ActiveHost?, status: StatusPollState, linkFacts: LinkFactsReading?, journal: HostJournalState?): DiagnosticReadings {
            host ?: return DiagnosticReadings()
            val read = status.lastRead?.takeIf { status.source == StatusObservationSource.from(host) }
            val outputs = read?.outputs
            return DiagnosticReadings(
                status = read?.status,
                outputs = outputs,
                linkFacts = linkFacts?.factsFor(host, LinkFactsTopology.key(outputs)),
                journal = (journal?.forHost(host) as? HostJournalState.Ready)?.journal,
            )
        }
    }
}

/**
 * The redacted diagnostic report (Decky `protocol/README.md`, "Diagnostic report"): named facts only,
 * built from readings the app already holds, with free text scrubbed of addresses, pins, MACs, tokens and
 * paths before it is bounded. It is a port of Decky `host/backend/diagnostics.py`, proven against the
 * vendored cross-client vector `fixtures/diagnostic-report.json`.
 *
 * Nothing here copies a record. No operation, request, preview or profile id, no host or boot identifier,
 * no listener address, certificate pin, wake MAC, credential or pairing material can reach the report,
 * because each section is rebuilt field by field from an allowlist. The report is composed on demand for
 * the owner's explicit export; it is never stored. [compose] never throws: a section that cannot be built
 * is null.
 *
 * Python and Kotlin regular expressions differ in ways that matter here, so every identifier and code
 * pattern is matched against the WHOLE string (`matches`, never a prefix or `$`), `\b` is spelled out as
 * lookarounds over ASCII word characters, and digits are `[0-9]` (the reference's `\d` also accepts other
 * scripts' digits; here a value written with them is refused, which can only drop a fact, never leak one).
 */
object DiagnosticReport {
    const val FORMAT = "steamos-companion-report-v1"
    const val MAX_REPORT_BYTES = 12 * 1024
    const val MAX_METHODS = 8
    const val MAX_OUTPUTS = 8
    const val MAX_MODES = 24
    const val MAX_CONNECTORS = 8
    const val MAX_RESOLUTIONS = 32
    const val MAX_JOURNAL = 20

    private const val TEXT_PRECAP = 1024
    private const val MAX_PROFILES = 256
    private const val MAX_TARGET_INT = 1L shl 31

    // --- free text ---------------------------------------------------------------------------------------

    // ASCII \w and \b. The text these run on is printable ASCII only (see scrubText), so this is exactly Python's.
    private const val WORD = "[A-Za-z0-9_]"
    private const val BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
    private const val HEX = "[0-9A-Fa-f]"

    /** In this order: each rule sees the output of the one before it. */
    private val SCRUBBERS: List<Pair<Regex, String>> = listOf(
        Regex("https?://\\S+") to "[url]",
        Regex("sha256:$HEX{8,}") to "[pin]",
        Regex("$BOUNDARY$HEX{2}(?:[:-]$HEX{2}){5}$BOUNDARY") to "[mac]",
        Regex("$BOUNDARY[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?::[0-9]{1,5})?$BOUNDARY") to "[ip]",
        Regex("$BOUNDARY(?:$HEX{1,4}:){5,7}$HEX{1,4}$BOUNDARY") to "[ip]",
        Regex("$BOUNDARY$HEX{1,4}(?::$HEX{1,4}){0,6}::(?:$HEX{1,4}(?::$HEX{1,4}){0,6})?") to "[ip]",
        Regex("(?:/[A-Za-z0-9_.\\-]+){2,}") to "[path]",
        Regex("$BOUNDARY(?=[A-Za-z0-9_\\-]*[0-9])[A-Za-z0-9_\\-]{24,}$BOUNDARY") to "[token]",
    )
    private val LINE_BREAKS = Regex("[\\t\\r\\n\\u000c\\u000b]+")
    private val NON_PRINTABLE = Regex("[^\\u0020-\\u007e]")
    private val SPACES = Regex(" {2,}")

    /**
     * Printable ASCII with addresses, pins, tokens and paths replaced, then bounded: control characters
     * become a space, everything outside printable ASCII is dropped, runs of spaces collapse, the rules run
     * in [SCRUBBERS] order, and only then is the text cut to [limit], so a token is never cut into a
     * fragment that escapes the rule. Null for a non-string or when nothing is left.
     */
    internal fun scrubText(value: String?, limit: Int): String? {
        if (value == null) return null
        val capped = if (value.codePointCount(0, value.length) > TEXT_PRECAP) {
            value.substring(0, value.offsetByCodePoints(0, TEXT_PRECAP))
        } else value
        var text = LINE_BREAKS.replace(capped, " ")
        text = NON_PRINTABLE.replace(text, "")
        text = SPACES.replace(text, " ").trim(' ')
        for ((pattern, marker) in SCRUBBERS) text = pattern.replace(text) { marker }
        text = text.take(limit).trim(' ')
        return text.ifEmpty { null }
    }

    // --- identifier and code patterns, always matched whole ---------------------------------------------------

    private val TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")
    private val CODE = Regex("[a-z][a-z0-9_]{0,63}")
    private val METHOD = Regex("[a-z][a-z0-9_]{0,31}")
    private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,23}")
    private val CONTRACT = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,63}")
    private val CONNECTOR = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,23}")
    private val CARD = Regex("drm:(card[0-9]{1,3})")
    private val VENDOR = Regex("[A-Za-z0-9]{1,8}")
    private val RESOLUTION = Regex("[0-9]{3,5}x[0-9]{3,5}")
    private val RESTORE_STATE = Regex("[a-z]{1,16}")
    private val STAMP = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.[0-9]{1,6})?Z")
    /** The reference reads the day with a PREFIX match (anything may follow the `T`); this is the one deliberate prefix rule. */
    private val DAY = Regex("^([0-9]{4}-[0-9]{2}-[0-9]{2})T")
    private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    private val TEMPERATURE_SOURCES = setOf("steam", "hwmon", "unavailable")
    private val SUNSHINE_STATES = setOf("disabled", "unavailable", "unknown", "running", "stopped", "restarting")
    private val TARGET_SCALARS = listOf(
        "output_id", "mode_id", "generation", "action", "restart", "automatic", "connector", "output_key",
        "target_output_key", "baseline_output_key", "target",
    )
    private val TARGET_LISTS = listOf("connectors", "output_keys")

    // --- composing ---------------------------------------------------------------------------------------------

    fun compose(readings: DiagnosticReadings): JsonValue.ObjectValue =
        compose(readings.status?.body, readings.outputs?.body, readings.linkFacts, readings.journal)

    /**
     * [statusBody] and [outputsBody] are the response bodies of `GET /v1/status` and `GET /v1/display/outputs`
     * as the app decoded them; only the facts named here are read from them.
     */
    fun compose(statusBody: JsonValue?, outputsBody: JsonValue?, linkFacts: LinkFacts?, journal: HostJournal?): JsonValue.ObjectValue {
        val host = guarded { host(statusBody) }
        val outputs = guarded { outputs(outputsBody) }
        val linkSection = guarded { link(linkFacts) }
        val records = guarded { journalRecords(journal) }
        return try {
            bound(host, outputs, linkSection, records)
        } catch (_: Exception) {
            report(null, null, null, null)
        }
    }

    /** The compact JSON of [compose], as the export carries it. */
    fun encode(report: JsonValue.ObjectValue): ByteArray = BoundedJson.encode(report)

    private fun report(host: JsonValue?, outputs: JsonValue?, link: JsonValue?, journal: JsonValue?) = JsonValue.ObjectValue(
        linkedMapOf(
            "format" to str(FORMAT),
            "host" to (host ?: JsonValue.NullValue),
            "outputs" to (outputs ?: JsonValue.NullValue),
            "link" to (link ?: JsonValue.NullValue),
            "journal" to (journal ?: JsonValue.NullValue),
        ),
    )

    /**
     * If the compact JSON still exceeds [MAX_REPORT_BYTES], journal records are dropped from the oldest end
     * (the host sends newest first, so the last one) until it fits, and `truncated` is set.
     */
    private fun bound(host: JsonValue?, outputs: JsonValue?, link: JsonValue?, records: List<JsonValue>?): JsonValue.ObjectValue {
        fun build(kept: Int, truncated: Boolean): JsonValue.ObjectValue {
            val journal = records?.let {
                obj("count" to num(it.size.toLong()), "truncated" to JsonValue.BooleanValue(truncated), "records" to JsonValue.ArrayValue(it.take(kept)))
            }
            return report(host, outputs, link, journal)
        }
        var kept = minOf(records?.size ?: 0, MAX_JOURNAL)
        var truncated = (records?.size ?: 0) > MAX_JOURNAL
        var built = build(kept, truncated)
        while (records != null && kept > 0 && BoundedJson.encode(built).size > MAX_REPORT_BYTES) {
            kept--
            truncated = true
            built = build(kept, truncated)
        }
        return built
    }

    private inline fun <T> guarded(section: () -> T?): T? = try {
        section()
    } catch (_: Exception) {
        null // a diagnostic must never be the thing that fails
    }

    // --- host ----------------------------------------------------------------------------------------------------

    private fun host(statusBody: JsonValue?): JsonValue? {
        val check = (statusBody as? JsonValue.ObjectValue)?.get("self_check") as? JsonValue.ObjectValue ?: return null
        val certificate = check.section("certificate")
        val listener = check.section("listener")
        val bridge = check.section("bridge")
        val provider = check.section("provider")
        val versions = check.section("versions")
        val source = check.section("temperature")["source"]
        val state = provider["state"]
        // The reference tests `source in {...}`, which raises for an unhashable value and so nulls the whole section.
        if (source.isContainer() || state.isContainer()) return null
        val methods = (bridge["methods"] as? JsonValue.ArrayValue)?.values.orEmpty()
            .mapNotNull { (it as? JsonValue.StringValue)?.value?.takeIf(METHOD::matches) }
            .take(MAX_METHODS)
        return obj(
            "plugin_version" to optStr(pattern(versions["plugin"], VERSION)),
            "protocol_version" to optNum(integer(versions["protocol"], 1, 99)),
            "certificate" to obj(
                "ready" to flag(certificate["ready"]),
                "valid_from" to optStr(day(certificate["not_before"])),
                "valid_until" to optStr(day(certificate["not_after"])),
            ),
            "listener" to obj("bound" to flag(listener["bound"]), "port" to optNum(integer(listener["port"], 1, 65535))),
            "bridge" to obj("ready" to flag(bridge["ready"]), "methods" to JsonValue.ArrayValue(methods.map(::str))),
            "temperature_source" to optStr((source as? JsonValue.StringValue)?.value?.takeIf { it in TEMPERATURE_SOURCES }),
            "sunshine" to obj(
                "monitor_enabled" to flag(provider["monitor_enabled"]),
                "state" to optStr((state as? JsonValue.StringValue)?.value?.takeIf { it in SUNSHINE_STATES }),
                "stale" to flag(provider["stale"]),
                "contract_version" to optStr(pattern(provider["contract_version"], CONTRACT)),
            ),
        )
    }

    private fun JsonValue.ObjectValue.section(name: String): JsonValue.ObjectValue =
        this[name] as? JsonValue.ObjectValue ?: EMPTY

    private val EMPTY = JsonValue.ObjectValue(emptyMap())

    private fun JsonValue?.isContainer() = this is JsonValue.ObjectValue || this is JsonValue.ArrayValue

    private fun day(value: JsonValue?): String? =
        (value as? JsonValue.StringValue)?.value?.let { DAY.find(it)?.groupValues?.get(1) }

    // --- outputs ---------------------------------------------------------------------------------------------------

    private fun outputs(body: JsonValue?): JsonValue? {
        val fields = body as? JsonValue.ObjectValue ?: return null
        val records = ArrayList<JsonValue>()
        for (raw in (fields["outputs"] as? JsonValue.ArrayValue)?.values.orEmpty()) {
            val item = raw as? JsonValue.ObjectValue ?: EMPTY
            val id = pattern(item["id"], TOKEN)?.takeIf { it.length <= 16 } ?: continue
            val modes = (item["modes"] as? JsonValue.ArrayValue)?.values.orEmpty().filterIsInstance<JsonValue.ObjectValue>()
            val texts = modes.mapNotNull(::modeTextOf)
            val currentId = item["current_mode_id"]
            val current = modes.firstOrNull { it["id"] is JsonValue.StringValue && it["id"] == currentId }?.let(::modeTextOf)
            records += obj(
                "id" to str(id),
                "name" to optStr(scrubText(text(item["name"]), 32)),
                "description" to optStr(scrubText(text(item["description"]), 48)),
                "internal" to flag(item["is_internal"]),
                "current_mode" to optStr(current),
                "mode_count" to num(texts.size.toLong()),
                "modes" to JsonValue.ArrayValue(texts.take(MAX_MODES).map(::str)),
            )
        }
        val profiles = (fields["profiles"] as? JsonValue.ArrayValue)?.values.orEmpty()
        return obj(
            "available" to flag(fields["available"]),
            "count" to num(records.size.toLong()),
            "outputs" to JsonValue.ArrayValue(records.take(MAX_OUTPUTS)),
            "saved_profiles" to num(minOf(profiles.size, MAX_PROFILES).toLong()),
            "preview_active" to JsonValue.BooleanValue(fields["preview"] is JsonValue.ObjectValue),
        )
    }

    private fun modeTextOf(mode: JsonValue.ObjectValue): String? =
        modeText(integer(mode["width"], 1, 16384), integer(mode["height"], 1, 16384), hertz(mode["refresh_hz"]))

    private fun modeText(width: Long?, height: Long?, hertz: String?): String? {
        if (width == null || height == null) return null
        return "${width}x$height" + (hertz?.let { "@$it" } ?: "")
    }

    /** A finite refresh rate above 0 and up to 1000 as two decimals with trailing zeros removed: 59.94, 60, 59.9. */
    private fun hertz(value: JsonValue?): String? {
        val lexical = (value as? JsonValue.NumberValue)?.lexical?.takeIf(NUMBER::matches) ?: return null
        return hertz(lexical.toDoubleOrNull())
    }

    private fun hertz(value: Double?): String? {
        if (value == null || !value.isFinite() || !(value > 0.0 && value <= 1000.0)) return null
        return BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString().trimEnd('0').trimEnd('.').ifEmpty { null }
    }

    // --- link --------------------------------------------------------------------------------------------------------

    private fun link(facts: LinkFacts?): JsonValue? {
        facts ?: return null
        val reason = optStr(scrubText(facts.reason, 96))
        if (!facts.available) return obj("available" to JsonValue.BooleanValue(false), "reason" to reason, "connectors" to JsonValue.ArrayValue(emptyList()))
        val connectors = facts.connectors.mapNotNull(::connector).take(MAX_CONNECTORS)
        return obj("available" to JsonValue.BooleanValue(true), "reason" to reason, "connectors" to JsonValue.ArrayValue(connectors))
    }

    private fun connector(item: LinkConnector): JsonValue? {
        val name = item.connector.takeIf(CONNECTOR::matches) ?: return null
        val resolutions = item.resolutions.filter(RESOLUTION::matches).distinct()
        return obj(
            "connector" to str(name),
            "card" to optStr(CARD.matchEntire(item.gpuId)?.groupValues?.get(1)),
            "connected" to JsonValue.BooleanValue(item.connected),
            "internal" to JsonValue.BooleanValue(item.isInternal),
            "vendor" to optStr(item.monitorVendor?.takeIf(VENDOR::matches)),
            "model" to optNum(item.monitorProductId?.toLong()?.takeIf { it in 0..65535 }),
            "output_id" to optStr(item.steamOutputId?.takeIf { TOKEN.matches(it) && it.length <= 16 }),
            "resolution_count" to num(resolutions.size.toLong()),
            "resolutions" to JsonValue.ArrayValue(resolutions.take(MAX_RESOLUTIONS).map(::str)),
        )
    }

    // --- journal -----------------------------------------------------------------------------------------------------

    /** Every valid record, newest first as the host sent them; [bound] counts, caps and cuts them. */
    private fun journalRecords(journal: HostJournal?): List<JsonValue>? =
        journal?.records?.mapNotNull(::journalRecord)

    private fun journalRecord(record: HostJournalRecord): JsonValue? {
        // A record needs a valid id, kind and state even though its id is never copied.
        if (!TOKEN.matches(record.id) || !TOKEN.matches(record.kind) || !TOKEN.matches(record.state)) return null
        if (record.kind.length > 64 || record.state.length > 32) return null
        val stamp = record.updatedAt?.let { STAMP.matchEntire(it) }
        return obj(
            "updated_at" to optStr(stamp?.let { "${it.groupValues[1]}Z" }),
            "kind" to str(record.kind),
            "state" to str(record.state),
            "outcome" to optStr(record.outcome?.takeIf(CODE::matches)),
            "restore_state" to optStr(record.restoreState?.takeIf(RESTORE_STATE::matches)),
            "restored" to (record.restored?.let { JsonValue.BooleanValue(it) } ?: JsonValue.NullValue),
            "reason" to optStr(scrubText(record.reason, 160)),
            "target" to (target(record.target) ?: JsonValue.NullValue),
        )
    }

    /** Only the named keys, in this order: scalars, then string lists, then `mode`. Anything else is not copied. */
    private fun target(value: Map<String, JournalValue>): JsonValue? {
        val out = LinkedHashMap<String, JsonValue>()
        for (key in TARGET_SCALARS) {
            when (val item = value[key]) {
                is JournalValue.Flag -> out[key] = JsonValue.BooleanValue(item.value)
                is JournalValue.Whole -> if (Math.abs(item.value) < MAX_TARGET_INT) out[key] = num(item.value)
                is JournalValue.Text -> scrubText(item.value, 48)?.let { out[key] = str(it) }
                else -> Unit // a decimal, a list or a group is not a scalar fact
            }
        }
        for (key in TARGET_LISTS) {
            val list = value[key] as? JournalValue.TextList ?: continue
            val texts = list.values.take(4).map { scrubText(it, 32) }
            if (texts.isNotEmpty() && texts.all { it != null }) out[key] = JsonValue.ArrayValue(texts.map { str(it!!) })
        }
        (value["mode"] as? JournalValue.Group)?.let { group ->
            val width = (group.entries["width"] as? JournalValue.Whole)?.value?.takeIf { it in 1..16384 }
            val height = (group.entries["height"] as? JournalValue.Whole)?.value?.takeIf { it in 1..16384 }
            val rate = when (val raw = group.entries["refresh_hz"]) {
                is JournalValue.Real -> raw.value
                is JournalValue.Whole -> raw.value.toDouble()
                else -> null
            }
            modeText(width, height, hertz(rate))?.let { out["mode"] = str(it) }
        }
        return if (out.isEmpty()) null else JsonValue.ObjectValue(out)
    }

    // --- small builders and checks -------------------------------------------------------------------------------------

    private fun obj(vararg entries: Pair<String, JsonValue>) = JsonValue.ObjectValue(linkedMapOf(*entries))
    private fun str(value: String) = JsonValue.StringValue(value)
    private fun optStr(value: String?): JsonValue = value?.let(::str) ?: JsonValue.NullValue
    private fun num(value: Long) = JsonValue.NumberValue(value.toString())
    private fun optNum(value: Long?): JsonValue = value?.let(::num) ?: JsonValue.NullValue
    private fun flag(value: JsonValue?): JsonValue = JsonValue.BooleanValue(value == JsonValue.BooleanValue(true))
    private fun text(value: JsonValue?): String? = (value as? JsonValue.StringValue)?.value
    private fun pattern(value: JsonValue?, pattern: Regex): String? = text(value)?.takeIf(pattern::matches)

    /** A JSON integer (not a decimal, an exponent or a string) within [low]..[high]. */
    private fun integer(value: JsonValue?, low: Long, high: Long): Long? {
        val lexical = (value as? JsonValue.NumberValue)?.lexical ?: return null
        if (!INTEGER.matches(lexical)) return null
        return lexical.toLongOrNull()?.takeIf { it in low..high }
    }
}
