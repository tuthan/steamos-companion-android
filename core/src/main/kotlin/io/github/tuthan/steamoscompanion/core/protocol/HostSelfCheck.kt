package io.github.tuthan.steamoscompanion.core.protocol

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The host's own account of its health, from the optional `self_check` object of the authenticated
 * status response (Decky `protocol/schema.json` `$defs/self_check`). It is live status data only:
 * never persisted, journaled, or exported.
 *
 * Every section is nullable because a host may omit the block, a newer host may reshape a section,
 * and a malformed section must degrade to "not reported" instead of failing the status read.
 */
data class HostSelfCheck(
    val certificate: HostCertificateCheck?,
    val listener: HostListenerCheck?,
    val bridge: HostBridgeCheck?,
    val temperature: HostTemperatureCheck?,
    val provider: HostProviderCheck?,
    val versions: HostVersionsCheck?,
)

data class HostCertificateCheck(
    val ready: Boolean,
    /** Last eight lowercase hex digits of the certificate fingerprint, as pairing shows it. */
    val fingerprintShort: String?,
    val notBefore: Instant?,
    val notAfter: Instant?,
)

data class HostListenerCheck(val bound: Boolean, val address: String?, val port: Int?)

data class HostBridgeCheck(
    val ready: Boolean,
    /** At most eight lowercase method names, in host order. */
    val methods: List<String>,
)

enum class TemperatureSource { STEAM, HWMON, UNAVAILABLE }

data class HostTemperatureCheck(val source: WireValue<TemperatureSource>)

data class HostProviderCheck(
    val monitorEnabled: Boolean,
    val state: WireValue<SunshineState>,
    /** The host's own observation is old; a stale "running" is not a confirmed state. */
    val stale: Boolean,
    val contractVersion: String?,
)

data class HostVersionsCheck(
    /** Any integer the host sent; null when it sent something that is not an integer. */
    val protocol: Int?,
    /** Null when the host could not read its plugin version (the literal "unknown") or sent nothing usable. */
    val plugin: String?,
)

/**
 * Tolerant decoder for the `self_check` block. The block is optional diagnosis, so unlike the rest of
 * the status response nothing here may throw: an absent, non-object or entirely unusable block is
 * null; a malformed, oversize or wrongly typed section is null; a bad optional field is null; unknown
 * keys and unknown enum values are ignored or kept as [WireValue.Unknown].
 */
internal object HostSelfCheckDecoder {
    const val MAX_METHODS = 8
    private const val MAX_ADDRESS = 64
    private const val MAX_CONTRACT_VERSION = 64
    private const val MAX_PLUGIN_VERSION = 64
    private const val MAX_ENUM = 64
    private const val MAX_METHOD_NAME = 32
    private const val UNKNOWN_PLUGIN = "unknown"

    private val FINGERPRINT_SHORT = Regex("[0-9a-f]{8}")
    private val TIMESTAMP = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
    private val METHOD = Regex("[a-z][a-z0-9_]{0,${MAX_METHOD_NAME - 1}}")

    fun decode(value: JsonValue?): HostSelfCheck? {
        val block = value as? JsonValue.ObjectValue ?: return null
        val decoded = try {
            HostSelfCheck(
                certificate = block.section("certificate", ::certificate),
                listener = block.section("listener", ::listener),
                bridge = block.section("bridge", ::bridge),
                temperature = block.section("temperature", ::temperature),
                provider = block.section("provider", ::provider),
                versions = block.section("versions", ::versions),
            )
        } catch (_: RuntimeException) {
            // Belt and braces: diagnosis must never be the reason a status read fails.
            return null
        }
        return decoded.takeUnless {
            it.certificate == null && it.listener == null && it.bridge == null &&
                it.temperature == null && it.provider == null && it.versions == null
        }
    }

    private fun <T> JsonValue.ObjectValue.section(name: String, parse: (JsonValue.ObjectValue) -> T?): T? =
        (this[name] as? JsonValue.ObjectValue)?.let(parse)

