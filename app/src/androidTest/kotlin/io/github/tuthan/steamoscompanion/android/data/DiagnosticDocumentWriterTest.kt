package io.github.tuthan.steamoscompanion.android.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.steamoscompanion.core.client.DiagnosticExporter
import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.CandidatePhase
import io.github.tuthan.steamoscompanion.core.client.EncryptedStateRepository
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.JournalRejectionCode
import io.github.tuthan.steamoscompanion.core.client.PairingCandidate
import io.github.tuthan.steamoscompanion.core.client.PairingVariant
import io.github.tuthan.steamoscompanion.core.client.StateCommitResult
import io.github.tuthan.steamoscompanion.core.client.StatusPollState
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a test-only content provider; no document is written outside this test's cache. */
@RunWith(AndroidJUnit4::class)
class DiagnosticDocumentWriterTest {
    @Test fun chosenContentDocumentReceivesOnlySanitizedBoundedJson() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = Uri.parse("content://${DiagnosticTestProvider.AUTHORITY}/export")
        context.contentResolver.delete(destination, null, null)
        try {
            val store = AndroidAtomicStateStore(File(context.cacheDir, "phase10-empty-state.bin"))
            val exporter = DiagnosticExporter(EncryptedStateRepository(store, AndroidKeystoreStateCipher()), AndroidClock())
            val writer = DiagnosticDocumentWriter(context.contentResolver, exporter)
            val result = writer.write(destination, StatusPollState(error = "TOKEN_SECRET_IN_RAW_ERROR"))

            assertTrue(result is DiagnosticWriteResult.Saved)
            val bytes = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
            assertEquals((result as DiagnosticWriteResult.Saved).bytesWritten, bytes.size)
            assertTrue(bytes.size <= DiagnosticExporter.MAX_EXPORT_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            assertTrue(text.contains("\"format_version\":3"))
            // Nothing was read from a host, so the report is present with every section null.
            assertTrue(text.contains("\"report\":{\"format\":\"steamos-companion-report-v1\",\"host\":null,\"outputs\":null,\"link\":null,\"journal\":null}"))
            assertFalse(text.contains("TOKEN_SECRET_IN_RAW_ERROR"))
            assertEquals(DiagnosticWriteResult.InvalidDestination,
                writer.write(Uri.fromFile(File(context.cacheDir, "invalid-export.json")), StatusPollState()))
            assertEquals(bytes.toList(), context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }.toList())
        } finally {
            context.contentResolver.delete(destination, null, null)
        }
    }

    @Test fun documentExcludesSecretsSeededInProductionEncryptedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = Uri.parse("content://${DiagnosticTestProvider.AUTHORITY}/export")
        val directory = File(context.cacheDir, "phase10-seeded-export").apply {
            deleteRecursively()
            mkdirs()
        }
        context.contentResolver.delete(destination, null, null)
        try {
            val token = "TOKEN_SECRET_DEVICE_123"
            val session = "SESSION_SECRET_DEVICE_1234567890"
            val nonce = "NonceDevice12345678901"
            val qrSecret = "QR_PAYLOAD_SECRET_DEVICE_1234567890"
            val host = ActiveHost(
                "host-diagnostic", "client-diagnostic", "deck.local:18443",
                "sha256:" + "ab".repeat(32), token, listOf("status.read", "power.control"),
                StoredWakeTarget(false, null, null, null, null), 1,
            )
            val request = ApiRequest(HttpMethod.POST, ApiRoute.POWER.path,
                jsonObject("request_id" to JsonValue.StringValue("diagnostic-power"),
                    "action" to JsonValue.StringValue("restart")), token)
            val entry = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 2)
                .copy(phase = JournalPhase.FAILED, rejectionCode = JournalRejectionCode.OPERATION_FAILED,
                    resultSummary = "Host detail: $nonce")
            val candidate = PairingCandidate(
                generation = 1, variant = PairingVariant.ADVANCED_PAYLOAD,
                clientId = "replacement-client", clientName = "Android Companion",
                requestedScopes = listOf("status.read"), endpoint = "other-deck.local:18443",
                certificatePin = "sha256:" + "cd".repeat(32), hostId = "replacement-host",
                createdAtMillis = 1, expiresAtMillis = 10, phase = CandidatePhase.PENDING,
                payloadId = "diagnostic-payload", payloadSecret = qrSecret,
                pairingId = "diagnostic-payload", pairingSession = session,
            )
            val repository = EncryptedStateRepository(
                AndroidAtomicStateStore(File(directory, "encrypted-state.bin")), AndroidKeystoreStateCipher())
            assertTrue(repository.transact {
                it.copy(pairingGeneration = 1, activeHost = host, candidate = candidate, journal = listOf(entry))
            } is StateCommitResult.Saved)

            val writer = DiagnosticDocumentWriter(context.contentResolver, DiagnosticExporter(repository, AndroidClock()))
            val result = writer.write(destination, StatusPollState(error = "Raw error: $token $nonce"))
            assertTrue(result is DiagnosticWriteResult.Saved)
            val bytes = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
            assertEquals((result as DiagnosticWriteResult.Saved).bytesWritten, bytes.size)
            assertTrue(bytes.size <= DiagnosticExporter.MAX_EXPORT_BYTES)
            val text = bytes.toString(Charsets.UTF_8)
            listOf(token, session, nonce, qrSecret, host.certificatePin, host.endpoint,
                candidate.payloadId!!, entry.bodyBase64, entry.bodySha256, entry.requestId,
                entry.resultSummary!!).forEach { secret ->
                assertFalse("Diagnostic document contained a seeded secret", text.contains(secret))
            }
            assertTrue(text.contains("\"paired\":true"))
            assertTrue(text.contains("\"candidate_phase\":\"pending\""))
            assertTrue(text.contains("\"route\":\"/v1/power\""))
            assertTrue(text.contains("\"failure_code\":\"operation_failed\""))

            val verificationCandidate = candidate.copy(variant = PairingVariant.VERIFICATION,
                payloadId = null, payloadSecret = null, verificationNonce = nonce)
            assertTrue(repository.transact { it.copy(candidate = verificationCandidate) } is StateCommitResult.Saved)
            assertTrue(writer.write(destination, StatusPollState()) is DiagnosticWriteResult.Saved)
            val verificationDocument = context.contentResolver.openInputStream(destination)!!.use { it.readBytes() }
                .toString(Charsets.UTF_8)
            listOf(token, session, nonce, qrSecret).forEach { secret ->
                assertFalse("Diagnostic document contained a seeded secret", verificationDocument.contains(secret))
            }
        } finally {
            context.contentResolver.delete(destination, null, null)
            directory.deleteRecursively()
        }
    }

}
