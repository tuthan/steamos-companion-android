package io.github.tuthan.steamoscompanion.core.protocol

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `self_check` is optional diagnosis on an authenticated status response. These tests pin what the
 * vendored fixtures carry and, above all, that no shape of the block can make a status read fail.
 */
class HostSelfCheckTest {
    // --- pinned fixtures -------------------------------------------------------------------

    @Test fun everyStatusSuccessFixtureDecodesAndExposesAFullBlock() {
        val index = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/index.json"))
        val cases = index["cases"].asArray("cases").map { it.asObject("case") }.filter {
            it["path"].asString("path") == "/v1/status" && it["status"].asLong("status") == 200L
        }.map { it["case"].asString("case") }
        assertEquals(11, cases.size)
        cases.forEach { name ->
            val check = status(name.removePrefix("status/")).selfCheck
            assertNotNull("$name must expose self_check", check)
            check!!
            assertNotNull("$name certificate", check.certificate)
            assertNotNull("$name listener", check.listener)
            assertNotNull("$name bridge", check.bridge)
            assertNotNull("$name temperature", check.temperature)
            assertNotNull("$name provider", check.provider)
            assertEquals("$name protocol", 1, check.versions?.protocol)
        }
    }

    @Test fun runningFixtureCarriesTheDocumentedValues() {
        val check = checkNotNull(status("sunshine-running").selfCheck)
        assertEquals(
            HostCertificateCheck(true, "aaaaaaaa", Instant.parse("2026-09-13T11:59:00Z"), Instant.parse("2036-09-10T11:59:00Z")),
            check.certificate,
        )
        assertEquals(HostListenerCheck(true, "0.0.0.0", 18443), check.listener)
        assertEquals(HostBridgeCheck(true, listOf("display", "restart", "shutdown", "suspend")), check.bridge)
        assertEquals(WireValue.Known(TemperatureSource.HWMON), check.temperature?.source)
        assertEquals(HostProviderCheck(true, WireValue.Known(SunshineState.RUNNING), false, "1"), check.provider)
        assertEquals(HostVersionsCheck(1, "0.0.0-fixture"), check.versions)
    }

    @Test fun certificateUnusableFixtureHasNoDatesOrFingerprint() {
        val certificate = checkNotNull(status("self-check-certificate-unusable").selfCheck?.certificate)
        assertEquals(HostCertificateCheck(false, null, null, null), certificate)
    }

    @Test fun pluginVersionUnknownFixtureIsNeverTurnedIntoAVersion() {
        val versions = checkNotNull(status("self-check-plugin-version-unknown").selfCheck?.versions)
        assertNull(versions.plugin)
        assertEquals(1, versions.protocol)
    }

    @Test fun steamTemperatureFixtureNamesSteamAsTheSource() {
        assertEquals(
            WireValue.Known(TemperatureSource.STEAM),
            status("self-check-steam-temperature").selfCheck?.temperature?.source,
        )
    }

    @Test fun legacyBodyFixturesPredateTheBlockAndDecodeWithoutIt() {
        listOf("sunshine-disabled", "sunshine-running", "sunshine-stopped", "sunshine-unknown", "steam-unavailable").forEach {
            val reply = ProtocolCodec.decodeResponse(ApiRoute.STATUS,
                HttpResponse(200, emptyMap(), ProtocolFixtureAssertions.readResource("fixtures/status-$it.json")))
            assertNull(it, ((reply as ApiReply.Versioned).payload as VersionedPayload.Status).selfCheck)
        }
    }

    // --- never fails a status read ---------------------------------------------------------

    @Test fun absentBlockDecodesAsNull() {
        val payload = decode(body("sunshine-running") - "self_check")
        assertNull(payload.selfCheck)
        assertEquals("host-fixture0001xxxxxxxxxxxxx", payload.hostId)
    }

    @Test fun blockThatIsNotAnObjectIsNull() {
        listOf<JsonValue>(
            JsonValue.NullValue, "healthy".asJson(), 5L.asJson(), true.asJson(), JsonValue.ArrayValue(emptyList()),
            JsonValue.ArrayValue(listOf(JsonValue.ObjectValue(emptyMap()))),
        ).forEach { assertNull(it.toString(), decode(body("sunshine-running") + ("self_check" to it)).selfCheck) }
    }

