package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue

/**
 * Finishes accepted power requests from what the device does next. The host acknowledges a request and
 * Steam then carries it out, so the device is expected to drop off: for suspend and shutdown that drop is
 * the success. A restart is finished only when the device is back, which is when its boot ID has changed
 * or, if the boot ID at request time is unknown, when it returned after being seen offline.
 *
 * Only requests the host acknowledged (it returned an operation ID) are settled; a request whose reply
 * was lost stays unresolved for the user to review. Nothing is ever resent.
 */
class PowerSettlement(
    private val repository: EncryptedStateRepository,
    private val journal: OperationJournal,
) {
    private val lock = Any()
    private val sawOffline = HashSet<Long>()

    /** Returns true when a journal entry was settled. Call with each status observation for [host]. */
    fun observe(host: ActiveHost, status: StatusPollState): Boolean {
        val source = StatusObservationSource.from(host)
        if (status.source != null && status.source != source) return false
        val state = (repository.load() as? StateLoadResult.Ready)?.state ?: return false
        val active = state.activeHost ?: return false
        if (StatusObservationSource.from(active) != source) return false
        val pending = state.journal.filter {
            it.path == ApiRoute.POWER.path && it.belongsTo(active) && it.operationId != null && it.phase in ACKNOWLEDGED
        }
        if (pending.isEmpty()) return false
        var changed = false
        for (entry in pending) {
            val action = action(entry) ?: continue
            val summary = when {
                status.unreachable -> {
                    synchronized(lock) { sawOffline += entry.localGeneration }
                    if (action == "restart") null else "Device went offline after accepting the request"
                }
                status.lastRead == null -> null
                else -> {
                    val bootId = status.lastRead.status.bootId
                    val seenOffline = synchronized(lock) { entry.localGeneration in sawOffline }
                    when {
                        entry.hostBootId != null && bootId != entry.hostBootId -> "Device restarted and is back online"
                        entry.hostBootId == null && seenOffline && action == "restart" -> "Device is back online after restarting"
                        else -> null
                    }
                }
            } ?: continue
            if (journal.settleAcknowledgedPower(active, entry.localGeneration, summary) == JournalUpdateResult.Saved) {
                synchronized(lock) { sawOffline -= entry.localGeneration }
                changed = true
            }
        }
        return changed
    }

    private fun action(entry: JournalEntry): String? = try {
        (BoundedJson.parseObject(entry.bodyBytes())["action"] as? JsonValue.StringValue)?.value
    } catch (_: Exception) { null }

    private companion object {
        val ACKNOWLEDGED = setOf(JournalPhase.ACCEPTED, JournalPhase.DISPATCHED, JournalPhase.OBSERVED_RETURN)
    }
}
