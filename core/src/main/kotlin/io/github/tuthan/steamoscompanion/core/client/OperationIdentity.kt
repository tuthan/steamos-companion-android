package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import java.security.MessageDigest

/** Legacy journal entries lack credential provenance and cannot authorize a current action. */
internal fun JournalEntry.belongsTo(host: ActiveHost): Boolean {
    if (hostId != host.hostId || clientId != host.clientId || originalEndpoint != host.endpoint ||
        originalCertificatePin != host.certificatePin || originalPairedAtMillis != host.pairedAtMillis
    ) return false
    val expected = originalBearerSha256 ?: return false
    val actual = MessageDigest.getInstance("SHA-256").digest(host.bearerToken.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), actual.toByteArray(Charsets.US_ASCII))
}

/**
 * An unresolved entry blocks new mutations until the host settles it or the user marks it reviewed.
 * Some entries can never settle: no operation ID was returned, the host pruned the record (404), or a
 * legacy entry lacks credential provenance and cannot be looked up.
 */
internal val JournalEntry.blocksLane: Boolean get() = phase.unresolved && !acknowledged && !hostReturnedPower

/**
 * The host ends a power request at `observed_return` for good ("Steam returned; the physical transition
 * is not confirmed") and does not count it as an active operation, so its own mutation lane is free.
 * Holding this client's lane open for it locked display and order changes after every Suspend or Restart.
 * The Power card still reports the request and what the next status read observes.
 */
internal val JournalEntry.hostReturnedPower: Boolean
    get() = phase == JournalPhase.OBSERVED_RETURN && path == ApiRoute.POWER.path

/** A legacy entry may be from this credential; keep its unresolved mutation lane conservative. */
internal fun JournalEntry.mayConflictWith(host: ActiveHost): Boolean {
    if (hostId != host.hostId || clientId != host.clientId || originalEndpoint != host.endpoint ||
        originalCertificatePin != host.certificatePin
    ) return false
    return if (originalPairedAtMillis == null && originalBearerSha256 == null) true else belongsTo(host)
}

/** UI-safe questions about the exact credential's mutation lane; no bearer value is exposed. */
object OperationLanePresentation {
    fun hasUnresolved(host: ActiveHost, state: PersistentState): Boolean =
        state.journal.any { it.blocksLane && it.mayConflictWith(host) }

    fun hasUnresolvedOtherThanPreview(host: ActiveHost, state: PersistentState, previewId: String): Boolean =
        state.journal.any { entry ->
            entry.blocksLane && entry.mayConflictWith(host) &&
                !(entry.path == ApiRoute.DISPLAY_PREVIEW.path && entry.operationId == previewId && entry.belongsTo(host))
        }

    fun ownsPreview(host: ActiveHost, state: PersistentState, previewId: String): Boolean =
        state.journal.any { it.path == ApiRoute.DISPLAY_PREVIEW.path && it.operationId == previewId && it.belongsTo(host) }
}

/** Operation kinds are fixed by the pinned route and the exact journaled body. */
internal fun JournalEntry.expectedOperationKind(): String? = when (path) {
    ApiRoute.PAIR_REVOKE_SELF.path -> "pair.revoke-self"
    ApiRoute.POWER.path -> {
        val action = (BoundedJson.parseObject(bodyBytes())["action"] as? JsonValue.StringValue)?.value
        action?.let { "power.$it" }
    }
    ApiRoute.DISPLAY_PREVIEW.path -> "display.preview"
    ApiRoute.DISPLAY_CONFIRM.path -> "display.confirm"
    ApiRoute.DISPLAY_RESTORE.path -> "display.restore"
    ApiRoute.DISPLAY_SAVE_CURRENT.path -> "display.save-current"
    ApiRoute.DISPLAY_ORDER_SET.path -> "display.order"
    ApiRoute.DISPLAY_ORDER_AUTOMATIC.path -> "display.order.reset"
    ApiRoute.SUNSHINE_RESTART.path -> "sunshine.restart"
    else -> null
}