    @Test fun blockWithNothingUsableInItIsNull() {
        assertNull(decode(body("sunshine-running") + ("self_check" to JsonValue.ObjectValue(emptyMap()))).selfCheck)
        assertNull(decode(withBlock("sunshine-running") { it.clear(); it["future_only"] = "x".asJson() }).selfCheck)
        val wrongEverywhere = withBlock("sunshine-running") {
            it["certificate"] = 1L.asJson()
            it["listener"] = JsonValue.ArrayValue(emptyList())
            it["bridge"] = "bridge".asJson()
            it["temperature"] = JsonValue.NullValue
            it["provider"] = true.asJson()
            it["versions"] = 3L.asJson()
        }
        assertNull(decode(wrongEverywhere).selfCheck)
    }

    @Test fun aWronglyTypedFieldDegradesOnlyItsSection() {
        val payload = decode(withBlock("sunshine-running") {
            it["certificate"] = section(it, "certificate") { c -> c["ready"] = "yes".asJson() }
        })
        val check = checkNotNull(payload.selfCheck)
        assertNull(check.certificate)
        assertEquals(HostListenerCheck(true, "0.0.0.0", 18443), check.listener)
        assertEquals(HostVersionsCheck(1, "0.0.0-fixture"), check.versions)
    }

    @Test fun aWronglyTypedOptionalFieldDegradesOnlyThatField() {
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["listener"] = section(it, "listener") { l -> l["port"] = "18443".asJson(); l["address"] = 7L.asJson() }
            it["versions"] = section(it, "versions") { v -> v["protocol"] = "1".asJson(); v["plugin"] = false.asJson() }
        }).selfCheck)
        assertEquals(HostListenerCheck(true, null, null), check.listener)
        assertEquals(HostVersionsCheck(null, null), check.versions)
    }

    @Test fun unknownKeysAreIgnoredAtEveryLevel() {
        val baseline = decode(body("sunshine-running")).selfCheck
        val extended = decode(withBlock("sunshine-running") {
            it["future_section"] = jsonObject("anything" to 1L.asJson())
            for (name in listOf("certificate", "listener", "bridge", "temperature", "provider", "versions")) {
                it[name] = section(it, name) { s -> s["added_later"] = "ignored".asJson() }
            }
        }).selfCheck
        assertEquals(baseline, extended)
    }

    @Test fun oversizeStringsAreDroppedWithoutFailingTheRead() {
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["listener"] = section(it, "listener") { l -> l["address"] = "a".repeat(65).asJson() }
            it["provider"] = section(it, "provider") { p -> p["contract_version"] = "9".repeat(65).asJson() }
            it["versions"] = section(it, "versions") { v -> v["plugin"] = "1".repeat(3000).asJson() }
            it["certificate"] = section(it, "certificate") { c -> c["fingerprint_short"] = "a".repeat(500).asJson() }
        }).selfCheck)
        assertEquals(HostListenerCheck(true, null, 18443), check.listener)
        assertNull(check.provider?.contractVersion)
        assertEquals(WireValue.Known(SunshineState.RUNNING), check.provider?.state)
        assertEquals(HostVersionsCheck(1, null), check.versions)
        assertNull(check.certificate?.fingerprintShort)
        assertEquals(true, check.certificate?.ready)
    }

    @Test fun anOversizeEnumValueDropsItsSection() {
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["temperature"] = jsonObject("source" to "x".repeat(65).asJson())
        }).selfCheck)
        assertNull(check.temperature)
        assertNotNull(check.provider)
    }

    @Test fun controlCharactersInTextAreRejectedNotDisplayed() {
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["listener"] = section(it, "listener") { l -> l["address"] = "10.0.0.1\n".asJson() }
            it["provider"] = section(it, "provider") { p -> p["contract_version"] = "1\u0000".asJson() }
        }).selfCheck)
        assertNull(check.listener?.address)
        assertNull(check.provider?.contractVersion)
    }

    @Test fun bridgeMethodsAreFilteredAndCappedAtEight() {
        val names = (1..20).map { "method_$it" }
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["bridge"] = jsonObject("ready" to true.asJson(), "methods" to JsonValue.ArrayValue(
                listOf("Display".asJson(), 5L.asJson(), "bad name".asJson(), "ok".asJson(), "ok".asJson()) + names.map { n -> n.asJson() }))
        }).selfCheck)
        assertEquals(listOf("ok") + names.take(7), check.bridge?.methods)
        assertTrue(checkNotNull(check.bridge).ready)

        val notAnArray = checkNotNull(decode(withBlock("sunshine-running") {
            it["bridge"] = jsonObject("ready" to true.asJson(), "methods" to "display".asJson())
        }).selfCheck)
        assertEquals(HostBridgeCheck(true, emptyList()), notAnArray.bridge)
    }

    @Test fun unknownEnumValuesAreKeptAsWireValuesNotRejected() {
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["temperature"] = jsonObject("source" to "acpi".asJson())
            it["provider"] = section(it, "provider") { p -> p["state"] = "paused".asJson() }
        }).selfCheck)
        assertEquals(WireValue.Unknown("acpi"), check.temperature?.source)
        assertEquals(WireValue.Unknown("paused"), check.provider?.state)
    }

    @Test fun invalidTimestampsPortsAndProtocolsBecomeNullInsteadOfThrowing() {
        val check = checkNotNull(decode(withBlock("sunshine-running") {
            it["certificate"] = section(it, "certificate") { c ->
                c["not_before"] = "2026-13-45T25:61:61Z".asJson()
                c["not_after"] = "2036-09-10T11:59:00z".asJson()
            }
            it["listener"] = section(it, "listener") { l -> l["port"] = 70000L.asJson() }
            it["versions"] = section(it, "versions") { v -> v["protocol"] = JsonValue.NumberValue("1.5") }
        }).selfCheck)
        assertEquals(HostCertificateCheck(true, "aaaaaaaa", null, null), check.certificate)
        assertNull(check.listener?.port)
        assertNull(check.versions?.protocol)
        listOf(0L, -1L, 65536L).forEach { port ->
            val listener = decode(withBlock("sunshine-running") {
                it["listener"] = section(it, "listener") { l -> l["port"] = port.asJson() }
            }).selfCheck?.listener
            assertNull("port $port", listener?.port)
        }
    }

    @Test fun anyIntegerProtocolIsKeptSoTheNudgeCanCompareIt() {
        listOf(0L, 1L, 2L, 99L).forEach { protocol ->
            val versions = decode(withBlock("sunshine-running") {
                it["versions"] = section(it, "versions") { v -> v["protocol"] = protocol.asJson() }
            }).selfCheck?.versions
            assertEquals(protocol.toInt(), versions?.protocol)
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun body(name: String): JsonValue.ObjectValue {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/status/$name.json"))
        return fixture["response"].asObject("response")["body"].asObject("body")
    }

    private fun status(name: String): VersionedPayload.Status = decode(body(name))

    private fun decode(body: JsonValue.ObjectValue): VersionedPayload.Status {
        val reply = ProtocolCodec.decodeResponse(ApiRoute.STATUS, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        assertTrue("status read must still succeed: $reply", reply is ApiReply.Versioned)
        return (reply as ApiReply.Versioned).payload as VersionedPayload.Status
    }

    /** Copy of the fixture body whose `self_check` fields have been edited. */
    private fun withBlock(name: String, edit: (MutableMap<String, JsonValue>) -> Unit): JsonValue.ObjectValue {
        val fields = body(name).fields.toMutableMap()
        val block = LinkedHashMap((fields.getValue("self_check") as JsonValue.ObjectValue).fields)
        edit(block)
        fields["self_check"] = JsonValue.ObjectValue(block)
        return JsonValue.ObjectValue(fields)
    }

    private fun section(
        block: Map<String, JsonValue>,
        name: String,
        edit: (MutableMap<String, JsonValue>) -> Unit,
    ): JsonValue.ObjectValue {
        val fields = LinkedHashMap((block.getValue(name) as JsonValue.ObjectValue).fields)
        edit(fields)
        return JsonValue.ObjectValue(fields)
    }

    private operator fun JsonValue.ObjectValue.minus(key: String) = JsonValue.ObjectValue(fields - key)
    private operator fun JsonValue.ObjectValue.plus(entry: Pair<String, JsonValue>) = JsonValue.ObjectValue(fields + entry)
}
