package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asArray
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendored cross-client vector for the redacted diagnostic report (Decky `protocol/fixtures/diagnostic-report.json`).
 *
 * Every case gives the four response bodies a report is built from and the exact `report` every client must rebuild.
 * Each body is decoded with the app's own decoder ([ProtocolCodec]), the decoded readings go through
 * [DiagnosticReport.compose], and the compact JSON is compared byte for byte (so key order counts) with the
 * reference's. Strict cases use only bodies the wire schema allows, so the app's decoders must accept every body of
 * every one of them (no exception list) and the report must match exactly. `hostile-shape` is non-strict: a client
 * whose decoder refuses a body may report that section as null.
 */
class DiagnosticReportVectorTest {
    private val vector: JsonValue.ObjectValue by lazy {
        BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/diagnostic-report.json"))
    }

    private val cases: Map<String, JsonValue.ObjectValue> by lazy {
        vector["cases"].asArray("cases").map { it.asObject("case") }.associateBy { it["name"].asString("name") }
    }

    private enum class Slot(val key: String, val route: ApiRoute) {
        STATUS("status", ApiRoute.STATUS),
        OUTPUTS("outputs", ApiRoute.DISPLAY_OUTPUTS),
        DRM("drm", ApiRoute.DRM),
        OPERATIONS("operations", ApiRoute.OPERATION_LIST),
    }

    /** One case after the app's decoders: what they accepted, and why they refused what they did not. */
    private class Decoded(
        val bodies: Map<Slot, JsonValue.ObjectValue?>,
        val readings: DiagnosticReadings,
        val refusals: Map<Slot, String>,
    ) {
        /** The report composed from what the app held. */
        fun compose(): JsonValue.ObjectValue = DiagnosticReport.compose(readings)

        /** The same, but with the outputs body even when the decoder refused it (only the non-strict case has such a body). */
        fun composeWithRawOutputs(): JsonValue.ObjectValue = DiagnosticReport.compose(
            readings.status?.body, readings.outputs?.body ?: bodies[Slot.OUTPUTS], readings.linkFacts, readings.journal,
        )
    }

    /** A contract-fixture path (a recorded response, or a legacy plain body), an inline body, or null for "not read". */
    private fun body(spec: JsonValue?): JsonValue.ObjectValue? {
        val fields = spec as? JsonValue.ObjectValue ?: return null
        (fields["body"] as? JsonValue.ObjectValue)?.let { return it }
        val file = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/" + fields["fixture"].asString("fixture")))
        return (file["response"] as? JsonValue.ObjectValue)?.get("body") as? JsonValue.ObjectValue ?: file
    }

    private fun decode(name: String): Decoded {
        val inputs = cases.getValue(name)["inputs"].asObject("inputs")
        val bodies = Slot.entries.associateWith { body(inputs[it.key]) }
        val refusals = LinkedHashMap<Slot, String>()
        val payloads = Slot.entries.associateWith { slot ->
            val raw = bodies[slot] ?: return@associateWith null
            try {
                val reply = ProtocolCodec.decodeResponse(slot.route, HttpResponse(200, emptyMap(), BoundedJson.encode(raw)))
                (reply as? ApiReply.Versioned)?.payload ?: run { refusals[slot] = "not a v1 reply"; null }
            } catch (error: Exception) {
                refusals[slot] = error.message ?: error.javaClass.simpleName
                null
            }
        }
        return Decoded(
            bodies,
            DiagnosticReadings(
                status = payloads[Slot.STATUS] as? VersionedPayload.Status,
                outputs = payloads[Slot.OUTPUTS] as? VersionedPayload.DisplayOutputs,
                linkFacts = (payloads[Slot.DRM] as? VersionedPayload.Drm)?.facts,
                journal = (payloads[Slot.OPERATIONS] as? VersionedPayload.OperationList)?.journal,
            ),
            refusals,
        )
    }

    private fun compact(value: JsonValue) = String(BoundedJson.encode(value), Charsets.UTF_8)
    private fun expected(name: String) = cases.getValue(name)["report"].asObject("report")
    private fun strict(name: String) = cases.getValue(name)["strict"] == JsonValue.BooleanValue(true)
    private val strictNames get() = cases.keys.filter(::strict)

    // --- the vector itself --------------------------------------------------------------------------------------------

