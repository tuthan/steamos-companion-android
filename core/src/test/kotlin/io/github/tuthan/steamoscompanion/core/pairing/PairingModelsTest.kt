package io.github.tuthan.steamoscompanion.core.pairing

import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.asString
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import io.github.tuthan.steamoscompanion.core.transport.TransportTarget
import io.github.tuthan.steamoscompanion.core.transport.UnverifiedDiscoveryCandidate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingModelsTest {
    @Test
    fun sasMatchesTheThreePinnedHostAndOmarchyKnownAnswers() {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { it.toByte() })
        assertEquals("21800881", SasCodeDeriver.derive(nonce, "sha256:" + "11".repeat(32)))
        assertEquals("39693759", SasCodeDeriver.derive(nonce, "sha256:" + "a".repeat(64)))
        assertEquals("67630249", SasCodeDeriver.derive(nonce, "sha256:" + "b".repeat(64)))
        assertNotEquals(
            SasCodeDeriver.derive(nonce, "sha256:" + "11".repeat(32)),
            SasCodeDeriver.derive(nonce, "sha256:" + "22".repeat(32)),
        )
    }

    @Test
    fun sasRejectsNoncanonicalNonceAndFingerprint() {
        assertThrows(IllegalArgumentException::class.java) { SasCodeDeriver.derive("AAECAwQFBgcICQoLDA0ODw==", TEST_PIN) }
        assertThrows(IllegalArgumentException::class.java) { SasCodeDeriver.derive("AAECAwQFBgcICQoLDA0ODw", "SHA256:" + "ab".repeat(32)) }
    }

    @Test
    fun payloadDecoderChecksHostFormatIdentityAndLocalExpiry() {
        val valid = payloadText(expiresAtMillis = TEST_NOW + 60_000)
        val parsed = PairingPayloadParser.parse(valid, TEST_NOW)
        assertEquals("deck.local:18443", parsed.endpoint)
        assertEquals(TEST_HOST, parsed.hostId)
        assertEquals(TEST_PIN, parsed.certificateFingerprint)
        assertEquals(TEST_PAIRING_ID, parsed.pairingId)
        assertEquals(TEST_NOW + 60_000, parsed.expiresAtMillis)

        assertThrows(PairingInputException::class.java) { PairingPayloadParser.parse(valid, TEST_NOW + 60_001) }
        assertThrows(PairingInputException::class.java) { PairingPayloadParser.parse("steamos-companion:v1:%%%", TEST_NOW) }
        assertThrows(PairingInputException::class.java) { PairingPayloadParser.parse(valid + "=", TEST_NOW) }
        assertThrows(PairingInputException::class.java) { PairingPayloadParser.parse(valid.replace("v1:", "v2:"), TEST_NOW) }
    }

    @Test
    fun hostBundledQrPayloadFixtureParsesWithTheAndroidClientValidator() {
        val fixture = javaClass.classLoader.getResourceAsStream("fixtures/pairing-payload.json")!!
            .use { BoundedJson.parseObject(it.readBytes()) }
        val payload = fixture["payload"].asString("payload")
        val parsed = PairingPayloadParser.parse(payload, TEST_NOW)

        assertEquals("192.0.2.10:18443", parsed.endpoint)
        assertEquals("host-fixture0001xxxxxxxxxxxxx", parsed.hostId)
        assertEquals("pair-fixture0001xxxxxxxxxxxxx", parsed.pairingId)
        assertEquals(TEST_NOW + 120_000, parsed.expiresAtMillis)
    }

    @Test
    fun payloadDecoderRejectsDuplicateJsonKeysWrongIdsBadPinsAndNonFiniteExpiry() {
        val duplicate = """{"protocol_version":1,"protocol_version":1,"endpoint":"deck.local:18443","host_id":"$TEST_HOST","certificate_fingerprint":"$TEST_PIN","pairing_id":"$TEST_PAIRING_ID","secret":"fixture-payload-secret-001","expires_at":1790000060}"""
        assertThrows(PairingInputException::class.java) { PairingPayloadParser.parse(payloadText(duplicate.toByteArray(Charsets.UTF_8)), TEST_NOW) }

        listOf(
            validPayloadMap().apply { put("host_id", JsonValue.StringValue("../host")) },
            validPayloadMap().apply { put("pairing_id", JsonValue.StringValue("pairing with spaces")) },
            validPayloadMap().apply { put("certificate_fingerprint", JsonValue.StringValue("sha256:" + "A".repeat(64))) },
            validPayloadMap().apply { put("expires_at", JsonValue.NumberValue("1e999")) },
            validPayloadMap().apply { put("secret", JsonValue.StringValue("short")) },
        ).forEach { document ->
            assertThrows(PairingInputException::class.java) { PairingPayloadParser.parse(payloadText(BoundedJson.encode(JsonValue.ObjectValue(document))), TEST_NOW) }
        }
    }

    @Test
    fun nonceAndPayloadRequestsUseOnlyTheirExactWireFieldsAndNeverCarryTheSas() {
        val network = LanPath("wifi-1", "wlan0", emptyList(), emptyList())
        val discovered = UnverifiedDiscoveryCandidate(
            TransportTarget(HostEndpoint.parse("deck.local:18443"), network),
            TEST_HOST,
            "SteamOS device",
            TEST_PIN,
        )
        val nonceCandidate = PairingCandidateFactory().fromDiscovery(
            discovered,
            "Android Companion",
            listOf("display.control", "status.read"),
            TEST_NOW,
        )
        val nonceBody = PairingRequestBuilder.request(nonceCandidate)
        assertEquals(setOf("verification_nonce", "client_id", "client_name", "scopes"), nonceBody.fields.keys)
        val verificationCode = requireNotNull(nonceCandidate.verificationCode)
        assertTrue(verificationCode.matches(Regex("[0-9]{8}")))
        assertFalse(BoundedJson.encode(nonceBody).toString(Charsets.UTF_8).contains(verificationCode))
        ApiRequest(HttpMethod.POST, ApiRoute.PAIR_REQUEST.path, nonceBody).validate()

        val pollBody = PairingRequestBuilder.request(nonceCandidate.copy(pairingSession = TEST_SESSION))
        assertEquals(setOf("verification_nonce", "client_id", "client_name", "scopes", "pairing_session"), pollBody.fields.keys)

        val payload = PairingPayloadParser.parse(payloadText(expiresAtMillis = TEST_NOW + 60_000), TEST_NOW)
        val payloadCandidate = PairingCandidateFactory().fromPayload(payload, "wifi-1", "Android Companion", PairingCandidateFactory.DEFAULT_SCOPES, TEST_NOW)
        val payloadBody = PairingRequestBuilder.request(payloadCandidate)
        assertEquals(setOf("pairing_id", "secret", "client_id", "client_name", "scopes"), payloadBody.fields.keys)
        assertFalse("verification_nonce" in payloadBody.fields)
        assertFalse("verification_code" in payloadBody.fields)
        ApiRequest(HttpMethod.POST, ApiRoute.PAIR_REQUEST.path, payloadBody).validate()

        val payloadPoll = PairingRequestBuilder.request(payloadCandidate.copy(pairingSession = TEST_SESSION))
        assertEquals(setOf("pairing_id", "secret", "client_id", "client_name", "scopes", "pairing_session"), payloadPoll.fields.keys)
        assertFalse("verification_nonce" in payloadPoll.fields)
        assertFalse("verification_code" in payloadPoll.fields)
        assertEquals("wifi-1", payloadCandidate.networkId)
    }

    @Test
    fun scopeAndClientNameValidatorsRequireStatusAndBoundDisplayedText() {
        assertEquals(listOf("status.read", "display.control"), PairingCandidateFactory.validateScopes(listOf("display.control", "status.read")))
        assertThrows(PairingInputException::class.java) { PairingCandidateFactory.validateScopes(listOf("power.control")) }
        assertThrows(PairingInputException::class.java) { PairingCandidateFactory.validateScopes(listOf("status.read", "status.read")) }
        assertThrows(PairingInputException::class.java) { PairingCandidateFactory.validateScopes(listOf("status.read", "admin")) }
        assertThrows(PairingInputException::class.java) { PairingCandidateFactory.validateClientName("x\nsecret") }
        assertEquals(96, PairingCandidateFactory.validateClientName("x".repeat(96)).length)
        assertThrows(PairingInputException::class.java) { PairingCandidateFactory.validateClientName("x".repeat(97)) }
    }

    private fun payloadText(expiresAtMillis: Long): String = payloadText(BoundedJson.encode(JsonValue.ObjectValue(validPayloadMap(expiresAtMillis))))

    private fun payloadText(json: ByteArray): String = PairingPayloadParser.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json)

    private fun validPayloadMap(expiresAtMillis: Long = TEST_NOW + 60_000): LinkedHashMap<String, JsonValue> = linkedMapOf(
        "protocol_version" to JsonValue.NumberValue("1"),
        "endpoint" to JsonValue.StringValue("https://deck.local:18443"),
        "host_id" to JsonValue.StringValue(TEST_HOST),
        "certificate_fingerprint" to JsonValue.StringValue(TEST_PIN),
        "pairing_id" to JsonValue.StringValue(TEST_PAIRING_ID),
        "secret" to JsonValue.StringValue("fixture-payload-secret-001"),
        "expires_at" to JsonValue.NumberValue((expiresAtMillis / 1_000.0).toString()),
    )
}
