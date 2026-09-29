package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload

enum class DisplayOperationKind {
    PREVIEW, KEEP, REVERT_PREVIEW, RESTORE_SAVED, SAVE_CURRENT, ORDER_SAVE, ORDER_SAVE_AND_RESTART, ORDER_RESET,
}

/** A current host fact. None of these values asserts that the journaled action caused it. */
enum class DisplayObservation {
    NOT_CHECKED, CONNECTION_LOST, OUTPUTS_UNAVAILABLE, UNPROVEN_PAIRING,
    PREVIEW_ACTIVE, PREVIEW_DIFFERENT, PREVIEW_ABSENT,
    OUTPUT_MISSING, OUTPUT_REENUMERATED, PROFILE_NOT_LISTED,
    MODE_MATCHES_REQUEST, MODE_DIFFERS_FROM_REQUEST, DISPLAY_REACHABLE,
    /** Order readback only: the host's saved order equals, differs from, or is cleared relative to the request. */
    ORDER_UNAVAILABLE, ORDER_SAVED_MATCHES_REQUEST, ORDER_SAVED_DIFFERS_FROM_REQUEST,
    ORDER_AUTOMATIC, ORDER_STILL_SAVED,
}

data class DisplayOperationCard(
    val localGeneration: Long,
    val kind: DisplayOperationKind,
    val phase: JournalPhase,
    val operationId: String?,
    val failure: JournalRejectionCode?,
    val observed: DisplayObservation,
    val pairingProven: Boolean,
    /** For order saves: the exact keys that were submitted, so a draft can wait for host readback. */
    val requestedKeys: List<String>? = null,
)

/** Rebuilds the latest display operation card from durable journal and an independent status read. */
object DisplayOperationPresentation {
    fun latest(
        host: ActiveHost,
        state: PersistentState,
        observation: StatusPollState,
        nowMonotonicNanos: Long,
    ): DisplayOperationCard? {
        val active = state.activeHost ?: return null
        if (StatusObservationSource.from(active) != StatusObservationSource.from(host)) return null
        val entry = state.journal.asSequence()
            .filter { kind(it) != null && (it.belongsTo(active) || it.phase.unresolved && it.mayConflictWith(active)) }
            .maxByOrNull { it.localGeneration } ?: return null
        val kind = kind(entry) ?: return null
        val proven = entry.belongsTo(active)
        val observed = if (!proven) DisplayObservation.UNPROVEN_PAIRING else when {
            observation.source != StatusObservationSource.from(active) -> DisplayObservation.NOT_CHECKED
            observation.unreachable -> DisplayObservation.CONNECTION_LOST
            else -> {
                val read = observation.lastRead
                val age = observation.lastSuccessMonotonicNanos?.let { nowMonotonicNanos - it }
                if (read == null || read.status.hostId != active.hostId || observation.stale ||
                    age == null || age !in 0..FRESH_NANOS
                ) DisplayObservation.NOT_CHECKED
                else if (kind.isOrder) orderObservation(entry, kind, read.order?.snapshot)
                else observedFor(entry, kind, read.outputs)
            }
        }
        return DisplayOperationCard(entry.localGeneration, kind, entry.phase, entry.operationId,
            entry.rejectionCode, observed, proven, requestedKeys(entry, kind))
    }

    private fun observedFor(
        entry: JournalEntry,
        kind: DisplayOperationKind,
        outputs: VersionedPayload.DisplayOutputs?,
    ): DisplayObservation {
        if (outputs == null || !outputs.available || outputs.legacyShape) return DisplayObservation.OUTPUTS_UNAVAILABLE
        val body = BoundedJson.parseObject(entry.bodyBytes())
        val preview = outputs.preview
        return when (kind) {
            DisplayOperationKind.PREVIEW -> {
                if (preview != null) {
                    if (preview.id == entry.operationId) DisplayObservation.PREVIEW_ACTIVE
                    else DisplayObservation.PREVIEW_DIFFERENT
                } else modeObservation(outputs, string(body["output_id"]), number(body["generation"]),
                    string(body["mode_id"]))
            }
            DisplayOperationKind.KEEP -> when {
                preview == null -> DisplayObservation.PREVIEW_ABSENT
                preview.id == string(body["preview_id"]) -> DisplayObservation.PREVIEW_ACTIVE
                else -> DisplayObservation.PREVIEW_DIFFERENT
            }
            DisplayOperationKind.REVERT_PREVIEW -> if (preview == null) DisplayObservation.PREVIEW_ABSENT
                else DisplayObservation.PREVIEW_ACTIVE
            DisplayOperationKind.RESTORE_SAVED -> {
                val profile = outputs.profiles.firstOrNull { it.id == string(body["profile_id"]) }
                    ?: return DisplayObservation.PROFILE_NOT_LISTED
                modeObservation(outputs, profile.outputId, null, profile.mode.id)
            }
            DisplayOperationKind.SAVE_CURRENT -> outputObservation(outputs, string(body["output_id"]),
                number(body["generation"]))
            DisplayOperationKind.ORDER_SAVE, DisplayOperationKind.ORDER_SAVE_AND_RESTART,
            DisplayOperationKind.ORDER_RESET -> DisplayObservation.OUTPUTS_UNAVAILABLE
        }
    }