    @Test fun theVendoredVectorHasTheDocumentedFormatAndCases() {
        assertEquals("steamos-companion-report-v1", vector["format"].asString("format"))
        assertEquals(
            listOf(
                "healthy", "host-without-self-check", "degraded-host", "ambiguous-link-with-preview", "nothing-read",
                "hostile-text", "hostile-shape", "oversize-journal",
            ),
            cases.keys.toList(),
        )
        assertEquals(listOf("hostile-shape"), cases.keys.filterNot(::strict))
        cases.forEach { (name, case) ->
            val report = case["report"].asObject("report")
            assertEquals("$name format", "steamos-companion-report-v1", report["format"].asString("format"))
            assertEquals("$name sections", listOf("format", "host", "outputs", "link", "journal"), report.fields.keys.toList())
            assertEquals("$name inputs", listOf("status", "outputs", "drm", "operations"), case["inputs"].asObject("inputs").fields.keys.toList())
        }
    }

    @Test fun anUnreadSectionIsNullAndAnEmptyOneIsPresent() {
        val nothing = expected("nothing-read")
        listOf("host", "outputs", "link", "journal").forEach { assertEquals(it, JsonValue.NullValue, nothing[it]) }
        val journal = expected("degraded-host")["journal"].asObject("journal")
        assertTrue(journal["records"].asArray("records").isEmpty())
        assertEquals(JsonValue.NullValue, expected("host-without-self-check")["host"])
    }

    // --- rebuilding it --------------------------------------------------------------------------------------------------

    /**
     * What the app holds is what the report is built from: the retained response bodies for the status and the display
     * outputs (the decoded `HostSelfCheck` turns the plugin version "unknown" into null and `DisplayOutputSummary` keeps
     * no `description`, both of which the report names) and the decoded link facts and journal.
     */
    @Test fun everyStrictCaseRebuildsTheReferenceReportByteForByte() {
        assertEquals(7, strictNames.size)
        for (name in strictNames) {
            val got = decode(name).compose()
            val want = expected(name)
            // Section by section first, so a failure names the section; then the whole compact text, which also pins key order.
            for (section in listOf("host", "outputs", "link", "journal")) {
                assertEquals("$name.$section", compact(want[section]!!), compact(got[section]!!))
            }
            assertEquals(name, compact(want), compact(got))
        }
    }

    /**
     * Every body of every strict case is accepted by [ProtocolCodec] and the app's decoders: no refusal, a reading for
     * every body the case supplies, and no record, connector or output dropped on the way. If a decoder rule refuses a
     * strict body, this fails naming the case, the body and the decoder's message; it is not worked around.
     */
    @Test fun theAppsDecodersAcceptEveryBodyOfEveryStrictCase() {
        val slotsSeen = linkedSetOf<Slot>()
        for (name in strictNames) {
            val decoded = decode(name)
            assertEquals("$name refusals", emptyMap<Slot, String>(), decoded.refusals)
            val readings = decoded.readings
            val body = decoded.bodies
            assertEquals("$name status", body[Slot.STATUS] != null, readings.status != null)
            assertEquals("$name outputs", body[Slot.OUTPUTS] != null, readings.outputs != null)
            assertEquals("$name drm", body[Slot.DRM] != null, readings.linkFacts != null)
            assertEquals("$name operations", body[Slot.OPERATIONS] != null, readings.journal != null)
            body.filterValues { it != null }.keys.forEach { slotsSeen += it }

            readings.status?.let {
                assertEquals("$name self_check", body.getValue(Slot.STATUS)!!["self_check"] is JsonValue.ObjectValue, it.selfCheck != null)
            }
            readings.outputs?.let {
                assertEquals("$name outputs kept", body.getValue(Slot.OUTPUTS)!!["outputs"].asArray("outputs").size, it.outputs.size)
            }
            readings.linkFacts?.let {
                val sent = body.getValue(Slot.DRM)!!["drm"].asObject("drm")["connectors"].asArray("connectors").size
                assertEquals("$name connectors kept", sent, it.connectors.size)
            }
            readings.journal?.let {
                assertEquals("$name records kept", body.getValue(Slot.OPERATIONS)!!["operations"].asArray("operations").size, it.records.size)
            }
        }
        assertEquals("every kind of body is exercised by a strict case", Slot.entries.toSet(), slotsSeen)
    }

    /**
     * `hostile-shape` is the only case a decoder refuses, which its non-strict rule allows: its status body has no
     * `steam_bridge` and its display inventory has more than eight outputs, so the app holds neither and both
     * sections are null. Pinned, so a decoder change that moves a refusal is noticed.
     */
    @Test fun onlyTheNonStrictCaseHasBodiesTheDecodersRefuse() {
        val refused = cases.keys.associateWith { name -> decode(name).refusals.mapKeys { it.key.key } }.filterValues { it.isNotEmpty() }
        assertEquals(setOf("hostile-shape"), refused.keys)
        assertEquals(setOf("status", "outputs"), refused.getValue("hostile-shape").keys)
        assertTrue(refused.getValue("hostile-shape").getValue("status").contains("steam_bridge"))
        assertTrue(refused.getValue("hostile-shape").getValue("outputs").contains("Too many display outputs"))
    }

