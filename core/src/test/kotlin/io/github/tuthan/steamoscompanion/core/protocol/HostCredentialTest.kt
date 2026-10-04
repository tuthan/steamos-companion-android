package io.github.tuthan.steamoscompanion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `credential.scopes` is the host's current word on what this pairing may do. These tests pin what the vendored
 * fixtures carry, that a host which does not say (an older one, or a malformed block) is never read as "no access",
 * and that no shape of the block can widen what the client believes or fail a status read.
 */
class HostCredentialTest {
    // --- pinned fixtures -------------------------------------------------------------------

    @Test fun viewOnlyFixtureReportsStatusReadAlone() {
        assertEquals(listOf("status.read"), status("view-only-credential").credentialScopes)
    }

    @Test fun scopesChangedByOwnerFixtureReportsTheNewListInHostOrder() {
        assertEquals(listOf("status.read", "display.control"), status("scopes-changed-by-owner").credentialScopes)
    }

    @Test fun everyStatusSuccessFixtureCarriesTheBlockWithGrantableScopesOnly() {
        val index = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/index.json"))
        val cases = index["cases"].asArray("cases").map { it.asObject("case") }.filter {
            it["path"].asString("path") == "/v1/status" && it["status"].asLong("status") == 200L
        }.map { it["case"].asString("case") }
        assertEquals(13, cases.size)
        cases.forEach { name ->
            val scopes = status(name.removePrefix("status/")).credentialScopes
            assertNotNull("$name must carry credential.scopes", scopes)
            assertTrue("$name: $scopes", scopes!!.all { it in HostScopes.GRANTABLE } && scopes.distinct() == scopes)
        }
    }

    @Test fun fullFixturesReportAllThreeInHostOrder() {
        assertEquals(HostScopes.GRANTABLE, status("sunshine-running").credentialScopes)
        assertEquals(listOf("status.read", "power.control", "display.control"), HostScopes.GRANTABLE)
    }

    // --- an older host does not say; that is not "no access" -------------------------------

    @Test fun aStatusWithoutTheBlockDecodesAsNullNotAsEmpty() {
        val older = body("sunshine-running") - "credential"
        val payload = decode(older)
        assertNull(payload.credentialScopes)
        assertEquals("host-fixture0001xxxxxxxxxxxxx", payload.hostId)
    }

    @Test fun legacyBodyFixturesPredateTheBlockAndDecodeAsNull() {
        listOf("sunshine-disabled", "sunshine-running", "sunshine-stopped", "sunshine-unknown", "steam-unavailable").forEach {
            val reply = ProtocolCodec.decodeResponse(ApiRoute.STATUS,
                HttpResponse(200, emptyMap(), ProtocolFixtureAssertions.readResource("fixtures/status-$it.json")))
            assertNull(it, ((reply as ApiReply.Versioned).payload as VersionedPayload.Status).credentialScopes)
        }
    }

    @Test fun anEmptyArrayIsARealAnswerAndNotNull() {
        val payload = decode(withCredential(jsonObject("scopes" to JsonValue.ArrayValue(emptyList()))))
        assertEquals(emptyList<String>(), payload.credentialScopes)
    }

    @Test fun aBlockThatIsNotAnObjectDecodesAsNull() {
        listOf<JsonValue>(
            JsonValue.NullValue, "full".asJson(), 5L.asJson(), true.asJson(), JsonValue.ArrayValue(emptyList()),
            JsonValue.ArrayValue(listOf("status.read".asJson())),
        ).forEach { assertNull(it.toString(), decode(body("sunshine-running") + ("credential" to it)).credentialScopes) }
    }

    @Test fun scopesThatAreMissingOrNotAnArrayDecodeAsNull() {
        assertNull(decode(withCredential(JsonValue.ObjectValue(emptyMap()))).credentialScopes)
        assertNull(decode(withCredential(jsonObject("other" to JsonValue.ArrayValue(listOf("status.read".asJson()))))).credentialScopes)
        listOf<JsonValue>(
            JsonValue.NullValue, "status.read".asJson(), 3L.asJson(), true.asJson(), jsonObject("0" to "status.read".asJson()),
        ).forEach { assertNull(it.toString(), decode(withCredential(jsonObject("scopes" to it))).credentialScopes) }
    }

    // --- hostile or newer input never widens what the client believes ----------------------

