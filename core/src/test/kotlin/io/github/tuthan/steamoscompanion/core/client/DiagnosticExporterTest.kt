package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JournalValue
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asArray
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue(text.contains("\"format_version\":3"))
        assertTrue(text.contains("\"failure_code\":\"operation_failed\""))
        assertFalse(text.contains("TOKEN_SECRET_IN_ERROR"))
        assertFalse(text.contains("TOKEN_SECRET_IN_STATUS"))
        assertFalse(text.contains(host.bearerToken))
    }

    // --- the report (B4) ----------------------------------------------------------------------------------------------------
    //
    // Until B4 the export carried only local facts, and the status self-check, the DRM link facts and the host's
    // journal were asserted never to appear in it. The explicit export now carries the redacted DiagnosticReport,
    // so those guards are replaced by stronger ones: the report is exactly what the composer makes of the readings
    // held in memory, nothing else leaks, only the pairing's own readings are used, and none of it is ever stored.

    private fun fixtureBody(path: String): JsonValue.ObjectValue {
        val file = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/$path"))
        return (file["response"] as? JsonValue.ObjectValue)?.get("body") as? JsonValue.ObjectValue ?: file
    }

    private fun payload(route: ApiRoute, body: JsonValue.ObjectValue): VersionedPayload =
        (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload

    private fun payload(route: ApiRoute, path: String) = payload(route, fixtureBody(path))
    private fun compact(value: JsonValue) = String(BoundedJson.encode(value), Charsets.UTF_8)
    private fun parse(export: ByteArray) = BoundedJson.parseObject(export)

    /** The healthy vector case's readings, held for [host]'s pairing exactly as the app holds them. */
    private class Held(val host: ActiveHost, val status: StatusPollState, val link: LinkFactsReading, val journal: HostJournalState.Ready,
        val readings: DiagnosticReadings)

    private fun held(host: ActiveHost = testActiveHost()): Held {
        val source = StatusObservationSource.from(host)
        val status = payload(ApiRoute.STATUS, "contract/status/sunshine-running.json") as VersionedPayload.Status
        val outputs = payload(ApiRoute.DISPLAY_OUTPUTS, "contract/display-outputs/available.json") as VersionedPayload.DisplayOutputs
        val facts = (payload(ApiRoute.DRM, "contract/drm/available.json") as VersionedPayload.Drm).facts
        val journal = (payload(ApiRoute.OPERATION_LIST, "contract/operations/list-mixed.json") as VersionedPayload.OperationList).journal!!
        return Held(
            host,
            StatusPollState(lastRead = PairedStatusRead(status, outputs, null), lastSuccessMonotonicNanos = 1, source = source),
            LinkFactsReading(source, LinkFactsTopology.key(outputs)!!, facts),
            HostJournalState.Ready(source, journal),
            DiagnosticReadings(status, outputs, facts, journal),
        )
    }

    private fun export(held: Held, status: StatusPollState = held.status, link: LinkFactsReading? = held.link, journal: HostJournalState? = held.journal,
        host: ActiveHost? = held.host, version: String = "1.2.3") =
        DiagnosticExporter.encode(StateLoadResult.Ready(PersistentState(activeHost = host)), status, 10, link, journal, version)

    @Test fun theExportCarriesTheClientAndExactlyTheReportComposedFromTheReadingsInMemory() {
        val held = held()
        val export = export(held)
        val body = parse(export)
        assertEquals(DiagnosticExporter.FORMAT_VERSION.toString(), (body["format_version"] as JsonValue.NumberValue).lexical)
        assertEquals("""{"app":"android","version":"1.2.3"}""", compact(body["client"]!!))
        assertEquals("10", (body["generated_at_ms"] as JsonValue.NumberValue).lexical)
        assertEquals(
            listOf("format_version", "client", "generated_at_ms", "store_state", "paired", "candidate_phase", "wake_configured", "status", "operations", "report"),
            body.fields.keys.toList(),
        )
        val composed = DiagnosticReport.compose(held.readings)
        assertEquals(compact(composed), compact(body["report"]!!))
        // And that report is the cross-client vector's "healthy" case: the same four fixtures, rebuilt as the reference does.
        val healthy = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/diagnostic-report.json"))["cases"].asArray("cases")
            .map { it.asObject("case") }.first { it["name"].asString("name") == "healthy" }
        assertEquals(compact(healthy["report"]!!), compact(body["report"]!!))
        assertTrue(export.size <= DiagnosticExporter.MAX_EXPORT_BYTES)
        assertEquals(32 * 1024, DiagnosticExporter.MAX_EXPORT_BYTES)
    }

    @Test fun onlyReadingsHeldForThisPairingAreExported() {
        val held = held()
        val other = testActiveHost(hostId = "host-other0002xxxxxxxxxxxxx", token = "fixture-token-0003-xxxxxxxxxxxxxxxxxxxxxxxx")
        val strangers = held(other)
        // Everything was observed for another credential: nothing of it is the active host's.
        val report = parse(export(held, status = strangers.status, link = strangers.link, journal = strangers.journal))["report"].asObject("report")
        listOf("host", "outputs", "link", "journal").forEach { assertEquals(it, JsonValue.NullValue, report[it]) }
        // One at a time.
        assertEquals(JsonValue.NullValue, parse(export(held, status = strangers.status))["report"].asObject("r")["host"])
        assertEquals(JsonValue.NullValue, parse(export(held, link = strangers.link))["report"].asObject("r")["link"])
        assertEquals(JsonValue.NullValue, parse(export(held, journal = strangers.journal))["report"].asObject("r")["journal"])
        // Link facts are tied to the output topology they were read for.
        val stale = LinkFactsReading(held.link.source, "another-topology", held.link.facts)
        assertEquals(JsonValue.NullValue, parse(export(held, link = stale))["report"].asObject("r")["link"])
        // A journal that is still loading, or was not available, is not a journal.
        assertEquals(JsonValue.NullValue, parse(export(held, journal = HostJournalState.Loading(held.link.source)))["report"].asObject("r")["journal"])
        assertEquals(JsonValue.NullValue, parse(export(held, journal = HostJournalState.Unavailable(held.link.source)))["report"].asObject("r")["journal"])
        // No active host: nothing is anyone's.
        val noHost = DiagnosticExporter.encode(StateLoadResult.Empty, held.status, 10, held.link, held.journal)
        listOf("host", "outputs", "link", "journal").forEach { assertEquals(it, JsonValue.NullValue, parse(noHost)["report"].asObject("r")[it]) }
    }

    @Test fun whatWasNotReadIsNullAndWhatTheHostAnsweredEmptyIsPresent() {
        val held = held()
        val nothing = parse(export(held, status = StatusPollState(), link = null, journal = null))["report"]!!
        assertEquals("""{"format":"steamos-companion-report-v1","host":null,"outputs":null,"link":null,"journal":null}""", compact(nothing))
        val emptyJournal = parse(export(held, journal = HostJournalState.Ready(held.link.source, HostJournal(emptyList()))))["report"].asObject("r")["journal"]!!
        assertEquals("""{"count":0,"truncated":false,"records":[]}""", compact(emptyJournal))
    }

    @Test fun aFullJournalAndAFullReportStayWithinTheExportLimit() {
        val host = testActiveHost()
        val request = testPowerRequest("diagnostic-full")
        val entries = (1L..PersistentStateLimits.MAX_JOURNAL_ENTRIES.toLong()).map {
            JournalEntry.create(it, host, request, BoundedJson.encode(request.body!!), 2)
                .copy(phase = JournalPhase.FAILED, rejectionCode = JournalRejectionCode.OPERATION_FAILED, operationId = "op-$it")
        }
        val big = (0 until 30).map { HostJournalRecord("op-$it", "display.selection.preview", "failed", null, "2026-09-13T12:%02d:00Z".format(it), "target_active_reconciled_after_reload",
            "long reason ".repeat(30), "succeeded", true, null, mapOf("action" to JournalValue.Text("a".repeat(60)), "output_key" to JournalValue.Text("z".repeat(60)))) }
        val held = held(host)
        val export = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host, journal = entries)), held.status, 10, held.link,
            HostJournalState.Ready(held.link.source, HostJournal(big)), "1.2.3",
        )
        assertTrue("${export.size} bytes", export.size <= DiagnosticExporter.MAX_EXPORT_BYTES)
        assertTrue(export.size > 16 * 1024 / 2)
    }

    @Test fun theVersionIsCheckedBeforeItIsExported() {
        val held = held()
        listOf("0.0.1", "1.2.3-beta+4", "v1").forEach {
            assertEquals(it, parse(export(held, version = it))["client"].asObject("c")["version"].asString("v"))
        }
        listOf("", " ", "1.2.3\n", "a".repeat(25), "https://evil.example", "x y", "TOKEN_SECRET\u0000").forEach {
            assertEquals(it, DiagnosticExporter.UNKNOWN_VERSION, parse(export(held, version = it))["client"].asObject("c")["version"].asString("v"))
        }
    }

    /** No type that is saved to encrypted state or journaled has a place to put the link facts, the host's journal or the report. */
    @Test fun persistedAndExportedModelsHaveNoLinkFactsJournalRecordsOrReportField() {
        listOf(
            PersistentState::class.java, ActiveHost::class.java, JournalEntry::class.java, PairingCandidate::class.java,
            StatusPollState::class.java, PairedStatusRead::class.java,
        ).forEach { type ->
            val offending = type.declaredFields.filter { field ->
                listOf("Link", "HostJournal", "JournalRecord", "DiagnosticReport", "DiagnosticReadings").any { it in field.genericType.typeName } ||
                    listOf("linkFacts", "hostJournal", "diagnosticReport", "report").any { it.equals(field.name, ignoreCase = true) }
            }
            assertTrue("${type.simpleName} has a link-facts, host-journal or report field: $offending", offending.isEmpty())
        }
    }

    /** The report is composed for one export and handed back; nothing keeps it, in memory, in the repository or in the state. */
    @Test fun theReportIsNeverStored() {
        val store = MemoryStateStore()
        val repository = EncryptedStateRepository(store, JvmAesGcmStateCipher())
        val held = held()
        assertTrue(repository.transact { it.copy(activeHost = held.host) } is StateCommitResult.Saved)
        val before = store.rawBytes()
        val writes = store.writeCount
        val exporter = DiagnosticExporter(repository, object : Clock {
            override fun wallTimeMillis() = 10L
            override fun monotonicNanos() = 0L
        }, "1.2.3")

        val first = exporter.export(held.status, held.link, held.journal)
        val second = exporter.export(held.status, held.link, held.journal)
        assertTrue(String(first, Charsets.UTF_8).contains("steamos-companion-report-v1"))
        assertEquals("an export is a pure function of its readings", first.toList(), second.toList())
        assertEquals("an export writes nothing", writes, store.writeCount)
        assertEquals(before!!.toList(), store.rawBytes()!!.toList())
        val saved = (repository.load() as StateLoadResult.Ready).state
        assertFalse(String(PersistentStateCodec.encode(saved), Charsets.UTF_8).contains("steamos-companion-report"))
        // The exporter and the composer hold no report: the exporter's fields are its collaborators, and the composer has no mutable state.
        assertEquals(
            setOf("repository" to EncryptedStateRepository::class.java, "clock" to Clock::class.java, "appVersion" to String::class.java),
            DiagnosticExporter::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name to it.type }.toSet(),
        )
        val mutable = DiagnosticReport::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isFinal(it.modifiers) }
        assertTrue("DiagnosticReport has mutable state: $mutable", mutable.isEmpty())
        // Exporting without readings changes nothing either, and a later export does not remember an earlier one.
        val bare = exporter.export(StatusPollState())
        assertEquals(JsonValue.NullValue, parse(bare)["report"].asObject("r")["host"])
    }

    /** Secrets in the readings the report is built from: none of them may be in the export, and the facts the report names still are. */
    @Test fun theExportNeverContainsTheSecretsOfAStatusJournalOrLinkReading() {
        val host = testActiveHost()
        val source = StatusObservationSource.from(host)
        val statusText = compact(fixtureBody("contract/status/sunshine-running.json"))
            .replace("host-fixture0001xxxxxxxxxxxxx", "HOST_ID_SENTINEL_9f3a").replace("00000000-0000-4000-8000-000000000001", "BOOT_ID_SENTINEL_77")
            .replace("001122334455", "WAKE_MAC_SENTINEL").replace("192.168.50.24", "SOURCE_ADDRESS_SENTINEL").replace("\"enp5s0\"", "\"INTERFACE_SENTINEL\"")
            .replace("\"address\":\"0.0.0.0\"", "\"address\":\"LISTENER_ADDRESS_SENTINEL\"").replace("aaaaaaaa", "FP_SENTINEL")
        val status = payload(ApiRoute.STATUS, BoundedJson.parseObject(statusText.toByteArray())) as VersionedPayload.Status
        val outputs = payload(ApiRoute.DISPLAY_OUTPUTS, BoundedJson.parseObject("""
            {"protocol_version":1,"available":true,"age_ms":0,"reason":null,"generation":4,"outputs":[{"id":"1","name":"Living room 198.51.100.7 TV","description":"PANEL_DESCRIPTION","is_internal":false,
             "current_mode_id":"m1","generation":4,"modes":[{"id":"m1","width":1920,"height":1080,"refresh_hz":60}]}],
             "profiles":[{"id":"PROFILE_ID_SENTINEL","output_id":"1","mode":{"id":"m1","width":1920,"height":1080,"refresh_hz":60}}],
             "preview":{"preview_id":"PREVIEW_ID_SENTINEL","output_id":"1","generation":4,"deadline":1.5,
                        "baseline_mode":{"id":"m1","width":1920,"height":1080,"refresh_hz":60},"target_mode":{"id":"m1","width":1920,"height":1080,"refresh_hz":60}}}
        """.toByteArray())) as VersionedPayload.DisplayOutputs
        val facts = (payload(ApiRoute.DRM, BoundedJson.parseObject("""
            {"protocol_version":1,"drm":{"available":true,"observed_at":"OBSERVED_AT_SENTINEL","reason":"reader at 198.51.100.9 failed","connectors":[
             {"connector":"HDMI-A-1","gpu_id":"drm:card0","display_name":"DISPLAY_NAME_SENTINEL","monitor_vendor":"GSM","monitor_product_id":49780,"is_internal":false,
              "connected":true,"resolutions":["1920x1080"],"steam_output_id":"1"}]}}
        """.toByteArray())) as VersionedPayload.Drm).facts
        val journal = (payload(ApiRoute.OPERATION_LIST, BoundedJson.parseObject("""
            {"protocol_version":1,"operations":[{"id":"op-ID_SENTINEL_1","request_id":"req-REQUEST_ID_SENTINEL","preview_id":"op-PREVIEW_SENTINEL","kind":"display.preview",
             "state":"failed","created_at":"CREATED_AT_SENTINEL","updated_at":"2026-09-13T12:00:00Z","outcome":"mode_request_returned","resolved_by":"RESOLVED_BY_SENTINEL",
             "reason":"could not reach 198.51.100.77 mac aa:bb:cc:dd:ee:ff token TOKEN_SENTINEL_0123456789abcdef0123 at /run/user/1000/steamos/state.json",
             "target":{"output_id":"1","profile_id":"PROFILE_TARGET_SENTINEL","token":"TOKEN_TARGET_SENTINEL","address":"198.51.100.78","serial":"SERIAL_SENTINEL",
                       "mode":{"width":1920,"height":1080,"refresh_hz":59.94}}}]}
        """.toByteArray())) as VersionedPayload.OperationList).journal!!
        val state = StatusPollState(lastRead = PairedStatusRead(status, outputs, null), lastSuccessMonotonicNanos = 1, source = source)
        val export = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host)), state, 10,
            LinkFactsReading(source, LinkFactsTopology.key(outputs)!!, facts), HostJournalState.Ready(source, journal), "1.2.3",
        )
        val text = export.toString(Charsets.UTF_8)

        // The report really was built from all four readings...
        val report = parse(export)["report"].asObject("report")
        listOf("host", "outputs", "link", "journal").forEach { assertTrue("$it was not built", report[it] is JsonValue.ObjectValue) }
        assertTrue(text.contains("could not reach [ip] mac [mac] token [token] at [path]"))
        assertTrue(text.contains("Living room [ip] TV") && text.contains("PANEL_DESCRIPTION") && text.contains("\"mode\":\"1920x1080@59.94\""))
        // ...and nothing private from any of them is in it, nor in the local part.
        listOf(
            "HOST_ID_SENTINEL", "BOOT_ID_SENTINEL", "WAKE_MAC_SENTINEL", "SOURCE_ADDRESS_SENTINEL", "INTERFACE_SENTINEL", "LISTENER_ADDRESS_SENTINEL", "FP_SENTINEL",
            "PROFILE_ID_SENTINEL", "PREVIEW_ID_SENTINEL", "OBSERVED_AT_SENTINEL", "DISPLAY_NAME_SENTINEL", "ID_SENTINEL", "REQUEST_ID_SENTINEL", "PREVIEW_SENTINEL",
            "CREATED_AT_SENTINEL", "RESOLVED_BY_SENTINEL", "TOKEN_SENTINEL", "PROFILE_TARGET_SENTINEL", "TOKEN_TARGET_SENTINEL", "SERIAL_SENTINEL",
            "198.51.100", "aa:bb:cc:dd:ee:ff", "/run/user", host.bearerToken, host.certificatePin, host.endpoint, host.hostId,
            "fingerprint", "boot_id", "host_id", "request_id", "preview_id", "profile_id", "serial", "edid", "bearer",
        ).forEach { assertFalse("Exported $it", text.contains(it)) }
    }

    /**
     * B3 asserted the whole self-check was never exported. The report now names the health the owner can read (the plugin
     * version, the listener port, the bridge methods, the certificate's days), but never the listener address or the
     * certificate's pin, in any form.
     */
    @Test fun hostSelfCheckAddressAndPinAreNeverExportedWhileItsNamedFactsAre() {
        val host = testActiveHost()
        val statusText = compact(fixtureBody("contract/status/sunshine-running.json"))
            .replace("\"address\":\"0.0.0.0\"", "\"address\":\"198.51.100.77\"").replace("aaaaaaaa", "c0ffee11").replace("0.0.0-fixture", "PLUGIN-9.9.9")
            .replace("\"display\"", "\"marker_method\"")
        val status = payload(ApiRoute.STATUS, BoundedJson.parseObject(statusText.toByteArray())) as VersionedPayload.Status
        val text = DiagnosticExporter.encode(
            StateLoadResult.Ready(PersistentState(activeHost = host)),
            StatusPollState(lastRead = PairedStatusRead(status, null, null), lastSuccessMonotonicNanos = 1, source = StatusObservationSource.from(host)), 10,
        ).toString(Charsets.UTF_8)

        assertTrue(text.contains("\"authenticated_read_present\":true"))
        assertTrue(text.contains("\"plugin_version\":\"PLUGIN-9.9.9\"") && text.contains("marker_method") && text.contains("\"port\":18443"))
        listOf("self_check", "selfCheck", "c0ffee11", "198.51.100.77", "fingerprint", "\"address\"").forEach { assertFalse("Exported $it", text.contains(it)) }
    }
}