    @Test fun hostileShapeNeverFailsLeaksNothingAndEachSectionIsTheReferenceNullOrADecoderReduction() {
        val decoded = decode("hostile-shape")
        val got = decoded.compose()
        val want = expected("hostile-shape")
        assertEquals(setOf("format", "host", "outputs", "link", "journal"), got.fields.keys)
        val outcomes = listOf("host", "outputs", "link", "journal").associateWith { outcome(got[it]!!, want[it]!!) }
        // Pinned, so a decoder change that moves a section is noticed. The status body has no steam_bridge, so the
        // decoder refuses it (host null); the outputs body is refused too (more than eight outputs), so the app holds
        // none (null); the link decoder drops every connector the schema does not describe (none of the fourteen sent
        // has is_internal and connected as booleans), which is a record-level reduction, not the reference's eight;
        // every journal record carries a nine-key `target` and the journal decoder keeps a target's first eight keys,
        // so each record is the reference's without its ninth key.
        assertEquals(
            mapOf("host" to Outcome.NULL, "outputs" to Outcome.NULL, "link" to Outcome.REDUCED, "journal" to Outcome.TARGETS_REDUCED),
            outcomes,
        )
        assertEquals(setOf("action"), droppedTargetKeys(got["journal"]!!, want["journal"]!!))
        assertNoForbiddenValues("hostile-shape", compact(got), decoded.bodies.values.toList())
        // Handed the retained response bodies directly, the composer reproduces the reference for what it reads from them.
        val raw = decoded.composeWithRawOutputs()
        assertEquals(Outcome.EXACT, outcome(raw["outputs"]!!, want["outputs"]!!))
        val rawHost = DiagnosticReport.compose(decoded.bodies[Slot.STATUS], null, null, null)
        assertEquals(compact(want["host"]!!), compact(rawHost["host"]!!))
    }

    private enum class Outcome { EXACT, NULL, REDUCED, TARGETS_REDUCED, MISMATCH }

    /**
     * Equal, null, the reference with whole records dropped (same other fields, records in the reference's order), or
     * the reference with a record's `target` cut to the keys the decoder keeps (see [droppedTargetKeys]).
     */
    private fun outcome(got: JsonValue, want: JsonValue): Outcome = when {
        compact(got) == compact(want) -> Outcome.EXACT
        got == JsonValue.NullValue -> Outcome.NULL
        got is JsonValue.ObjectValue && want is JsonValue.ObjectValue && listOf("connectors", "records").any { isReduction(got, want, it) } ->
            Outcome.REDUCED
        got is JsonValue.ObjectValue && want is JsonValue.ObjectValue && isTargetReduction(got, want) -> Outcome.TARGETS_REDUCED
        else -> Outcome.MISMATCH
    }

    private fun records(journal: JsonValue) = (journal as? JsonValue.ObjectValue)?.get("records") as? JsonValue.ArrayValue

    /** The same journal, record for record, except that a record's `target` has fewer keys (the others in the same order and equal). */
    private fun isTargetReduction(got: JsonValue.ObjectValue, want: JsonValue.ObjectValue): Boolean {
        val gotRecords = records(got)?.values ?: return false
        val wantRecords = records(want)?.values ?: return false
        if (got.fields.filterKeys { it != "records" } != want.fields.filterKeys { it != "records" } || gotRecords.size != wantRecords.size) return false
        var reduced = false
        for ((g, w) in gotRecords.zip(wantRecords)) {
            if (g !is JsonValue.ObjectValue || w !is JsonValue.ObjectValue) return false
            if (g.fields.filterKeys { it != "target" } != w.fields.filterKeys { it != "target" }) return false
            val gotTarget = (g["target"] as? JsonValue.ObjectValue)?.fields.orEmpty()
            val wantTarget = (w["target"] as? JsonValue.ObjectValue)?.fields.orEmpty()
            val kept = wantTarget.entries.filter { gotTarget[it.key] == it.value }
            if (gotTarget.keys.toList() != kept.map { it.key } || gotTarget.size > wantTarget.size) return false
            if (gotTarget.size < wantTarget.size) reduced = true
        }
        return reduced
    }

    /** The target keys the reference reports and the app's journal does not, over all records. */
    private fun droppedTargetKeys(got: JsonValue, want: JsonValue): Set<String> {
        val gotRecords = records(got)!!.values
        return records(want)!!.values.zip(gotRecords).flatMap { (w, g) ->
            val gotTarget = (g.asObject("record")["target"] as? JsonValue.ObjectValue)?.fields.orEmpty()
            (w.asObject("record")["target"] as? JsonValue.ObjectValue)?.fields.orEmpty().keys - gotTarget.keys
        }.toSet()
    }

