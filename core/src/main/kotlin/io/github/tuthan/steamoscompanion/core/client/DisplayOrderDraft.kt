package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.DisplayOrderSummary

/**
 * The owner's editable copy of the host's Gaming Mode display order. Only connected outputs are
 * editable, ordered by the host's saved priority first and its inventory order after.
 *
 * Background reads never reorder a dirty draft. A topology change (generation, connected set or
 * connector) marks the draft stale, and only [refreshed] accepts the new host reading.
 */
data class DisplayOrderDraft(
    val baseKeys: List<String>,
    val keys: List<String>,
    val topology: String,
    val stale: Boolean = false,
    /** Keys submitted successfully; host readback must show them before the draft follows the host again. */
    val pendingKeys: List<String>? = null,
) {
    val dirty: Boolean get() = keys != baseKeys

    fun canMove(key: String, delta: Int): Boolean {
        val index = keys.indexOf(key)
        return !stale && pendingKeys == null && index >= 0 && index + delta in keys.indices
    }

    fun move(key: String, delta: Int): DisplayOrderDraft {
        if (!canMove(key, delta)) return this
        val next = keys.toMutableList()
        val index = next.indexOf(key)
        next.add(index + delta, next.removeAt(index))
        return copy(keys = next)
    }

    /** The host accepted [submitted]; hold the draft steady until readback catches up. */
    fun saved(submitted: List<String>): DisplayOrderDraft = copy(baseKeys = submitted, keys = submitted, pendingKeys = submitted)

    /** Explicit Refresh: accept the host's current reading. */
    fun refreshed(order: DisplayOrderSummary): DisplayOrderDraft = from(order)

    /** Background poll. */
    fun observed(order: DisplayOrderSummary): DisplayOrderDraft {
        val latest = DisplayOrderPresentation.editableKeys(order)
        val topologyNow = DisplayOrderPresentation.topology(order)
        val hostBlocked = order.stale || order.ambiguous || order.unsupported
        if (topologyNow != topology) return copy(stale = true)
        if (pendingKeys != null) {
            // Moves are disabled while pending, so the draft equals the submitted keys here.
            return if (latest == pendingKeys || order.savedOutputKeys == pendingKeys) {
                copy(baseKeys = latest, keys = latest, pendingKeys = null, stale = stale || hostBlocked)
            } else this
        }
        if (dirty) return if (hostBlocked) copy(stale = true) else this
        return copy(baseKeys = latest, keys = latest, stale = stale || hostBlocked)
    }

    companion object {
        fun from(order: DisplayOrderSummary): DisplayOrderDraft {
            val keys = DisplayOrderPresentation.editableKeys(order)
            return DisplayOrderDraft(keys, keys, DisplayOrderPresentation.topology(order),
                stale = order.stale || order.ambiguous || order.unsupported)
        }
    }
}