    private fun requestedKeys(entry: JournalEntry, kind: DisplayOperationKind): List<String>? =
        if (kind != DisplayOperationKind.ORDER_SAVE && kind != DisplayOperationKind.ORDER_SAVE_AND_RESTART) null
        else (BoundedJson.parseObject(entry.bodyBytes())["output_keys"] as? JsonValue.ArrayValue)
            ?.values?.mapNotNull { string(it) }

    /** Compares only the host's saved order; it never claims the restart happened. */
    private fun orderObservation(
        entry: JournalEntry,
        kind: DisplayOperationKind,
        order: io.github.tuthan.steamoscompanion.core.protocol.DisplayOrderSummary?,
    ): DisplayObservation {
        if (order == null || order.unsupported || !order.available && order.savedOutputKeys.isEmpty()) {
            return DisplayObservation.ORDER_UNAVAILABLE
        }
        if (kind == DisplayOperationKind.ORDER_RESET) {
            return if (order.savedOutputKeys.isEmpty()) DisplayObservation.ORDER_AUTOMATIC
            else DisplayObservation.ORDER_STILL_SAVED
        }
        val requested = (BoundedJson.parseObject(entry.bodyBytes())["output_keys"] as? JsonValue.ArrayValue)
            ?.values?.mapNotNull { string(it) }.orEmpty()
        return if (order.savedOutputKeys == requested) DisplayObservation.ORDER_SAVED_MATCHES_REQUEST
        else DisplayObservation.ORDER_SAVED_DIFFERS_FROM_REQUEST
    }

    private fun modeObservation(
        outputs: VersionedPayload.DisplayOutputs,
        outputId: String?,
        generation: Long?,
        modeId: String?,
    ): DisplayObservation {
        val output = outputs.outputs.firstOrNull { it.id == outputId } ?: return DisplayObservation.OUTPUT_MISSING
        if (generation != null && output.generation != generation) return DisplayObservation.OUTPUT_REENUMERATED
        return if (output.currentModeId == modeId) DisplayObservation.MODE_MATCHES_REQUEST
        else DisplayObservation.MODE_DIFFERS_FROM_REQUEST
    }

    private fun outputObservation(
        outputs: VersionedPayload.DisplayOutputs,
        outputId: String?,
        generation: Long?,
    ): DisplayObservation {
        val output = outputs.outputs.firstOrNull { it.id == outputId } ?: return DisplayObservation.OUTPUT_MISSING
        return if (output.generation != generation) DisplayObservation.OUTPUT_REENUMERATED
        else DisplayObservation.DISPLAY_REACHABLE
    }

    private fun kind(entry: JournalEntry): DisplayOperationKind? = when (entry.path) {
        ApiRoute.DISPLAY_PREVIEW.path -> DisplayOperationKind.PREVIEW
        ApiRoute.DISPLAY_CONFIRM.path -> DisplayOperationKind.KEEP
        ApiRoute.DISPLAY_SAVE_CURRENT.path -> DisplayOperationKind.SAVE_CURRENT
        ApiRoute.DISPLAY_RESTORE.path -> when (string(BoundedJson.parseObject(entry.bodyBytes())["source"])) {
            "preview" -> DisplayOperationKind.REVERT_PREVIEW
            "verified" -> DisplayOperationKind.RESTORE_SAVED
            else -> null
        }
        ApiRoute.DISPLAY_ORDER_SET.path -> if ((BoundedJson.parseObject(entry.bodyBytes())["restart"] as?
                JsonValue.BooleanValue)?.value == true) DisplayOperationKind.ORDER_SAVE_AND_RESTART
            else DisplayOperationKind.ORDER_SAVE
        ApiRoute.DISPLAY_ORDER_AUTOMATIC.path -> DisplayOperationKind.ORDER_RESET
        else -> null
    }

    private val DisplayOperationKind.isOrder: Boolean get() = this == DisplayOperationKind.ORDER_SAVE ||
        this == DisplayOperationKind.ORDER_SAVE_AND_RESTART || this == DisplayOperationKind.ORDER_RESET

    private fun string(value: JsonValue?): String? = (value as? JsonValue.StringValue)?.value
    private fun number(value: JsonValue?): Long? = (value as? JsonValue.NumberValue)?.lexical?.toLongOrNull()
    private const val FRESH_NANOS = 15_000_000_000L
}
