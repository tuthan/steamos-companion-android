package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import java.security.MessageDigest

/** Local display names contain no credential and are keyed by the paired host/client identity. */
class LocalAliasStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("device_aliases", Context.MODE_PRIVATE)

    fun get(host: ActiveHost): String? = preferences.getString(key(host), null)

    fun set(host: ActiveHost, alias: String?) {
        val normalized = alias?.trim()?.takeIf { it.isNotEmpty() }
        require(normalized == null || normalized.length <= 64 && normalized.none(Char::isISOControl)) {
            "Device name must be at most 64 printable characters"
        }
        preferences.edit().apply {
            if (normalized == null) remove(key(host)) else putString(key(host), normalized)
        }.apply()
    }

    fun forget(host: ActiveHost) {
        preferences.edit().remove(key(host)).apply()
    }

    private fun key(host: ActiveHost): String {
        val bytes = "${host.hostId}\u0000${host.clientId}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
