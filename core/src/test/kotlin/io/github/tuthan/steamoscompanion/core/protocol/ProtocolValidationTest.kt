package io.github.tuthan.steamoscompanion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class ProtocolValidationTest {
    @Test
    fun requestBodiesFromEveryPinnedFixtureAreCheckedByTheAndroidValidator() {
        ProtocolFixtureAssertions.assertEveryFixtureRequestAgainstAndroidValidator()
    }

    @Test
    fun endpointAcceptsHostOnlyAndRejectsUrlComponents() {
        assertEquals("deck.local:18443", HostEndpoint.parse("deck.local").authority)
        assertEquals("192.168.1.20:1234", HostEndpoint.parse("https://192.168.1.20:1234").authority)
        assertEquals("[2001:db8::1]:18443", HostEndpoint.parse("[2001:db8::1]").authority)
        listOf(
            "https://deck.local/path",
            "https://deck.local/?x=1",
            "https://user@deck.local",
            "http://deck.local",
            "deck.local:70000",
            "deck.local\r\nAuthorization: x",
        ).forEach { value -> assertThrows(RequestValidationException::class.java) { HostEndpoint.parse(value) } }
    }

    @Test
    fun closedRouteSetMatchesOnlyDocumentedMethodAndPathPairs() {
        val expected = mapOf(
            "GET /v1/discovery" to ApiRoute.DISCOVERY,
            "POST /v1/pair/request" to ApiRoute.PAIR_REQUEST,
            "POST /v1/pair/cancel" to ApiRoute.PAIR_CANCEL,
            "POST /v1/pair/revoke-self" to ApiRoute.PAIR_REVOKE_SELF,
            "GET /v1/status" to ApiRoute.STATUS,
            "GET /v1/display/outputs" to ApiRoute.DISPLAY_OUTPUTS,
            "GET /v1/display/order" to ApiRoute.DISPLAY_ORDER,
            "GET /v1/operations/op-1" to ApiRoute.OPERATION,
            "POST /v1/power" to ApiRoute.POWER,
            "POST /v1/display/order" to ApiRoute.DISPLAY_ORDER_SET,
            "POST /v1/display/order/automatic" to ApiRoute.DISPLAY_ORDER_AUTOMATIC,
            "POST /v1/display/preview" to ApiRoute.DISPLAY_PREVIEW,
            "POST /v1/display/confirm" to ApiRoute.DISPLAY_CONFIRM,
            "POST /v1/display/restore" to ApiRoute.DISPLAY_RESTORE,
            "POST /v1/display/save-current" to ApiRoute.DISPLAY_SAVE_CURRENT,
            "POST /v1/sunshine/restart" to ApiRoute.SUNSHINE_RESTART,
        )
        expected.forEach { (key, route) ->
            val (method, path) = key.split(' ', limit = 2)
            assertEquals(route, ApiRoute.match(HttpMethod.valueOf(method), path).route)
        }
        listOf(
            HttpMethod.GET to "/v1/power",
            HttpMethod.POST to "/v1/status",
            HttpMethod.POST to "/v1/unknown",
            HttpMethod.GET to "/v1/operations/bad/id",
            HttpMethod.GET to "/v1/status?x=1",
            HttpMethod.GET to "https://deck.local/v1/status",
        ).forEach { (method, path) -> assertThrows(RequestValidationException::class.java) { ApiRoute.match(method, path) } }
    }

    @Test
    fun pairedRoutesRequireCredentialAndPairingNeverCarriesOne() {
        assertThrows(RequestValidationException::class.java) {
            ApiRequest(HttpMethod.GET, "/v1/status").validate()
        }
        assertThrows(RequestValidationException::class.java) {
            ApiRequest(HttpMethod.POST, "/v1/pair/request", emptyObject(), bearerToken = "fixture-token").validate()
        }
        val payload = jsonObject(
            "pairing_id" to "pair-1".asJson(),
            "secret" to "s".repeat(256).asJson(),
            "client_id" to "client-1".asJson(),
            "client_name" to "Android Companion".asJson(),
            "scopes" to JsonValue.ArrayValue(listOf("status.read".asJson())),
        )
        ApiRequest(HttpMethod.POST, "/v1/pair/request", payload).validate()
        val oversizedSecret = JsonValue.ObjectValue(payload.fields + ("secret" to "s".repeat(257).asJson()))
        assertThrows(RequestValidationException::class.java) {
            ApiRequest(HttpMethod.POST, "/v1/pair/request", oversizedSecret).validate()
        }
        val invalidSession = JsonValue.ObjectValue(payload.fields + ("pairing_session" to "short".asJson()))
        assertThrows(RequestValidationException::class.java) {
            ApiRequest(HttpMethod.POST, "/v1/pair/request", invalidSession).validate()
        }
        val malformedReply = jsonObject(
            "protocol_version" to 1L.asJson(),
            "state" to "pending".asJson(),
            "pairing_id" to "pair-1".asJson(),
            "expires_at" to 1_790_000_060L.asJson(),
            "pairing_session" to "short".asJson(),
        )
        assertThrows(JsonFormatException::class.java) {
            ProtocolCodec.decodeResponse(
                ApiRoute.PAIR_REQUEST,
                HttpResponse(200, emptyMap(), BoundedJson.encode(malformedReply)),
            )
        }
    }

    @Test
    fun mutationBodyRequiresExactFieldsAndClosedActions() {
        val valid = jsonObject(
            "request_id" to "power-1".asJson(),
            "action" to "suspend".asJson(),
        )
        ApiRequest(HttpMethod.POST, "/v1/power", valid, bearerToken = "fixture-token").validate()
        val extra = JsonValue.ObjectValue(valid.fields + ("request_id_extra" to "x".asJson()))
        assertThrows(RequestValidationException::class.java) {
            ApiRequest(HttpMethod.POST, "/v1/power", extra, bearerToken = "fixture-token").validate()
        }
        val invalidAction = JsonValue.ObjectValue(valid.fields + ("action" to "hibernate".asJson()))
        assertThrows(RequestValidationException::class.java) {
            ApiRequest(HttpMethod.POST, "/v1/power", invalidAction, bearerToken = "fixture-token").validate()
        }
    }

    @Test
    fun strictJsonRejectsDuplicateKeysTrailingDataInvalidUtf8AndNonFiniteNumbers() {
        listOf(
            "{\"x\":1,\"x\":2}",
            "{\"x\":NaN}",
            "{\"x\":Infinity}",
            "{\"x\":1} trailing",
        ).forEach { input -> assertThrows(JsonFormatException::class.java) { BoundedJson.parseObject(input.toByteArray()) } }
        assertThrows(JsonFormatException::class.java) {
            BoundedJson.parseObject(byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 'x'.code.toByte(), '"'.code.toByte(), ':'.code.toByte(), '"'.code.toByte(), 0xc3.toByte(), 0x28.toByte(), '"'.code.toByte(), '}'.code.toByte()))
        }
    }

    @Test
    fun strictJsonEnforcesDepthAndStringBudgetsAndRoundTrips() {
        assertThrows(JsonFormatException::class.java) {
            BoundedJson.parseObject("{\"x\":{\"y\":1}}".toByteArray(), JsonLimits(maxDepth = 1))
        }
        assertThrows(JsonFormatException::class.java) {
            BoundedJson.parseObject("{\"x\":\"long\"}".toByteArray(), JsonLimits(maxStringCodePoints = 3))
        }
        val source = jsonObject("name" to "Deck\n🧭".asJson(), "n" to 12L.asJson())
        assertEquals(source, BoundedJson.parseObject(BoundedJson.encode(source)))
    }

    @Test
    fun unsupportedVersionIsReturnedWithoutInterpretingPayload() {
        val body = jsonObject(
            "protocol_version" to 2L.asJson(),
            "host_id" to "future-layout".asJson(),
        )
        val reply = ProtocolCodec.decodeResponse(
            ApiRoute.STATUS,
            HttpResponse(200, emptyMap(), BoundedJson.encode(body)),
        )
        assertEquals(ApiReply.UnsupportedVersion(2), reply)
    }

    @Test
    fun operationReplyMustMatchTheRequestedOperationId() {
        val fixture = BoundedJson.parseObject(
            javaClass.classLoader.getResourceAsStream("fixtures/contract/operations/accepted.json")!!.use { it.readBytes() },
        )
        val responseBody = fixture["response"].asObject("response")["body"].asObject("body")
        val path = fixture["request"].asObject("request")["path"].asString("path")
        val expectedId = ApiRoute.match(HttpMethod.GET, path).operationId!!
        val valid = HttpResponse(200, emptyMap(), BoundedJson.encode(responseBody))
        assertTrue(ProtocolCodec.decodeResponse(ApiRoute.OPERATION, valid, expectedId) is ApiReply.Versioned)

        val oldOperation = responseBody["operation"].asObject("operation")
        val wrongOperation = JsonValue.ObjectValue(oldOperation.fields + ("id" to "another-operation".asJson()))
        val mismatchedBody = JsonValue.ObjectValue(responseBody.fields + ("operation" to wrongOperation))
        assertThrows(JsonFormatException::class.java) {
            ProtocolCodec.decodeResponse(
                ApiRoute.OPERATION,
                HttpResponse(200, emptyMap(), BoundedJson.encode(mismatchedBody)),
                expectedId,
            )
        }
    }

    @Test
    fun unknownBridgeAndOperationStatesArePreservedAsUnknown() {
        val statusFixture = ProtocolFixtureAssertions.readResource("fixtures/contract/status/steam-bridge-not-ready.json")
        val status = BoundedJson.parseObject(statusFixture)
        val response = status["response"].asObject("response")
        val original = response["body"].asObject("body")
        val changed = JsonValue.ObjectValue(original.fields + ("steam_bridge" to "starting".asJson()))
        val decoded = ProtocolCodec.decodeResponse(ApiRoute.STATUS, HttpResponse(200, emptyMap(), BoundedJson.encode(changed)))
        assertTrue(decoded is ApiReply.Versioned)
        val payload = (decoded as ApiReply.Versioned).payload as VersionedPayload.Status
        assertEquals(WireValue.Unknown("starting"), payload.steamBridge)
        assertFalse(payload.capabilities.isEmpty())

        val op = jsonObject(
            "id" to "op-1".asJson(), "request_id" to "req-1".asJson(), "state" to "future".asJson(),
            "kind" to "new.action".asJson(), "created_at" to "now".asJson(), "updated_at" to "now".asJson(),
        )
        assertEquals(WireValue.Unknown("future"), ProtocolCodec.parseOperation(op).state)
    }

    private fun emptyObject() = JsonValue.ObjectValue(emptyMap())
}
