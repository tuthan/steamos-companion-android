package io.github.tuthan.steamoscompanion.core.protocol

/** The three scopes a host can grant a paired client, in the host's own order (`protocol/schema.json` `$defs/credential`). */
object HostScopes {
    const val STATUS_READ = "status.read"
    const val POWER_CONTROL = "power.control"
    const val DISPLAY_CONTROL = "display.control"
    val GRANTABLE: List<String> = listOf(STATUS_READ, POWER_CONTROL, DISPLAY_CONTROL)
}

/**
 * What the host says this credential may do right now, from the optional `credential` object of the
 * authenticated status response. The owner can change a pairing's scopes after the fact, so this is the
 * host's current word, not what was granted at pairing.
 *
 * Only the grantable scopes are kept, each once, in the order the host sent them; anything else is dropped,
 * so a hostile or newer host cannot widen what this client believes. A missing, non-object or non-array
 * block is null, "the host did not say" (an older host), which must never be read as "no access"; an empty
 * array is a real answer and decodes to an empty list. Nothing here may throw: the block is advisory and
 * never the reason a status read fails.
 */
internal object HostCredentialDecoder {
    fun decode(value: JsonValue?): List<String>? {
        val scopes = (value as? JsonValue.ObjectValue)?.get("scopes") as? JsonValue.ArrayValue ?: return null
        return scopes.values
            .mapNotNull { (it as? JsonValue.StringValue)?.value?.takeIf(HostScopes.GRANTABLE::contains) }
            .distinct()
    }
}
