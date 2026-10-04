package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.LinkConnector
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload

/**
 * A resolution the display link never listed. It is a warning, never a block: the mode may still work,
 * so the text must not call it invalid. [resolution] is the `"<width>x<height>"` that was not found.
 */
data class LinkAdvisoryNotice(val connector: LinkConnector, val resolution: String)

/**
 * The joined connector as facts, for the muted link line under the output: `connector · card · display
 * name · VENDOR model product`. [displayName] is null when it only repeats the connector; [vendor] and
 * [productId] are both present or both null. The app turns it into localized text.
 */
data class LinkLine(
    val connector: String,
    /** The GPU card, `drm:` stripped: "card0". */
    val card: String,
    val displayName: String?,
    val vendor: String?,
    val productId: Int?,
)

/**
 * Pure rules for the advisory DRM link facts. They mirror Decky's `linkFactsFor`/`linkAdvisory` so every
 * client agrees, and they are deliberately narrow: anything unclear says nothing.
 *
 * - The DRM list is RESOLUTIONS ONLY. Refresh rate is never read, so it can never change the answer.
 * - The HOST did the join: only a connector whose `steam_output_id` equals the selected output's id
 *   counts, and only when exactly one does. A client never matches by name.
 */
object LinkAdvisory {
    /** The one connector the host joined to [outputId], or null (unjoined, ambiguous, unavailable, no facts). */
    fun connectorFor(facts: LinkFacts?, outputId: String?): LinkConnector? {
        if (facts == null || !facts.available || outputId.isNullOrEmpty()) return null
        return facts.connectors.filter { it.steamOutputId == outputId }.singleOrNull()
    }

    /**
     * Warn only when the joined connector is connected, not internal, lists at least one resolution, and
     * [selectedMode]'s `"<width>x<height>"` is not among them. [currentModeId] is the output's current
     * mode: choosing it changes nothing, so it never warns. Internal panels are skipped because Steam
     * exposes scaled modes the connector list does not.
     */
    fun evaluate(
        facts: LinkFacts?,
        selectedOutputId: String?,
        selectedMode: DisplayModeSummary?,
        currentModeId: String? = null,
    ): LinkAdvisoryNotice? {
        val mode = selectedMode ?: return null
        if (currentModeId != null && mode.id == currentModeId) return null
        val connector = connectorFor(facts, selectedOutputId) ?: return null
        if (!connector.connected || connector.isInternal || connector.resolutions.isEmpty()) return null
        val resolution = "${mode.width}x${mode.height}"
        return if (resolution in connector.resolutions) null else LinkAdvisoryNotice(connector, resolution)
    }

    fun line(connector: LinkConnector): LinkLine {
        val vendor = connector.monitorVendor
        val product = connector.monitorProductId
        val both = vendor != null && product != null
        return LinkLine(
            connector = connector.connector,
            card = connector.gpuId.removePrefix("drm:"),
            displayName = connector.displayName.takeIf { it.isNotBlank() && it != connector.connector },
            vendor = vendor.takeIf { both },
            productId = product.takeIf { both },
        )
    }
}

/**
 * What the output topology looked like when link facts were requested. The facts belong to that
 * topology only: once an output is added, removed or re-enumerated they are no longer trusted (the
 * output id could now name another display) and the screen asks again.
 */
object LinkFactsTopology {
    /** Null when there is no available inventory to attach facts to; read-only legacy hosts have no such route either. */
    fun key(inventory: VersionedPayload.DisplayOutputs?): String? {
        if (inventory == null || !inventory.available || inventory.legacyShape) return null
        return buildString {
            append(inventory.generation ?: "-")
            inventory.outputs.forEach { append('|').append(it.id).append(':').append(it.generation) }
        }
    }
}
