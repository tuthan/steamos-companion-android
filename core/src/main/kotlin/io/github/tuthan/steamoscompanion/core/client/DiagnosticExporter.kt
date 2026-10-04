package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject

/**
 * Builds the owner's explicit diagnostics export from named facts only. No stored JSON, request body, or error
 * text is copied. The export is the local facts below plus the redacted [DiagnosticReport] of the paired host,
 * composed on demand from readings the app already holds and never stored; the owner reads the file before
 * sharing it.
 */
class DiagnosticExporter(
    private val repository: EncryptedStateRepository,
    private val clock: Clock,
    private val appVersion: String = UNKNOWN_VERSION,
) {
    fun export(status: StatusPollState, linkFacts: LinkFactsReading? = null, journal: HostJournalState? = null): ByteArray =
        encode(repository.load(), status, clock.wallTimeMillis(), linkFacts, journal, appVersion)

    companion object {
        /** The local facts stay small and the report is at most [DiagnosticReport.MAX_REPORT_BYTES]. */
        const val MAX_EXPORT_BYTES = 32 * 1024
        const val FORMAT_VERSION = 3
        const val UNKNOWN_VERSION = "unknown"
        private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,23}")

        fun encode(
            load: StateLoadResult,
            status: StatusPollState,
            generatedAtMillis: Long,
            linkFacts: LinkFactsReading? = null,
            journal: HostJournalState? = null,
            appVersion: String = UNKNOWN_VERSION,
        ): ByteArray {
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
            val report = DiagnosticReport.compose(DiagnosticReadings.forHost(state?.activeHost, status, linkFacts, journal))
            val body = jsonObject(
                "format_version" to number(FORMAT_VERSION),
                "client" to jsonObject(
                    "app" to string("android"),
                    "version" to string(appVersion.takeIf(VERSION::matches) ?: UNKNOWN_VERSION),
                ),
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
                "report" to report,
            )
            return BoundedJson.encode(body).also { require(it.size <= MAX_EXPORT_BYTES) { "Diagnostic export exceeded its limit" } }
        }

        private fun string(value: String) = JsonValue.StringValue(value)
        private fun number(value: Long) = JsonValue.NumberValue(value.toString())
        private fun number(value: Int) = JsonValue.NumberValue(value.toString())
    }
}
