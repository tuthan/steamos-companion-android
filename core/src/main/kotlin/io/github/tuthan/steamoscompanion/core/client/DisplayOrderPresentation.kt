package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.DisplayOrderSummary

data class DisplayOrderRow(val key: String, val label: String, val connector: String?, val connected: Boolean, val active: Boolean?)

/** Read-only view of the host's Gaming Mode display order, resolved from host-issued keys. */
data class DisplayOrderView(
    val current: List<DisplayOrderRow>,
    /** Empty means the host has no saved priority and picks the order automatically. */
    val saved: List<DisplayOrderRow>,
    val restartRequired: Boolean,
)

object DisplayOrderPresentation {
    /** The tab is offered when the host exposes ordering at all; unsupported hosts hide it. */
    fun offered(order: DisplayOrderSummary?): Boolean = order != null && !order.unsupported

    /** Connected outputs only, saved priority first, then the host inventory order. */
    fun editableKeys(order: DisplayOrderSummary): List<String> {
        val connected = order.outputs.filter { it.connected }.map { it.key }.toSet()
        val inventory = order.outputKeys.filter { it in connected }
        val saved = order.savedOutputKeys.filter { it in connected }
        return (saved + inventory).distinct()
    }

    /** Changes when the host's inventory generation, connected set, or connector labels change. */
    fun topology(order: DisplayOrderSummary): String =
        order.generation.toString() + order.outputs.sortedBy { it.key }
            .joinToString(prefix = "|", separator = "|") { "${it.key}:${it.connected}:${it.connector.orEmpty()}" }

    /** Whether the host currently allows changing the order at all. */
    fun editable(order: DisplayOrderSummary): Boolean =
        order.available && !order.unsupported && !order.stale && !order.ambiguous && order.generation != null

    fun row(order: DisplayOrderSummary, key: String): DisplayOrderRow {
        val output = order.outputs.firstOrNull { it.key == key }
        val label = output?.displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: output?.connector ?: key.substringAfterLast(':')
        return DisplayOrderRow(key, label, output?.connector, output?.connected ?: false, output?.active)
    }

    /** Saved keys the host cannot currently place because the display is not connected. */
    fun disconnectedSavedCount(order: DisplayOrderSummary): Int {
        val connected = order.outputs.filter { it.connected }.map { it.key }.toSet()
        return order.savedOutputKeys.count { it !in connected }
    }

    fun project(order: DisplayOrderSummary): DisplayOrderView = DisplayOrderView(
        order.outputKeys.map { row(order, it) }, order.savedOutputKeys.map { row(order, it) }, order.restartRequired,
    )
}
