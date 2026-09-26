package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFixtureCatalogTest {
    @Test
    fun everyIndexedScenarioLoadsAsSyntheticNonSecretFixtureData() {
        val loader = checkNotNull(Thread.currentThread().contextClassLoader)
        val indexBytes = loader.getResourceAsStream("phase06/fixture-index.json")
            ?.use { it.readBytes() }
            ?: error("Phase 06 fixture index was not added to the test runtime")
        val index = BoundedJson.parseObject(indexBytes)
        assertEquals(setOf("schemaVersion", "fixtures"), index.fields.keys)
        assertEquals("1", index["schemaVersion"].number())
        val entries = index["fixtures"].array()

        val destinations = mutableSetOf<String>()
        entries.forEach { entryValue ->
            val entry = entryValue.objectValue()
            assertEquals(setOf("screen", "scenario", "source", "contractFixtures", "resourcePath"), entry.fields.keys)
            val screen = entry["screen"].string()
            val scenario = entry["scenario"].string()
            val resourcePath = entry["resourcePath"].string()
            val contractFixtures = entry["contractFixtures"].array().map { it.string() }
            assertTrue(resourcePath.startsWith("phase06/"))
            assertFalse(resourcePath.contains(".."))
            destinations += screen

            val bytes = loader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                ?: error("Missing fixture resource: $resourcePath")
            val fixture = BoundedJson.parseObject(bytes)
            assertEquals(
                setOf("schemaVersion", "fixtureOnly", "screen", "scenario", "source", "contractFixtures", "sampleData"),
                fixture.fields.keys,
            )
            assertEquals("1", fixture["schemaVersion"].number())
            assertEquals(true, fixture["fixtureOnly"].boolean())
            assertEquals(screen, fixture["screen"].string())
            assertEquals(scenario, fixture["scenario"].string())
            assertEquals(entry["source"].string(), fixture["source"].string())
            assertEquals(contractFixtures, fixture["contractFixtures"].array().map { it.string() })

            val sampleData = fixture["sampleData"].objectValue()
            assertEquals(true, sampleData["synthetic"].boolean())
            val device = sampleData["device"].objectValue()
            assertTrue(device["endpoint"].string().contains(".invalid:"))
            assertEquals(null, device["name"])
            assertEquals(false, sampleData["pairing"].objectValue()["payloadSecretIncluded"].boolean())
            assertNoSecretValues(fixture)
        }

        assertEquals(177, entries.size)
        assertEquals(13, destinations.size)
    }

    private fun assertNoSecretValues(value: JsonValue?) {
        when (value) {
            is JsonValue.ObjectValue -> value.fields.forEach { (key, child) ->
                if (key == "payloadSecretIncluded") {
                    assertEquals(false, child.boolean())
                } else {
                    assertFalse("Sensitive field $key is forbidden in a UI fixture", key.lowercase() in SENSITIVE_FIELDS)
                    assertNoSecretValues(child)
                }
            }
            is JsonValue.ArrayValue -> value.values.forEach(::assertNoSecretValues)
            else -> Unit
        }
    }

    private fun JsonValue?.objectValue() = this as? JsonValue.ObjectValue
        ?: error("Expected a JSON object")

    private fun JsonValue?.array() = (this as? JsonValue.ArrayValue)?.values
        ?: error("Expected a JSON array")

    private fun JsonValue?.string() = (this as? JsonValue.StringValue)?.value
        ?: error("Expected a JSON string")

    private fun JsonValue?.number() = (this as? JsonValue.NumberValue)?.lexical
        ?: error("Expected a JSON number")

    private fun JsonValue?.boolean() = (this as? JsonValue.BooleanValue)?.value
        ?: error("Expected a JSON boolean")

    companion object {
        private val SENSITIVE_FIELDS = setOf("secret", "nonce", "token", "bearertoken", "credential", "credentialtoken")
    }
}