    private fun isReduction(got: JsonValue.ObjectValue, want: JsonValue.ObjectValue, listKey: String): Boolean {
        val gotList = (got[listKey] as? JsonValue.ArrayValue)?.values ?: return false
        val wantList = (want[listKey] as? JsonValue.ArrayValue)?.values ?: return false
        if (got.fields.keys != want.fields.keys) return false
        if (got.fields.filterKeys { it != listKey && it != "count" } != want.fields.filterKeys { it != listKey && it != "count" }) return false
        var from = 0
        for (record in gotList) {
            val at = wantList.drop(from).indexOfFirst { compact(it) == compact(record) }
            if (at < 0) return false
            from += at + 1
        }
        return true
    }

    // --- nothing private survives ----------------------------------------------------------------------------------------

    private val secretKeys = setOf(
        "host_id", "boot_id", "certificate_fingerprint", "fingerprint_short", "mac", "token", "pairing_session", "request_id",
        "preview_id", "profile_id", "address", "serial", "edid",
    )
    private val secretPrefixes = listOf("op-", "profile-", "req-", "host-", "boot-", "fixture-token", "sha256:")
    private val secretText = listOf("192.168", "aa:bb:cc:dd:ee:ff", "fixture-token", "evil.example", "/run/user", "secret", "sha256:", "http://", "https://")

    /** Every string in an input body that a report must never repeat: under a private key, or shaped like an identifier. */
    private fun sentinels(value: JsonValue?, key: String? = null, found: MutableSet<String> = linkedSetOf()): Set<String> {
        when (value) {
            is JsonValue.ObjectValue -> value.fields.forEach { (name, item) -> sentinels(item, name, found) }
            is JsonValue.ArrayValue -> value.values.forEach { sentinels(it, key, found) }
            is JsonValue.StringValue -> if (value.value.length >= 6 && (key in secretKeys || secretPrefixes.any { value.value.startsWith(it) })) found += value.value
            else -> Unit
        }
        return found
    }

    private fun assertNoForbiddenValues(label: String, text: String, bodies: List<JsonValue?>) {
        sentinels(JsonValue.ArrayValue(bodies.filterNotNull())).forEach { assertFalse("$label repeats $it", text.contains(it)) }
        secretText.forEach { assertFalse("$label contains $it", text.contains(it)) }
    }

    @Test fun noCaseRepeatsAnIdentifierAddressPinOrTokenFromItsInputs() {
        var scanned = 0
        for (name in cases.keys) {
            val decoded = decode(name)
            val bodies = decoded.bodies.values.toList()
            // Both routes: what the app would hold, and what the retained bodies alone give.
            for (report in listOf(decoded.compose(), decoded.composeWithRawOutputs())) {
                assertNoForbiddenValues(name, compact(report), bodies)
                scanned += sentinels(JsonValue.ArrayValue(bodies.filterNotNull())).size
            }
            // And the reference's own reports must pass the same scan, or the scan proves nothing.
            assertNoForbiddenValues("$name (reference)", compact(expected(name)), bodies)
        }
        assertTrue("the scan found no sentinels to look for", scanned > 100)
    }

    @Test fun theScanFindsWhatItIsMeantToFind() {
        val text = compact(decode("hostile-text").composeWithRawOutputs())
        val sentinels = sentinels(JsonValue.ArrayValue(decode("hostile-text").bodies.values.filterNotNull()))
        assertTrue(sentinels.any { it.startsWith("op-secret-") })
        assertTrue(sentinels.any { it.startsWith("req-secret-") })
        assertTrue(sentinels.any { it.startsWith("host-") })
        assertTrue(sentinels.any { it == "profile-secret" })
        // The scrubbed forms are what remain.
        assertTrue(text.contains("[ip]") && text.contains("[url]") && text.contains("[pin]") && text.contains("[mac]") && text.contains("[token]") && text.contains("[path]"))
    }

    @Test fun everyReportStaysWithinItsByteLimit() {
        for (name in cases.keys) {
            val decoded = decode(name)
            assertTrue(name, BoundedJson.encode(expected(name)).size <= DiagnosticReport.MAX_REPORT_BYTES)
            assertTrue(name, BoundedJson.encode(decoded.compose()).size <= DiagnosticReport.MAX_REPORT_BYTES)
            assertTrue(name, BoundedJson.encode(decoded.composeWithRawOutputs()).size <= DiagnosticReport.MAX_REPORT_BYTES)
        }
        assertTrue(BoundedJson.encode(expected("oversize-journal")).size > 11_000)
    }
}
