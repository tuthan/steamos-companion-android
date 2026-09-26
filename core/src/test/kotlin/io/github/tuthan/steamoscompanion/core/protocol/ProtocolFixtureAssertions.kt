package io.github.tuthan.steamoscompanion.core.protocol

import io.github.tuthan.steamoscompanion.core.transport.Http1ResponseParser
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

object ProtocolFixtureAssertions {
    private val source = ProtocolResources()
    private val index by lazy { source.json("fixtures/contract/index.json") }

    fun assertContractFixture(caseName: String) {
        val case = index["cases"].asArray("cases").map { it.asObject("case") }
            .firstOrNull { it["case"].asString("case") == caseName }
        assertNotNull("Fixture index has no case $caseName", case)
        case!!
        val relativeFile = case["file"].asString("file")
        val fixture = source.json("fixtures/contract/$relativeFile")
        assertEquals(caseName, fixture["case"].asString("case"))

        val request = fixture["request"].asObject("request")
        val methodText = request["method"].asString("request.method")
        val path = request["path"].asString("request.path")
        val matched = try {
            ApiRoute.match(HttpMethod.valueOf(methodText), path)
        } catch (_: Exception) {
            null
        }
        val route = matched?.route ?: ApiRoute.DISCOVERY

        val response = fixture["response"].asObject("response")
        val status = response["status"].asLong("response.status").toInt()
        assertEquals(case["status"].asLong("index.status").toInt(), status)
        val rawHeaders = response["headers"] as? JsonValue.ObjectValue
        val headers = rawHeaders?.fields?.mapValues { (_, value) -> value.asString("response.header") }
            ?.mapKeys { it.key.lowercase() }.orEmpty()
        val body = BoundedJson.encode(response["body"] ?: throw AssertionError("Fixture has no response body"))
        val decoded = ProtocolCodec.decodeResponse(route, HttpResponse(status, headers, body), matched?.operationId)
        val expectedError = when (val error = case["error"]) {
            null, JsonValue.NullValue -> null
            else -> error.asString("index.error")
        }
        if (expectedError == null) {
            assertTrue("$caseName should decode as v1", decoded is ApiReply.Versioned)
            assertEquals(1, (decoded as ApiReply.Versioned).protocolVersion)
        } else {
            assertTrue("$caseName should decode as an error envelope", decoded is ApiReply.Rejected)
            val rejection = decoded as ApiReply.Rejected
            assertEquals(status, rejection.status)
            assertEquals(expectedError, rejection.error)
            assertTrue("$caseName has an empty error message", rejection.message.isNotEmpty())
        }
    }

    fun assertLegacyBody(path: String, route: ApiRoute) {
        val bytes = source.bytes(path)
        val decoded = ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), bytes))
        assertTrue("$path should decode as v1", decoded is ApiReply.Versioned)
    }

    fun assertEveryFixtureRequestAgainstAndroidValidator() {
        index["cases"].asArray("cases").map { it.asObject("case") }.forEach { case ->
            val name = case["case"].asString("case")
            val relativeFile = case["file"].asString("file")
            val fixture = source.json("fixtures/contract/$relativeFile")
            val request = fixture["request"].asObject("request")
            val methodText = request["method"].asString("request.method")
            val path = request["path"].asString("request.path")
            val body = (request["body"] as? JsonValue.ObjectValue)
            val headers = request["headers"] as? JsonValue.ObjectValue
            val authorization = headers?.fields?.entries?.firstOrNull { it.key.equals("authorization", ignoreCase = true) }
                ?.value?.asString("authorization")
            val bearer = authorization?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            val route = runCatching { ApiRoute.match(HttpMethod.valueOf(methodText), path).route }.getOrNull()
            val valid = runCatching {
                ApiRequest(HttpMethod.valueOf(methodText), path, body, bearer).validate()
            }.isSuccess
            val status = fixture["response"].asObject("response")["status"].asLong("status").toInt()
            val authShapeMatches = route != null && route.requiresBearer == !bearer.isNullOrBlank()
            val expectedValid = status !in setOf(400, 405) && "pairing_code" !in body?.fields.orEmpty() && authShapeMatches
            assertEquals("$name request body validation", expectedValid, valid)
        }
    }

    fun readResource(path: String): ByteArray = source.bytes(path)

    private class ProtocolResources {
        fun bytes(path: String): ByteArray =
            javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }
                ?: throw AssertionError("Protocol resource not found: $path")

        fun json(path: String): JsonValue.ObjectValue = BoundedJson.parseObject(bytes(path))
    }
}

/** Small lower-level contract checks shared by generated fixture methods. */
fun parseResponseBytes(raw: String, maxBodyBytes: Int = 16 * 1024) =
    Http1ResponseParser.read(ByteArrayInputStream(raw.toByteArray(StandardCharsets.US_ASCII)), maxBodyBytes).response
