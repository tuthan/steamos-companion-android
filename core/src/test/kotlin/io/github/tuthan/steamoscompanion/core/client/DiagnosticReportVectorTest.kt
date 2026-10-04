package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.asArray
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vendored cross-client vector for the redacted diagnostic report (Decky `protocol/fixtures/diagnostic-report.json`). */
class DiagnosticReportVectorTest {
    private val vector: JsonValue.ObjectValue by lazy {
        BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/diagnostic-report.json"))
    }

    private val cases: Map<String, JsonValue.ObjectValue> by lazy {
        vector["cases"].asArray("cases").map { it.asObject("case") }.associateBy { it["name"].asString("name") }
    }

    @Test fun theVendoredVectorHasTheDocumentedFormatAndCases() {
        assertEquals("steamos-companion-report-v1", vector["format"].asString("format"))
        assertEquals(
            listOf(
                "healthy", "host-without-self-check", "degraded-host", "ambiguous-link-with-preview", "nothing-read",
                "hostile-text", "hostile-shape", "oversize-journal",
            ),
            cases.keys.toList(),
        )
        assertEquals(listOf("hostile-shape"), cases.filterValues { it["strict"] == JsonValue.BooleanValue(false) }.keys.toList())
        cases.forEach { (name, case) ->
            val report = case["report"].asObject("report")
            assertEquals("$name format", "steamos-companion-report-v1", report["format"].asString("format"))
            assertEquals("$name sections", listOf("format", "host", "outputs", "link", "journal"), report.fields.keys.toList())
            assertEquals("$name inputs", listOf("status", "outputs", "drm", "operations"), case["inputs"].asObject("inputs").fields.keys.toList())
        }
    }

    @Test fun anUnreadSectionIsNullAndAnEmptyOneIsPresent() {
        val nothing = cases.getValue("nothing-read")["report"].asObject("report")
        listOf("host", "outputs", "link", "journal").forEach { assertEquals(it, JsonValue.NullValue, nothing[it]) }
        val degraded = cases.getValue("degraded-host")["report"].asObject("report")
        val journal = degraded["journal"].asObject("journal")
        assertTrue(journal["records"].asArray("records").isEmpty())
        assertEquals(JsonValue.NullValue, cases.getValue("host-without-self-check")["report"].asObject("report")["host"])
    }
}
