package io.github.tuthan.steamoscompanion.android.data

import android.content.ContentResolver
import android.net.Uri
import io.github.tuthan.steamoscompanion.core.client.DiagnosticExporter
import io.github.tuthan.steamoscompanion.core.client.HostJournalState
import io.github.tuthan.steamoscompanion.core.client.LinkFactsReading
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import java.io.FileNotFoundException
import java.io.IOException

sealed interface DiagnosticWriteResult {
    data class Saved(val bytesWritten: Int) : DiagnosticWriteResult
    data object InvalidDestination : DiagnosticWriteResult
    data object DestinationUnavailable : DiagnosticWriteResult
    data object PermissionDenied : DiagnosticWriteResult
    data object ExportFailed : DiagnosticWriteResult
    data object WriteFailed : DiagnosticWriteResult
}

/** Writes only the bounded sanitized export (local facts and the redacted report) to a document URI chosen by an explicit user action. */
class DiagnosticDocumentWriter(
    private val resolver: ContentResolver,
    private val exporter: DiagnosticExporter,
) {
    /** Call from a worker after ACTION_CREATE_DOCUMENT returns a content URI. */
    fun write(
        destination: Uri,
        status: StatusPollState,
        linkFacts: LinkFactsReading? = null,
        journal: HostJournalState? = null,
    ): DiagnosticWriteResult {
        if (destination.scheme != ContentResolver.SCHEME_CONTENT || destination.authority.isNullOrEmpty()) {
            return DiagnosticWriteResult.InvalidDestination
        }
        val bytes = try {
            exporter.export(status, linkFacts, journal)
        } catch (_: Exception) {
            return DiagnosticWriteResult.ExportFailed
        }
        return try {
            val stream = resolver.openOutputStream(destination, "wt")
                ?: return DiagnosticWriteResult.DestinationUnavailable
            stream.use {
                it.write(bytes)
                it.flush()
            }
            DiagnosticWriteResult.Saved(bytes.size)
        } catch (_: SecurityException) {
            DiagnosticWriteResult.PermissionDenied
        } catch (_: FileNotFoundException) {
            DiagnosticWriteResult.DestinationUnavailable
        } catch (_: IOException) {
            DiagnosticWriteResult.WriteFailed
        } catch (_: IllegalArgumentException) {
            DiagnosticWriteResult.InvalidDestination
        } catch (_: Exception) {
            DiagnosticWriteResult.WriteFailed
        }
    }
}