    private fun certificate(section: JsonValue.ObjectValue): HostCertificateCheck? {
        val ready = section["ready"].booleanOrNull() ?: return null
        return HostCertificateCheck(
            ready = ready,
            fingerprintShort = section["fingerprint_short"].textOrNull(8)?.takeIf(FINGERPRINT_SHORT::matches),
            notBefore = section["not_before"].timestampOrNull(),
            notAfter = section["not_after"].timestampOrNull(),
        )
    }

    private fun listener(section: JsonValue.ObjectValue): HostListenerCheck? {
        val bound = section["bound"].booleanOrNull() ?: return null
        return HostListenerCheck(
            bound = bound,
            address = section["address"].textOrNull(MAX_ADDRESS),
            port = section["port"].intOrNull()?.takeIf { it in 1..65535 },
        )
    }

    private fun bridge(section: JsonValue.ObjectValue): HostBridgeCheck? {
        val ready = section["ready"].booleanOrNull() ?: return null
        val names = (section["methods"] as? JsonValue.ArrayValue)?.values.orEmpty()
            .mapNotNull { it.textOrNull(MAX_METHOD_NAME)?.takeIf(METHOD::matches) }
            .distinct()
            .take(MAX_METHODS)
        return HostBridgeCheck(ready, names)
    }

    private fun temperature(section: JsonValue.ObjectValue): HostTemperatureCheck? {
        val raw = section["source"].textOrNull(MAX_ENUM) ?: return null
        return HostTemperatureCheck(
            when (raw) {
                "steam" -> WireValue.Known(TemperatureSource.STEAM)
                "hwmon" -> WireValue.Known(TemperatureSource.HWMON)
                "unavailable" -> WireValue.Known(TemperatureSource.UNAVAILABLE)
                else -> WireValue.Unknown(raw)
            },
        )
    }

    private fun provider(section: JsonValue.ObjectValue): HostProviderCheck? {
        val enabled = section["monitor_enabled"].booleanOrNull() ?: return null
        val raw = section["state"].textOrNull(MAX_ENUM) ?: return null
        val stale = section["stale"].booleanOrNull() ?: return null
        return HostProviderCheck(
            monitorEnabled = enabled,
            state = when (raw) {
                "disabled" -> WireValue.Known(SunshineState.DISABLED)
                "unavailable" -> WireValue.Known(SunshineState.UNAVAILABLE)
                "unknown" -> WireValue.Known(SunshineState.UNKNOWN)
                "running" -> WireValue.Known(SunshineState.RUNNING)
                "stopped" -> WireValue.Known(SunshineState.STOPPED)
                "restarting" -> WireValue.Known(SunshineState.RESTARTING)
                else -> WireValue.Unknown(raw)
            },
            stale = stale,
            contractVersion = section["contract_version"].textOrNull(MAX_CONTRACT_VERSION),
        )
    }

    private fun versions(section: JsonValue.ObjectValue): HostVersionsCheck = HostVersionsCheck(
        protocol = section["protocol"].intOrNull(),
        plugin = section["plugin"].textOrNull(MAX_PLUGIN_VERSION)?.takeUnless { it == UNKNOWN_PLUGIN },
    )

    private fun JsonValue?.booleanOrNull(): Boolean? = (this as? JsonValue.BooleanValue)?.value

    private fun JsonValue?.intOrNull(): Int? = (this as? JsonValue.NumberValue)?.lexical?.toIntOrNull()

    /** A non-blank string within [maxLength] with no control characters; anything else is "not reported". */
    private fun JsonValue?.textOrNull(maxLength: Int): String? {
        val text = (this as? JsonValue.StringValue)?.value ?: return null
        if (text.isBlank() || text.length > maxLength || text.any { it.isISOControl() }) return null
        return text
    }

    private fun JsonValue?.timestampOrNull(): Instant? {
        val text = (this as? JsonValue.StringValue)?.value ?: return null
        if (!TIMESTAMP.matches(text)) return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
