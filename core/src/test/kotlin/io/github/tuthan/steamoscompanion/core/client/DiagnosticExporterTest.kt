package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.CapabilityState
import io.github.tuthan.steamoscompanion.core.protocol.HostBridgeCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostCertificateCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostListenerCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostSelfCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostVersionsCheck
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.protocol.JournalValue
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.LinkConnector
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.SteamBridgeState
import io.github.tuthan.steamoscompanion.core.protocol.SunshineState
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.WireValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticExporterTest {
    @Test fun exportExcludesEverySeededSecretAndUntrustedErrorText() {
        val token = "TOKEN_SECRET_123"
        val nonce = "NONCE_SECRET_1234567890"
        val session = "SESSION_SECRET_1234567890"
        val qrSecret = "QR_PAYLOAD_SECRET_1234567890"
        val host = ActiveHost(
            "host-1", "client-1", "192.0.2.10:8443", "sha256:" + "a".repeat(64), token,
            listOf("status.read", "power.control"), StoredWakeTarget(true, "001122334455", "wlan0", null, null), 1,
        )
        val request = ApiRequest(
            HttpMethod.POST, ApiRoute.POWER.path,
            jsonObject("request_id" to JsonValue.StringValue("request-1"), "action" to JsonValue.StringValue("restart")),
            token,
        )
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(requireNotNull(request.body)), 2)
            .copy(resultSummary = "Host said $token $session $nonce $qrSecret")
        val candidate = PairingCandidate(
            generation = 1, variant = PairingVariant.ADVANCED_PAYLOAD,
            clientId = "client-2", clientName = "phone", requestedScopes = listOf("status.read"),
            endpoint = "192.0.2.11:8443", certificatePin = "sha256:" + "b".repeat(64),
            hostId = "host-2", createdAtMillis = 1, expiresAtMillis = 5,
            phase = CandidatePhase.PENDING, payloadId = "pairing-2", payloadSecret = qrSecret,
            pairingId = "pairing-2", pairingSession = session,
        )
        val state = PersistentState(pairingGeneration = 1, activeHost = host, candidate = candidate, journal = listOf(entry))
        val export = DiagnosticExporter.encode(
            StateLoadResult.Ready(state),
            StatusPollState(error = "Transport failure: $token $nonce"),
            generatedAtMillis = 10,
        )
        val text = export.toString(Charsets.UTF_8)
        listOf(token, nonce, session, qrSecret, host.certificatePin, host.endpoint, entry.bodyBase64,
            entry.bodySha256, entry.requestId, entry.resultSummary!!).forEach { assertFalse("Leaked $it", text.contains(it)) }
        assertTrue(text.contains("\"route\":\"/v1/power\""))
        assertTrue(text.contains("\"phase\":\"prepared\""))
        assertTrue(export.size <= DiagnosticExporter.MAX_EXPORT_BYTES)
    }

    @Test fun unavailableStateExportsOnlyFixedReasonCode() {
        val export = DiagnosticExporter.encode(
            StateLoadResult.Unavailable(StateUnavailableReason.KEY_INVALIDATED),
            StatusPollState(error = "raw exception TOKEN_SECRET"), 10,
        ).toString(Charsets.UTF_8)
        assertTrue(export.contains("unavailable_key_invalidated"))
        assertFalse(export.contains("TOKEN_SECRET"))
    }

    @Test fun typedFailureIsExportedWithoutHostErrorText() {
        val host = testActiveHost()
        val request = testPowerRequest("diagnostic-failure")
        val entry = JournalEntry.create(1, host, request, BoundedJson.encode(request.body!!), 2)
            .copy(phase = JournalPhase.FAILED, rejectionCode = JournalRejectionCode.OPERATION_FAILED,
                resultSummary = "Host error included TOKEN_SECRET_IN_ERROR")
        val text = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host, journal = listOf(entry))),
            StatusPollState(error = "TOKEN_SECRET_IN_STATUS"), 10,
        ).toString(Charsets.UTF_8)

        assertTrue(text.contains("\"format_version\":2"))
        assertTrue(text.contains("\"failure_code\":\"operation_failed\""))
        assertFalse(text.contains("TOKEN_SECRET_IN_ERROR"))
        assertFalse(text.contains("TOKEN_SECRET_IN_STATUS"))
        assertFalse(text.contains(host.bearerToken))
    }

    /** The host self-check is live status data; the export must not gain any of its facts. */
    @Test fun hostSelfCheckFactsAreNeverExported() {
        val host = testActiveHost()
        val selfCheck = HostSelfCheck(
            certificate = HostCertificateCheck(true, "c0ffee11", null, null),
            listener = HostListenerCheck(true, "198.51.100.77", 18443),
            bridge = HostBridgeCheck(true, listOf("marker_method")),
            temperature = null,
            provider = null,
            versions = HostVersionsCheck(1, "PLUGIN_MARKER_9.9.9"),
        )
        val status = VersionedPayload.Status(host.hostId, "boot-1", WireValue.Known(SteamBridgeState.READY),
            mapOf("suspend" to WireValue.Known(CapabilityState.AVAILABLE)), WireValue.Known(SunshineState.RUNNING),
            jsonObject(), selfCheck)
        val text = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host)),
            StatusPollState(lastRead = PairedStatusRead(status, null, null), lastSuccessMonotonicNanos = 1,
                source = StatusObservationSource.from(host)), 10,
        ).toString(Charsets.UTF_8)

        assertTrue(text.contains("\"authenticated_read_present\":true"))
        listOf("self_check", "selfCheck", "c0ffee11", "198.51.100.77", "marker_method", "PLUGIN_MARKER_9.9.9")
            .forEach { assertFalse("Exported $it", text.contains(it)) }
    }

    /**
     * The DRM link facts are advisory, in-memory data held beside the status observation, not in it. Nothing
     * the exporter reads can carry them, and the export must not gain any of their facts.
     */
    @Test fun linkFactsAreNeverExported() {
        val host = testActiveHost()
        val reading = LinkFactsReading(StatusObservationSource.from(host), "topology-marker", LinkFacts(
            true, "OBSERVED_MARKER", "REASON_MARKER", listOf(LinkConnector("HDMI-MARKER-1", "drm:card7", "DISPLAY_NAME_MARKER", "MRK", 31337,
                isInternal = false, connected = true, resolutions = listOf("7777x8888"), steamOutputId = "OUTPUT_MARKER"))))
        val status = VersionedPayload.Status(host.hostId, "boot-1", WireValue.Known(SteamBridgeState.READY),
            mapOf("suspend" to WireValue.Known(CapabilityState.AVAILABLE)), WireValue.Known(SunshineState.RUNNING), jsonObject())
        val text = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host)),
            StatusPollState(lastRead = PairedStatusRead(status, null, null), lastSuccessMonotonicNanos = 1,
                source = StatusObservationSource.from(host)), 10,
        ).toString(Charsets.UTF_8)

        assertNotNull(reading.factsFor(host, "topology-marker"))
        assertTrue(text.contains("\"authenticated_read_present\":true"))
        listOf(
            "\"drm\"", "linkFacts", "LinkFacts", "link_facts", "resolutions", "steam_output_id", "gpu_id", "monitor_vendor",
            "HDMI-MARKER-1", "card7", "DISPLAY_NAME_MARKER", "31337", "7777x8888", "OUTPUT_MARKER", "OBSERVED_MARKER", "REASON_MARKER",
        ).forEach { assertFalse("Exported $it", text.contains(it)) }
    }

    /** No type that is saved to encrypted state, journaled, or exported has a place to put the facts. */
    @Test fun persistedAndExportedModelsHaveNoLinkFactsField() {
        listOf(
            PersistentState::class.java, ActiveHost::class.java, JournalEntry::class.java, PairingCandidate::class.java,
            StatusPollState::class.java, PairedStatusRead::class.java,
        ).forEach { type ->
            val offending = type.declaredFields.filter { "Link" in it.genericType.typeName }
            assertTrue("${type.simpleName} has a link-facts field: $offending", offending.isEmpty())
        }
    }

    /**
     * The host operation journal is diagnostic, in-memory data held beside the status observation, not in it.
     * Nothing the exporter reads can carry it, and the export must not gain any of its records.
     */
    @Test fun hostJournalRecordsAreNeverExported() {
        val host = testActiveHost()
        val state = HostJournalState.Ready(StatusObservationSource.from(host), HostJournal(listOf(HostJournalRecord(
            id = "op-ID_MARKER", kind = "kind.KIND_MARKER", state = "STATE_MARKER", createdAt = "CREATED_MARKER", updatedAt = "UPDATED_MARKER",
            outcome = "OUTCOME_MARKER", reason = "REASON_MARKER", restoreState = "RESTORE_MARKER", restored = true, resolvedBy = "RESOLVED_MARKER",
            target = mapOf("target_key" to JournalValue.Text("TARGET_MARKER"), "list" to JournalValue.TextList(listOf("LIST_MARKER")))))))
        val status = VersionedPayload.Status(host.hostId, "boot-1", WireValue.Known(SteamBridgeState.READY),
            mapOf("suspend" to WireValue.Known(CapabilityState.AVAILABLE)), WireValue.Known(SunshineState.RUNNING), jsonObject())
        val text = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host)),
            StatusPollState(lastRead = PairedStatusRead(status, null, null), lastSuccessMonotonicNanos = 1,
                source = StatusObservationSource.from(host)), 10,
        ).toString(Charsets.UTF_8)

        assertNotNull(state.forHost(host))
        assertTrue(text.contains("\"authenticated_read_present\":true"))
        listOf(
            "hostJournal", "HostJournal", "host_journal", "restore_state", "resolved_by", "target_key",
            "ID_MARKER", "KIND_MARKER", "STATE_MARKER", "CREATED_MARKER", "UPDATED_MARKER", "OUTCOME_MARKER", "REASON_MARKER",
            "RESTORE_MARKER", "RESOLVED_MARKER", "TARGET_MARKER", "LIST_MARKER",
        ).forEach { assertFalse("Exported $it", text.contains(it)) }
    }

    /** No type that is saved to encrypted state, journaled, or exported has a place to put the host's journal. */
    @Test fun persistedAndExportedModelsHaveNoHostJournalField() {
        listOf(
            PersistentState::class.java, ActiveHost::class.java, JournalEntry::class.java, PairingCandidate::class.java,
            StatusPollState::class.java, PairedStatusRead::class.java,
        ).forEach { type ->
            val offending = type.declaredFields.filter { "HostJournal" in it.genericType.typeName || "JournalRecord" in it.genericType.typeName }
            assertTrue("${type.simpleName} has a host-journal field: $offending", offending.isEmpty())
        }
    }
}
