package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.protocol.JournalText
import io.github.tuthan.steamoscompanion.core.protocol.JournalValue

/**
 * The shared wording for operation journal records, embedded here and checked against Decky's
 * `protocol/operation-vocabulary.json` by a test, so every client says the same thing. Each label states
 * what the host recorded and nothing more: a Completed state is not a visible picture, and only an
 * outcome whose code says the owner confirmed visibility uses the word "visible".
 */
object OperationVocabulary {
    val states: Map<String, String> = mapOf(
        "accepted" to "Requested",
        "dispatched" to "In progress",
        "observed_return" to "Returned, not confirmed",
        "succeeded" to "Completed",
        "failed" to "Failed",
        "unknown" to "Result not confirmed",
    )

    val kinds: Map<String, String> = mapOf(
        "power.suspend" to "Suspend",
        "power.restart" to "Restart",
        "power.shutdown" to "Shut down",
        "display.preview" to "Display mode preview",
        "display.confirm" to "Display preview confirmation",
        "display.restore" to "Display restore",
        "display.save-current" to "Save current display mode",
        "display.order" to "Gaming Mode display order",
        "display.order.reset" to "Reset display order",
        "display.preference" to "Gaming Mode display preference",
        "display.selection.preview" to "Screen selection preview",
        "display.selection.revert" to "Screen selection revert",
        "display.session-restart" to "Gaming Mode restart",
        "sunshine.restart" to "Start or restart Sunshine",
        "pair.revoke-self" to "Revoke this client",
    )

    val outcomes: Map<String, String> = mapOf(
        "method_returned" to "Steam returned from the call; the physical result is not confirmed",
        "mode_request_returned" to "Mode request returned; waiting for confirmation",
        "target_active_read_back" to "Selected screen read back as active; waiting for confirmation",
        "target_active_reconciled_after_reload" to "Selected screen read back as active after a plugin reload",
        "visible_picture_confirmed" to "Owner confirmed the picture was visible",
        "active_screen_visible_and_confirmed" to "Owner confirmed the selected screen was visible",
        "current_mode_visible_and_read_back" to "Current mode read back; owner confirmed the picture was visible",
        "restored_and_read_back" to "Previous mode restored and read back",
        "baseline_screen_restored" to "Previous screen restored",
        "gamescope_output_configured" to "Gamescope output saved; applies after Gaming Mode restarts",
        "gamescope_output_configured_and_restart_requested" to "Gamescope output saved; Gaming Mode restart requested",
        "gamescope_output_configured_restart_failed" to "Gamescope output saved; Gaming Mode restart failed",
        "gamescope_output_cleared" to "Gamescope output preference cleared",
        "gamescope_session_restart_requested" to "Gaming Mode restart requested",
        "already_running" to "Sunshine was already running",
        "running" to "Sunshine reported running",
        "revoked" to "This client's access was revoked",
    )

    val restoreStates: Map<String, String> = mapOf(
        "dispatched" to "Restore in progress",
        "succeeded" to "Previous screen restored",
        "failed" to "Previous screen not restored",
        "unknown" to "Restore result not confirmed",
    )
}

/** One row of the Host journal: a title, muted detail lines, and the same facts as one spoken sentence. */
data class HostJournalRow(
    val id: String,
    /** "<kind> · <state>". */
    val title: String,
    /** Requested (target), Recorded (outcome), restore state, reason, updated time; absent ones are omitted. */
    val lines: List<String>,
    /** The title and lines read as sentences, for a screen reader. */
    val spoken: String,
)

/**
 * The words for one record's codes, before layout. [outcome] and [restoreState] are null when the host
 * recorded none. Every word comes from [OperationVocabulary], or is the sanitised code when unknown.
 */
data class JournalWording(val kind: String, val state: String, val outcome: String?, val restoreState: String?)

/** The newest [rows] of [total] records the host sent that survived the allowlist. */
data class HostJournalView(val rows: List<HostJournalRow>, val total: Int) {
    val truncated: Boolean get() = total > rows.size
}

/**
 * Pure presentation of the host operation journal. The journal is diagnostic evidence of what the host
 * did and read back, not status: wording comes from [OperationVocabulary], an unknown code is shown as
 * sanitised text rather than hidden or guessed at, and nothing here turns a completed entry into a claim
 * about the picture.
 */