    @Test fun unknownScopesAreDropped() {
        val scopes = decodeScopes("status.read", "sunshine.control", "admin", "power.control", "display.control.extra")
        assertEquals(listOf("status.read", "power.control"), scopes)
        assertEquals(emptyList<String>(), decodeScopes("sunshine.control", "root", "*"))
    }

    @Test fun matchingIsExact() {
        assertEquals(emptyList<String>(), decodeScopes("STATUS.READ", "Power.Control", " display.control", "status.read ", ""))
    }

    @Test fun duplicatesAreKeptOnceInTheOrderTheyFirstAppear() {
        assertEquals(listOf("power.control", "status.read"),
            decodeScopes("power.control", "power.control", "status.read", "power.control", "status.read"))
        assertEquals(listOf("display.control", "status.read", "power.control"),
            decodeScopes("display.control", "status.read", "power.control", "display.control"))
    }

    @Test fun moreThanThreeEntriesNeverYieldMoreThanTheThreeGrantableScopes() {
        val many = decodeScopes("status.read", "power.control", "display.control", "sunshine.control", "status.read", "x")
        assertEquals(listOf("status.read", "power.control", "display.control"), many)
        assertEquals(listOf("status.read"), decodeScopes(*Array(500) { "status.read" }))
        assertEquals(emptyList<String>(), decodeScopes(*Array(1000) { "scope-$it" }))
    }

    @Test fun elementsThatAreNotStringsAreDropped() {
        val mixed = JsonValue.ArrayValue(listOf(
            "status.read".asJson(), 1L.asJson(), JsonValue.NullValue, true.asJson(),
            jsonObject("scope" to "power.control".asJson()), JsonValue.ArrayValue(listOf("display.control".asJson())),
            "display.control".asJson(),
        ))
        assertEquals(listOf("status.read", "display.control"),
            decode(withCredential(jsonObject("scopes" to mixed))).credentialScopes)
    }

    @Test fun otherKeysInTheBlockAreIgnored() {
        val block = jsonObject(
            "scopes" to JsonValue.ArrayValue(listOf("status.read".asJson())),
            "role" to "admin".asJson(), "future" to jsonObject("scopes" to JsonValue.ArrayValue(listOf("power.control".asJson()))),
        )
        assertEquals(listOf("status.read"), decode(withCredential(block)).credentialScopes)
    }

    @Test fun noShapeOfTheBlockFailsTheStatusRead() {
        val shapes = listOf<JsonValue>(
            JsonValue.NullValue, 1L.asJson(), "x".asJson(), JsonValue.ArrayValue(emptyList()),
            jsonObject("scopes" to JsonValue.NullValue), jsonObject("scopes" to jsonObject("a" to "b".asJson())),
            jsonObject("scopes" to JsonValue.ArrayValue(listOf(JsonValue.NullValue, JsonValue.ArrayValue(emptyList())))),
        )
        shapes.forEach { shape ->
            val payload = decode(body("sunshine-running") + ("credential" to shape))
            assertEquals(shape.toString(), "host-fixture0001xxxxxxxxxxxxx", payload.hostId)
        }
    }

    @Test fun theDecoderOnItsOwnAgreesWithTheStatusPath() {
        assertNull(HostCredentialDecoder.decode(null))
        assertNull(HostCredentialDecoder.decode(JsonValue.NullValue))
        assertEquals(emptyList<String>(),
            HostCredentialDecoder.decode(jsonObject("scopes" to JsonValue.ArrayValue(emptyList()))))
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun decodeScopes(vararg values: String): List<String>? =
        decode(withCredential(jsonObject("scopes" to JsonValue.ArrayValue(values.map { it.asJson() })))).credentialScopes

    private fun body(name: String): JsonValue.ObjectValue {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/status/$name.json"))
        return fixture["response"].asObject("response")["body"].asObject("body")
    }

    private fun status(name: String): VersionedPayload.Status = decode(body(name))

    private fun withCredential(block: JsonValue): JsonValue.ObjectValue = body("sunshine-running") + ("credential" to block)

    private fun decode(body: JsonValue.ObjectValue): VersionedPayload.Status {
        val reply = ProtocolCodec.decodeResponse(ApiRoute.STATUS, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        assertTrue("status read must still succeed: $reply", reply is ApiReply.Versioned)
        return (reply as ApiReply.Versioned).payload as VersionedPayload.Status
    }

    private operator fun JsonValue.ObjectValue.minus(key: String) = JsonValue.ObjectValue(fields - key)
    private operator fun JsonValue.ObjectValue.plus(entry: Pair<String, JsonValue>) = JsonValue.ObjectValue(fields + entry)
}
