package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.HostScopes

/** The "Access on this host" line: [role] then [summary], and a [note] unless the pairing holds every scope. */
data class HostAccessView(val role: String, val summary: String, val note: String?) {
    val line: String get() = "$role$SEPARATOR$summary"

    private companion object { const val SEPARATOR = " · " }
}

/**
 * What a pairing may do, in the same words as the Decky host's own Access line (`host/frontend/index.js`,
 * `accessRole`, `accessSummary` and `accessNote`), so the owner reads the same thing on both devices. The owner
 * of the host decides these scopes and can change them after pairing; this only words what is held. [scopes] is
 * the stored pairing's current value, which every status read replaces with what the host reports.
 */
object HostAccessPresentation {
    const val OWNER_NOTE = "The owner of the host chose this and can change it there."

    /** A host refusal (403 `forbidden`) of a request this client sent; the stored pairing is not touched by it. */
    const val FORBIDDEN_MESSAGE = "The host owner has not allowed this action for this pairing."

    private val LABELS = listOf(
        HostScopes.STATUS_READ to "Status",
        HostScopes.POWER_CONTROL to "Power",
        HostScopes.DISPLAY_CONTROL to "Display",
    )

    fun view(scopes: Collection<String>): HostAccessView {
        val held = LABELS.filter { (scope, _) -> scope in scopes }
        val role = when {
            held.size == LABELS.size -> "Full access"
            held.isEmpty() -> "No access"
            held.size == 1 && held.single().first == HostScopes.STATUS_READ -> "View only"
            else -> "Custom"
        }
        val summary = if (held.isEmpty()) "No permissions" else held.joinToString(" · ") { it.second }
        return HostAccessView(role, summary, OWNER_NOTE.takeIf { held.size != LABELS.size })
    }

    fun allows(scopes: Collection<String>, scope: String): Boolean = scope in scopes

    /** Why a control is disabled when the pairing lacks [scope]: only Power and Display are ever grantable controls. */
    fun denied(scope: String): String {
        val label = LABELS.firstOrNull { it.first == scope }?.second ?: scope
        return "The host owner has not allowed $label for this pairing."
    }

    /** The plain words for a finished request the host refused with 403 `forbidden`; null for any other outcome. */
    fun refusal(entry: JournalEntry): String? =
        FORBIDDEN_MESSAGE.takeIf { entry.phase == JournalPhase.FAILED && entry.rejectionCode == JournalRejectionCode.FORBIDDEN }
}
