package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject

/** Builds a small export from named facts only. No stored JSON, request body, or error text is copied. */
class DiagnosticExporter(
    private val repository: EncryptedStateRepository,
    private val clock: Clock,
) {
    fun export(status: StatusPollState): ByteArray = encode(repository.load(), status, clock.wallTimeMillis())

    companion object {
        const val MAX_EXPORT_BYTES = 16 * 1024

        fun encode(load: StateLoadResult, status: StatusPollState, generatedAtMillis: Long): ByteArray {
            val (storeState, state) = when (load) {
                StateLoadResult.Empty -> "empty" to null
                is StateLoadResult.Ready -> "ready" to load.state
                is StateLoadResult.Unavailable -> "unavailable_${load.reason.name.lowercase()}" to null
                is StateLoadResult.Failed -> "failed_${load.reason.name.lowercase()}" to null
            }
            val entries = state?.journal.orEmpty().takeLast(PersistentStateLimits.MAX_JOURNAL_ENTRIES).map { entry ->
                jsonObject(
                    "route" to string(entry.path),
                    "phase" to string(entry.phase.wireValue),
                    "failure_code" to (entry.rejectionCode?.wireValue?.let(::string) ?: JsonValue.NullValue),
                    "created_at_ms" to number(entry.createdAtMillis),
                    "updated_at_ms" to number(entry.updatedAtMillis),
                    "acknowledged" to JsonValue.BooleanValue(entry.acknowledged),
                    "operation_id_present" to JsonValue.BooleanValue(entry.operationId != null),
                )
            }
            val body = jsonObject(
                "format_version" to number(2),
                "generated_at_ms" to number(generatedAtMillis.coerceAtLeast(0)),
                "store_state" to string(storeState),
                "paired" to JsonValue.BooleanValue(state?.activeHost != null),
                "candidate_phase" to (state?.candidate?.phase?.wireValue?.let(::string) ?: JsonValue.NullValue),
                "wake_configured" to JsonValue.BooleanValue(state?.activeHost?.wakeTarget?.available == true),
                "status" to jsonObject(
                    "headline" to string(status.headline.name.lowercase()),
                    "reading" to JsonValue.BooleanValue(status.reading),
                    "stale" to JsonValue.BooleanValue(status.stale),
                    "unreachable" to JsonValue.BooleanValue(status.unreachable),
                    "authenticated_read_present" to JsonValue.BooleanValue(status.lastRead != null),
                ),
                "operations" to JsonValue.ArrayValue(entries),
            )
            return BoundedJson.encode(body).also { require(it.size <= MAX_EXPORT_BYTES) { "Diagnostic export exceeded its limit" } }
        }

        private fun string(value: String) = JsonValue.StringValue(value)
        private fun number(value: Long) = JsonValue.NumberValue(value.toString())
        private fun number(value: Int) = JsonValue.NumberValue(value.toString())
    }
}
