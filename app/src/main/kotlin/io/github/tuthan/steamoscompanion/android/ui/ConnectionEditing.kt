package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.ConnectionRepairResult
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StateInvariantCode
import io.github.tuthan.steamoscompanion.core.client.StateResetResult
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionPolicy
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint

/** What the Rename dialog accepts; the same limits `LocalAliasStore.set` enforces, applied while typing. */
internal object DeviceNameInput {
    const val MAX_LENGTH = 64

    /** Typing can never produce a name the store would refuse: no control characters, at most 64 characters. */
    fun sanitize(raw: String): String = raw.filterNot(Char::isISOControl).take(MAX_LENGTH)

    /** The alias to store, or null to clear it and fall back to the endpoint. */
    fun alias(raw: String): String? = sanitize(raw).trim().ifEmpty { null }
}

/** The Edit address dialog's two fields, validated the way the manual-address screen validates them. */
internal data class AddressDraft(val host: String, val port: String) {
    fun withHost(raw: String) = copy(host = raw.filterNot(Char::isWhitespace).take(MAX_HOST))
    fun withPort(raw: String) = copy(port = raw.filter(Char::isDigit).take(MAX_PORT_DIGITS))

    /** The `host:port` to verify, or null while the fields are not a valid endpoint. */
    val authority: String? get() {
        val number = port.toIntOrNull() ?: return null
        if (host.isBlank() || number !in 1..65535) return null
        val joined = if (host.contains(':')) "[$host]:$number" else "$host:$number"
        return try { HostEndpoint.parse(joined).authority } catch (_: IllegalArgumentException) { null }
    }

    companion object {
        private const val MAX_HOST = 253
        private const val MAX_PORT_DIGITS = 5

        fun from(endpoint: String): AddressDraft = try {
            val parsed = HostEndpoint.parse(endpoint)
            AddressDraft(parsed.host, parsed.port.toString())
        } catch (_: IllegalArgumentException) { AddressDraft("", HostEndpoint.DEFAULT_PORT.toString()) }
    }
}

/**
 * What Edit address and Find device again tell the owner. A repaired address is saved only after the device proves the
 * saved host ID and certificate pin, so every other outcome says plainly that the saved address was not changed.
 */
internal object ConnectionRepairMessages {
    fun forResult(result: ConnectionRepairResult): Pair<String, StatusTone> = when (result) {
        is ConnectionRepairResult.Saved -> "Address updated to ${result.host.endpoint}" to StatusTone.GOOD
        is ConnectionRepairResult.IdentityChanged ->
            "Device identity changed. The saved address was not changed. Review the device and pair again." to
                StatusTone.BLOCKED
        is ConnectionRepairResult.ProbeFailed ->
            if (result.reason == LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE) result.reason to StatusTone.WARNING
            else "No SteamOS Companion server answered at that address. The saved address was not changed." to
                StatusTone.WARNING
        is ConnectionRepairResult.StateFailed -> when (val saved = result.result) {
            is StateCommitResult.Rejected ->
                if (saved.code == StateInvariantCode.STALE_GENERATION || saved.code == StateInvariantCode.INVALID_IDENTITY) {
                    "Pairing changed while checking the address; check the selected device again." to StatusTone.WARNING
                } else "Could not save the new address; the current address remains." to StatusTone.WARNING
            else -> "Could not save the new address; the current address remains." to StatusTone.WARNING
        }
        is ConnectionRepairResult.NotFound ->
            if (result.qualifiedPermission) LocalNetworkPermissionPolicy.API_36_FAILURE_MESSAGE to StatusTone.WARNING
            else (if (result.partial) {
                "This device was not found. Some network addresses weren't searched; you can edit the address instead."
            } else "This device was not found on this network. Check that it is on, or edit the address.") to
                StatusTone.WARNING
    }
}

/** What Reset local pairing reports; it succeeds only when both the state file and its key are gone. */
internal fun resetLocalPairingMessage(result: StateResetResult): Pair<String, StatusTone> = when (result) {
    StateResetResult.Cleared -> "Local pairing reset. Pair again to continue." to StatusTone.GOOD
    is StateResetResult.Failed -> "Could not remove the saved pairing; try again." to StatusTone.WARNING
}
