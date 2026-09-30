package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.wake.WakeRelay
import java.security.MessageDigest

/**
 * The wake relay is a LAN-side IP address, not a credential, so it lives beside the local aliases
 * rather than in the encrypted state schema. It is keyed by the paired host/client identity.
 */
class WakeRelayStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("wake_relays", Context.MODE_PRIVATE)

    fun get(host: ActiveHost): WakeRelay? = preferences.getString(key(host), null)?.let(WakeRelay::parse)

    /** Returns false, leaving the saved relay unchanged, when [text] is not a usable relay address. */
    fun set(host: ActiveHost, text: String): Boolean {
        if (text.isBlank()) {
            preferences.edit().remove(key(host)).apply()
            return true
        }
        val relay = WakeRelay.parse(text) ?: return false
        preferences.edit().putString(key(host), relay.format()).apply()
        return true
    }

    private fun key(host: ActiveHost): String {
        val bytes = "${host.hostId}\u0000${host.clientId}".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
