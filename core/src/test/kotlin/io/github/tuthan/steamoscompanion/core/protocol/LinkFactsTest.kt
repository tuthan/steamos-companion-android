package io.github.tuthan.steamoscompanion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `GET /v1/drm` is advisory. These tests pin what the vendored fixtures carry and, above all, that no
 * shape of the body can make the read fail or throw: it decodes to "no facts" at worst.
 */
class LinkFactsTest {
    // --- pinned fixtures -------------------------------------------------------------------

    @Test fun availableFixtureCarriesTheDocumentedFacts() {
        val facts = checkNotNull(drm("available").facts)
        assertTrue(facts.available)
        assertEquals("2026-09-13T12:00:00.250000Z", facts.observedAt)
        assertNull(facts.reason)
        assertEquals(listOf("DP-1", "HDMI-A-1", "HDMI-A-2", "eDP-1"), facts.connectors.map { it.connector })

        val hdmi = facts.connectors.first { it.connector == "HDMI-A-1" }
        assertEquals(
            LinkConnector("HDMI-A-1", "drm:card0", "LG TV SSCR2", "GSM", 49780, isInternal = false, connected = true,
                resolutions = listOf("3840x2160", "1920x1080"), steamOutputId = "1"),
            hdmi,
        )
        val dp = facts.connectors.first { it.connector == "DP-1" }
        assertNull("the host did not join DP-1 to a Steam output", dp.steamOutputId)
        assertEquals(listOf("3840x2160", "2560x1440", "1920x1080"), dp.resolutions)

        val unplugged = facts.connectors.first { it.connector == "HDMI-A-2" }
        assertFalse(unplugged.connected)
        assertEquals(emptyList<String>(), unplugged.resolutions)
        assertNull(unplugged.monitorVendor)
        assertNull(unplugged.monitorProductId)
        assertEquals("a name equal to the connector is the host's fallback", "HDMI-A-2", unplugged.displayName)

        val panel = facts.connectors.first { it.connector == "eDP-1" }
        assertTrue(panel.isInternal)
        assertEquals(listOf("1280x800"), panel.resolutions)
    }

    @Test fun ambiguousConnectorFixtureLeavesBothDp1ConnectorsUnjoined() {
        val facts = checkNotNull(drm("ambiguous-connector").facts)
        val dp = facts.connectors.filter { it.connector == "DP-1" }
        assertEquals(listOf("drm:card0", "drm:card1"), dp.map { it.gpuId })
        assertTrue(dp.all { it.connected && it.steamOutputId == null })
        assertEquals("1", facts.connectors.single { it.connector == "HDMI-A-1" }.steamOutputId)
    }

    @Test fun bridgeUnavailableFixtureJoinsNothing() {
        val facts = checkNotNull(drm("bridge-unavailable").facts)
        assertEquals(4, facts.connectors.size)
        assertTrue(facts.connectors.all { it.steamOutputId == null })
    }

    @Test fun noConnectorsFixtureIsUnavailableNotAnError() {
        val facts = checkNotNull(drm("no-connectors").facts)
        assertFalse(facts.available)
        assertNull(facts.observedAt)
        assertEquals("Linux DRM connectors are not readable on this host", facts.reason)
        assertEquals(emptyList<LinkConnector>(), facts.connectors)
    }

    @Test fun unauthorizedFixtureIsTheErrorEnvelope() {
        val reply = decodeFixture("unauthorized")
        reply as ApiReply.Rejected
        assertEquals(401, reply.status)
        assertEquals("unauthorized", reply.error)
    }

    @Test fun anOlderHostAnswers404NotFoundWhichIsJustARejection() {
        val body = """{"error":"not_found","message":"not found"}""".toByteArray()
        val reply = ProtocolCodec.decodeResponse(ApiRoute.DRM, HttpResponse(404, emptyMap(), body))
        reply as ApiReply.Rejected
        assertEquals("not_found", reply.error)
    }

    // --- the route -------------------------------------------------------------------------

