package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayOutputSummary
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload

data class DisplaySelection(val outputId: String, val outputGeneration: Long, val modeId: String?)

/** Stable selection and mode ordering for a live display inventory. */
object DisplayInventory {
    fun sameInventory(a: VersionedPayload.DisplayOutputs, b: VersionedPayload.DisplayOutputs): Boolean =
        a.available == b.available && a.generation == b.generation &&
            a.outputs == b.outputs && a.profiles == b.profiles

    fun select(inventory: VersionedPayload.DisplayOutputs, previous: DisplaySelection?): DisplaySelection? {
        if (!inventory.available) return null
        val outputs = inventory.outputs
        val previousOutput = outputs.firstOrNull { it.id == previous?.outputId }
        // An active preview is authoritative: losing its output must not silently move the picker.
        if (inventory.preview != null && previous != null && previousOutput == null) return null
        val selected = previousOutput ?: outputs.firstOrNull { it.isInternal == false } ?: outputs.firstOrNull() ?: return null
        val selectedMode = previous?.modeId?.takeIf {
            previous.outputId == selected.id && previous.outputGeneration == selected.generation &&
                selected.modes.any { mode -> mode.id == it }
        } ?: selected.currentModeId?.takeIf { current -> selected.modes.any { it.id == current } }
        return DisplaySelection(selected.id, selected.generation, selectedMode)
    }

    fun orderedModes(output: DisplayOutputSummary): List<DisplayModeSummary> = output.modes.sortedWith(
        compareByDescending<DisplayModeSummary> { it.id == output.currentModeId }
            .thenByDescending { it.width * it.height }
            .thenByDescending { it.refreshHz?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: Double.NEGATIVE_INFINITY },
    )
}