object HostJournalPresentation {
    /** Shown with every ready list. */
    const val STANDING_NOTE =
        "Entries record what the host did and read back. A completed entry does not by itself show that the picture is visible."

    /** Newest records shown. The model keeps up to 256. */
    const val MAX_ROWS = 20
    private const val MAX_UNKNOWN_CODE = 64
    private const val MAX_TARGET_ENTRIES = 4
    private const val MAX_REQUESTED_LINE = 300
    private const val UNRECOGNISED = "Unrecognised"
    private val SEPARATOR = " · "
    private val STAMP = Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.[0-9]{1,9})?Z")

    fun view(journal: HostJournal): HostJournalView =
        HostJournalView(journal.records.take(MAX_ROWS).map(::row), journal.records.size)

    fun row(record: HostJournalRecord): HostJournalRow {
        val words = wording(record.kind, record.state, record.outcome, record.restoreState)
        val title = words.kind + SEPARATOR + words.state
        val lines = buildList {
            requested(record)?.let(::add)
            words.outcome?.let { add("Recorded$SEPARATOR$it") }
            words.restoreState?.let(::add)
            record.reason?.let(::add)
            record.updatedAt?.let(::updated)?.let(::add)
        }
        // Sentences: the detail separators become commas, and a line that already ends in a full stop is not doubled.
        val spoken = (listOf(title) + lines).joinToString(". ") { it.replace(SEPARATOR, ", ").trimEnd('.') }
        return HostJournalRow(record.id, title, lines, spoken)
    }

    /**
     * Maps the four codes to their wording. A known code gets the shared label; an unknown one is shown as
     * sanitised text, never hidden. Only a code that itself contains "visible" can produce the word.
     */
    fun wording(kind: String, state: String, outcome: String?, restoreState: String?) = JournalWording(
        kind = OperationVocabulary.kinds[kind] ?: safe(kind),
        state = OperationVocabulary.states[state] ?: safe(state),
        outcome = outcome?.let { OperationVocabulary.outcomes[it] ?: safe(it) },
        restoreState = restoreState?.let { OperationVocabulary.restoreStates[it] ?: "Restore state$SEPARATOR${safe(it)}" },
    )

    /** A code the vocabulary does not know, as bounded plain text. */
    private fun safe(code: String): String = JournalText.clean(code, MAX_UNKNOWN_CODE) ?: UNRECOGNISED

    private fun updated(stamp: String): String? = STAMP.matchEntire(stamp)?.let { "Updated ${it.groupValues[1]} ${it.groupValues[2]} UTC" }

    private fun requested(record: HostJournalRecord): String? {
        if (record.target.isEmpty()) return null
        val facts = record.target.entries.take(MAX_TARGET_ENTRIES)
            .joinToString(SEPARATOR) { (key, value) -> "${safe(key).replace('_', ' ')}: ${valueText(value)}" }
        val line = "Requested$SEPARATOR$facts"
        return if (line.length <= MAX_REQUESTED_LINE) line else line.take(MAX_REQUESTED_LINE - 1).trimEnd() + "…"
    }

    private fun valueText(value: JournalValue): String = when (value) {
        is JournalValue.Flag -> value.value.toString()
        is JournalValue.Whole -> value.value.toString()
        is JournalValue.Real -> realText(value.value)
        is JournalValue.Text -> value.value
        is JournalValue.TextList -> value.values.joinToString(", ")
        is JournalValue.Group -> groupText(value)
    }

    /** `1920x1080@60` for a mode-shaped group, otherwise `key value` pairs. */
    private fun groupText(group: JournalValue.Group): String {
        val width = group.entries["width"] as? JournalValue.Whole
        val height = group.entries["height"] as? JournalValue.Whole
        if (width != null && height != null) {
            val rate = when (val hz = group.entries["refresh_hz"]) {
                is JournalValue.Whole -> "@${hz.value}"
                is JournalValue.Real -> "@${Math.round(hz.value)}"
                else -> ""
            }
            return "${width.value}x${height.value}$rate"
        }
        return group.entries.entries.joinToString(", ") { (key, scalar) -> "${key.replace('_', ' ')} ${valueText(scalar)}" }
    }

    private fun realText(value: Double): String =
        if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
}