    @Test fun theRouteIsAnAuthenticatedGetWithNoBodyAndNoQuery() {
        assertEquals(ApiRoute.DRM, ApiRoute.match(HttpMethod.GET, "/v1/drm").route)
        assertTrue(ApiRoute.DRM.requiresBearer)
        assertEquals(HttpMethod.GET, ApiRoute.DRM.method)
        listOf("/v1/drm?x=1", "/v1/drm?", "/v1/drm#a", "/v1/drm/", "/v1/DRM", "https://host/v1/drm").forEach {
            try {
                ApiRoute.match(HttpMethod.GET, it)
                fail("$it must be rejected")
            } catch (_: RequestValidationException) {
            }
        }
        try {
            ApiRoute.match(HttpMethod.POST, "/v1/drm")
            fail("POST must be rejected")
        } catch (_: RequestValidationException) {
        }
        ApiRequest(HttpMethod.GET, "/v1/drm", bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx").validate()
        try {
            ApiRequest(HttpMethod.GET, "/v1/drm").validate()
            fail("an unauthenticated read must be rejected")
        } catch (_: RequestValidationException) {
        }
        try {
            ApiRequest(HttpMethod.GET, "/v1/drm", jsonObject(), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx").validate()
            fail("a body must be rejected")
        } catch (_: RequestValidationException) {
        }
    }

    // --- never fails a read ----------------------------------------------------------------

    @Test fun absentBlockIsNoFactsAndStillAVersionedReply() {
        assertNull(decode(body("available") - "drm").facts)
    }

    @Test fun blockThatIsNotAnObjectIsNoFacts() {
        listOf<JsonValue>(
            JsonValue.NullValue, "facts".asJson(), 5L.asJson(), true.asJson(), JsonValue.ArrayValue(emptyList()),
            JsonValue.ArrayValue(listOf(JsonValue.ObjectValue(emptyMap()))),
        ).forEach { assertNull(it.toString(), decode(body("available") + ("drm" to it)).facts) }
    }

    @Test fun anEmptyOrUnknownOnlyBlockIsNoFacts() {
        assertNull(decode(body("available") + ("drm" to JsonValue.ObjectValue(emptyMap()))).facts)
        assertNull(decode(withDrm("available") { it.clear(); it["future_only"] = "x".asJson() }).facts)
    }

    @Test fun aMistypedAvailableFlagIsNoFacts() {
        listOf<JsonValue>("true".asJson(), 1L.asJson(), JsonValue.NullValue, JsonValue.ObjectValue(emptyMap()))
            .forEach { value -> assertNull(value.toString(), decode(withDrm("available") { it["available"] = value }).facts) }
        assertNull(decode(withDrm("available") { it.remove("available") }).facts)
    }

    @Test fun availableWithoutAnArrayOfConnectorsIsNoFacts() {
        assertNull(decode(withDrm("available") { it.remove("connectors") }).facts)
        listOf<JsonValue>(JsonValue.NullValue, "DP-1".asJson(), jsonObject("connector" to "DP-1".asJson()), 3L.asJson())
            .forEach { value -> assertNull(value.toString(), decode(withDrm("available") { it["connectors"] = value }).facts) }
    }

    @Test fun unavailableIsKeptAsNoConnectorsEvenIfTheHostSentSome() {
        val facts = checkNotNull(decode(withDrm("available") { it["available"] = false.asJson() }).facts)
        assertFalse(facts.available)
        assertEquals(emptyList<LinkConnector>(), facts.connectors)
    }

    @Test fun moreThanSixteenConnectorsIsNotAConformingHostSoNoFacts() {
        val sixteen = (0 until 16).map { connector("DP-$it", "drm:card0") }
        val facts = checkNotNull(decode(withConnectors(sixteen)).facts)
        assertEquals(16, facts.connectors.size)
        assertNull(decode(withConnectors(sixteen + connector("DP-16", "drm:card0"))).facts)
    }

    @Test fun anEntryThatIsNotAnObjectIsDroppedAndTheRestKept() {
        val facts = checkNotNull(decode(withConnectors(listOf(
            "HDMI-A-1".asJson(), JsonValue.NullValue, 5L.asJson(), JsonValue.ArrayValue(emptyList()), connector("DP-2", "drm:card0"),
        ))).facts)
        assertEquals(listOf("DP-2"), facts.connectors.map { it.connector })
    }

    @Test fun aMalformedConnectorIsDroppedWithoutFailingTheOthers() {
        val bad = listOf<(MutableMap<String, JsonValue>) -> Unit>(
            { it.remove("connector") },
            { it["connector"] = "".asJson() },
            { it["connector"] = "1DP".asJson() },
            { it["connector"] = "DP 1".asJson() },
            { it["connector"] = ("D" + "P".repeat(64)).asJson() },
            { it["connector"] = 7L.asJson() },
            { it.remove("gpu_id") },
            { it["gpu_id"] = "card0".asJson() },
            { it["gpu_id"] = "drm:card".asJson() },
            { it["gpu_id"] = "drm:card12345".asJson() },
            { it["gpu_id"] = "drm:card0\n".asJson() },
            { it.remove("is_internal") },
            { it["is_internal"] = "false".asJson() },
            { it["is_internal"] = JsonValue.NullValue },
            { it.remove("connected") },
            { it["connected"] = 1L.asJson() },
            { it.remove("resolutions") },
            { it["resolutions"] = "3840x2160".asJson() },
            { it["resolutions"] = JsonValue.NullValue },
        )
        bad.forEachIndexed { index, edit ->
            val facts = checkNotNull(decode(withConnectors(listOf(connector("DP-9", "drm:card0", edit), connector("HDMI-A-1", "drm:card0")))).facts)
            assertEquals("case $index", listOf("HDMI-A-1"), facts.connectors.map { it.connector })
        }
    }

    @Test fun oneOddResolutionDropsTheWholeConnectorBecauseSkippingItCouldInventAMissingMode() {
        val odd = listOf<JsonValue>(
            "3440x1440@60".asJson(), "0x1080".asJson(), "1920x0".asJson(), "1920 x 1080".asJson(), "1920X1080".asJson(),
            "123456x1080".asJson(), "01920x1080".asJson(), "-1920x1080".asJson(), "1920x1080\n".asJson(), "".asJson(),
            5L.asJson(), JsonValue.NullValue, jsonObject("width" to 1920L.asJson()),
        )
        odd.forEach { entry ->
            val facts = checkNotNull(decode(withConnectors(listOf(
                connector("HDMI-A-1", "drm:card0") { it["resolutions"] = JsonValue.ArrayValue(listOf("1920x1080".asJson(), entry)) },
                connector("DP-1", "drm:card0"),
            ))).facts)
            assertEquals(entry.toString(), listOf("DP-1"), facts.connectors.map { it.connector })
        }
    }

    @Test fun resolutionsAreDeduplicatedInHostOrderAndBoundedAt256() {
        val deduped = checkNotNull(decode(withConnectors(listOf(connector("DP-1", "drm:card0") {
            it["resolutions"] = JsonValue.ArrayValue(listOf("1920x1080", "3840x2160", "1920x1080").map(String::asJson))
        }))).facts)
        assertEquals(listOf("1920x1080", "3840x2160"), deduped.connectors.single().resolutions)

        fun many(count: Int) = (1..count).map { "${100 + it}x200".asJson() }
        val atLimit = checkNotNull(decode(withConnectors(listOf(connector("DP-1", "drm:card0") {
            it["resolutions"] = JsonValue.ArrayValue(many(256))
        }))).facts)
        assertEquals(256, atLimit.connectors.single().resolutions.size)
        val over = checkNotNull(decode(withConnectors(listOf(connector("DP-1", "drm:card0") {
            it["resolutions"] = JsonValue.ArrayValue(many(257))
        }, connector("HDMI-A-1", "drm:card0")))).facts)
        assertEquals("a 257-entry list is not conforming; the connector is dropped", listOf("HDMI-A-1"), over.connectors.map { it.connector })
    }

    @Test fun aMaximalConformingBodyStillPassesTheStrictParserAndTheRouteLimit() {
        val resolutions = JsonValue.ArrayValue((1..256).map { "${10000 + it}x${20000 + it}".asJson() })
        val connectors = (0 until 16).map { connector("DP-$it", "drm:card1") { c -> c["resolutions"] = resolutions } }
        val bytes = BoundedJson.encode(withConnectors(connectors))
        assertTrue("body must stay under the route limit, was ${bytes.size}", bytes.size < ApiRoute.DRM.maxResponseBytes)
        val facts = checkNotNull((decodeBytes(bytes) as VersionedPayload.Drm).facts)
        assertEquals(16, facts.connectors.size)
        assertTrue(facts.connectors.all { it.resolutions.size == 256 })
    }

    @Test fun aBadOptionalFieldDegradesOnlyThatField() {
        val facts = checkNotNull(decode(withConnectors(listOf(connector("HDMI-A-1", "drm:card0") {
            it["monitor_vendor"] = "gsm".asJson()
            it["monitor_product_id"] = JsonValue.NumberValue("1.5")
            it["display_name"] = 7L.asJson()
            it["steam_output_id"] = 1L.asJson()
        }, connector("DP-1", "drm:card0") {
            it["monitor_vendor"] = "GSMX".asJson()
            it["monitor_product_id"] = 70000L.asJson()
            it["display_name"] = "".asJson()
            it["steam_output_id"] = "".asJson()
        }, connector("DP-2", "drm:card0") {
            it["monitor_vendor"] = JsonValue.NullValue
            it["monitor_product_id"] = (-1L).asJson()
            it["display_name"] = "x".repeat(129).asJson()
            it["steam_output_id"] = "o".repeat(129).asJson()
        }, connector("DP-3", "drm:card0") {
            it["display_name"] = "Bad\u0007name".asJson()
            it["steam_output_id"] = "id\n".asJson()
            it["monitor_product_id"] = "41340".asJson()
        }))).facts)
        facts.connectors.forEach {
            assertNull(it.connector, it.monitorVendor)
            assertNull(it.connector, it.monitorProductId)
            assertEquals(it.connector, it.displayName)
            assertNull(it.connector, it.steamOutputId)
            assertTrue(it.connected)
        }
        assertEquals(4, facts.connectors.size)
    }

    @Test fun boundaryValuesAreKept() {
        val facts = checkNotNull(decode(withConnectors(listOf(
            connector("A", "drm:card0") { it["monitor_product_id"] = 0L.asJson() },
            connector("B", "drm:card1234") { it["monitor_product_id"] = 65535L.asJson(); it["steam_output_id"] = "o".repeat(128).asJson()
                it["display_name"] = "n".repeat(128).asJson() },
        ))).facts)
        assertEquals(0, facts.connectors[0].monitorProductId)
        assertEquals(65535, facts.connectors[1].monitorProductId)
        assertEquals("o".repeat(128), facts.connectors[1].steamOutputId)
        assertEquals("n".repeat(128), facts.connectors[1].displayName)
        assertEquals("drm:card1234", facts.connectors[1].gpuId)
    }

    @Test fun unknownKeysAreIgnoredAtEveryLevel() {
        val baseline = decode(body("available")).facts
        val extended = decode(withDrm("available") {
            it["future_section"] = jsonObject("anything" to 1L.asJson())
            val rewritten = (it.getValue("connectors") as JsonValue.ArrayValue).values.map { c ->
                JsonValue.ObjectValue((c as JsonValue.ObjectValue).fields + ("added_later" to "ignored".asJson()))
            }
            it["connectors"] = JsonValue.ArrayValue(rewritten)
        }).facts
        assertEquals(baseline, extended)
    }

    @Test fun oversizeOrOddObservedAtAndReasonAreDroppedButTheFactsKept() {
        val facts = checkNotNull(decode(withDrm("available") {
            it["observed_at"] = "t".repeat(65).asJson()
            it["reason"] = "r".repeat(257).asJson()
        }).facts)
        assertNull(facts.observedAt)
        assertNull(facts.reason)
        assertEquals(4, facts.connectors.size)
        val control = checkNotNull(decode(withDrm("available") {
            it["observed_at"] = "2026\u0000".asJson()
            it["reason"] = 5L.asJson()
        }).facts)
        assertNull(control.observedAt)
        assertNull(control.reason)
    }

    @Test fun aDroppedConnectorStillCancelsTheJoinOfAnotherClaimingTheSameOutput() {
        val facts = checkNotNull(decode(withConnectors(listOf(
            connector("HDMI-A-1", "drm:card0") { it["steam_output_id"] = "1".asJson() },
            // Malformed (odd resolution) yet it claims output "1" too: the join is no longer unique.
            connector("DP-1", "drm:card0") {
                it["steam_output_id"] = "1".asJson()
                it["resolutions"] = JsonValue.ArrayValue(listOf("not-a-resolution".asJson()))
            },
            connector("DP-2", "drm:card0") { it["steam_output_id"] = "2".asJson() },
        ))).facts)
        assertEquals(listOf("HDMI-A-1", "DP-2"), facts.connectors.map { it.connector })
        assertNull("output 1 is claimed by two connectors, so neither is joined", facts.connectors[0].steamOutputId)
        assertEquals("2", facts.connectors[1].steamOutputId)
    }

    @Test fun noShapeOfTheBlockEverThrowsOrTurnsTheReplyIntoAFailure() {
        val shapes = listOf<JsonValue>(
            JsonValue.NullValue, JsonValue.ObjectValue(emptyMap()), "x".asJson(), JsonValue.ArrayValue(listOf(JsonValue.NullValue)),
            jsonObject("available" to true.asJson(), "connectors" to JsonValue.ArrayValue(listOf(JsonValue.ArrayValue(emptyList())))),
            jsonObject("available" to true.asJson(), "connectors" to JsonValue.ArrayValue(listOf(jsonObject()))),
            jsonObject("available" to true.asJson(), "connectors" to JsonValue.ArrayValue(listOf(jsonObject("resolutions" to jsonObject())))),
            jsonObject("available" to false.asJson(), "connectors" to 9L.asJson(), "reason" to JsonValue.ArrayValue(emptyList())),
        )
        shapes.forEach { shape ->
            val reply = ProtocolCodec.decodeResponse(ApiRoute.DRM,
                HttpResponse(200, emptyMap(), BoundedJson.encode(jsonObject("protocol_version" to 1L.asJson(), "drm" to shape))))
            assertTrue(shape.toString(), reply is ApiReply.Versioned)
        }
    }

    @Test fun theStatusReadIsUnaffectedByAnything() {
        // The status route has no knowledge of the DRM block: a status body carrying a hostile "drm" key still decodes.
        val reply = ProtocolCodec.decodeResponse(ApiRoute.STATUS, HttpResponse(200, emptyMap(), BoundedJson.encode(
            statusBody() + ("drm" to JsonValue.ArrayValue(listOf(JsonValue.NullValue))))))
        assertTrue(reply is ApiReply.Versioned)
        assertNotNull(((reply as ApiReply.Versioned).payload as VersionedPayload.Status).hostId)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun fixtureBody(name: String): JsonValue.ObjectValue {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/drm/$name.json"))
        return fixture["response"].asObject("response")["body"].asObject("body")
    }

    private fun body(name: String) = fixtureBody(name)

    private fun statusBody(): JsonValue.ObjectValue {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/status/sunshine-running.json"))
        return fixture["response"].asObject("response")["body"].asObject("body")
    }

    private fun drm(name: String): VersionedPayload.Drm = decode(body(name))

    private fun decodeFixture(name: String): ApiReply {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/drm/$name.json"))
        val response = fixture["response"].asObject("response")
        return ProtocolCodec.decodeResponse(ApiRoute.DRM,
            HttpResponse(response["status"].asLong("status").toInt(), emptyMap(), BoundedJson.encode(response["body"]!!)))
    }

    private fun decode(body: JsonValue.ObjectValue): VersionedPayload.Drm = decodeBytes(BoundedJson.encode(body)) as VersionedPayload.Drm

    private fun decodeBytes(bytes: ByteArray): VersionedPayload {
        val reply = ProtocolCodec.decodeResponse(ApiRoute.DRM, HttpResponse(200, emptyMap(), bytes))
        assertTrue("the read must still succeed: $reply", reply is ApiReply.Versioned)
        return (reply as ApiReply.Versioned).payload
    }

    /** Copy of the fixture body whose `drm` fields have been edited. */
    private fun withDrm(name: String, edit: (MutableMap<String, JsonValue>) -> Unit): JsonValue.ObjectValue {
        val fields = body(name).fields.toMutableMap()
        val block = LinkedHashMap((fields.getValue("drm") as JsonValue.ObjectValue).fields)
        edit(block)
        fields["drm"] = JsonValue.ObjectValue(block)
        return JsonValue.ObjectValue(fields)
    }

    private fun withConnectors(connectors: List<JsonValue>) =
        withDrm("available") { it["connectors"] = JsonValue.ArrayValue(connectors) }

    /** A conforming connector record that [edit] may then corrupt. */
    private fun connector(
        name: String,
        gpu: String,
        edit: (MutableMap<String, JsonValue>) -> Unit = {},
    ): JsonValue.ObjectValue {
        val fields = linkedMapOf<String, JsonValue>(
            "connector" to name.asJson(),
            "gpu_id" to gpu.asJson(),
            "display_name" to name.asJson(),
            "monitor_vendor" to JsonValue.NullValue,
            "monitor_product_id" to JsonValue.NullValue,
            "is_internal" to false.asJson(),
            "connected" to true.asJson(),
            "resolutions" to JsonValue.ArrayValue(listOf("1920x1080".asJson())),
            "steam_output_id" to JsonValue.NullValue,
        )
        edit(fields)
        return JsonValue.ObjectValue(fields)
    }

    private operator fun JsonValue.ObjectValue.minus(key: String) = JsonValue.ObjectValue(fields - key)
    private operator fun JsonValue.ObjectValue.plus(entry: Pair<String, JsonValue>) = JsonValue.ObjectValue(fields + entry)
}
