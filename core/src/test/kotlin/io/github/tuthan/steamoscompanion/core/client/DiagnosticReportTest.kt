package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalDecoder
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.protocol.JournalValue
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.LinkConnector
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.LinkFactsDecoder
import io.github.tuthan.steamoscompanion.core.protocol.asArray
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The report's rules, one at a time, on values built here; the vendored vector is `DiagnosticReportVectorTest`. */
class DiagnosticReportTest {
    private fun json(text: String) = BoundedJson.parseObject(text.toByteArray(Charsets.UTF_8))
    private fun compact(value: JsonValue) = String(BoundedJson.encode(value), Charsets.UTF_8)
    private fun section(report: JsonValue.ObjectValue, name: String): JsonValue.ObjectValue = report[name].asObject(name)
    private fun JsonValue?.text() = (this as JsonValue.StringValue).value
    private fun JsonValue?.list() = this.asArray("list")

    private val empty = DiagnosticReport.compose(null, null, null, null)

    // --- free text ---------------------------------------------------------------------------------------------------------

    @Test fun eachScrubRule() {
        listOf(
            "connect to 192.168.50.42:18443 failed" to "connect to [ip] failed",
            "peer fe80::1a2b:3c4d:5e6f:7a8b refused" to "peer [ip] refused",
            "peer 2001:db8:85a3:0:0:8a2e:370:7334 x" to "peer [ip] x",
            "at 2026-09-13T12:00:24Z done" to "at 2026-09-13T12:00:24Z done",
            "wake mac aa:bb:cc:dd:ee:ff unreachable" to "wake mac [mac] unreachable",
            "wake mac AA-BB-CC-DD-EE-FF unreachable" to "wake mac [mac] unreachable",
            "GET https://192.168.50.42:18443/v1/status?x=1 failed" to "GET [url] failed",
            "bad cert sha256:" + "0123456789abcdef".repeat(4) to "bad cert [pin]",
            "token fixture-token-0001-" + "x".repeat(30) + " rejected" to "token [token] rejected",
            "cannot open /run/user/1000/steamos-companion/state.json" to "cannot open [path]",
            "first line\nsecond\tline\u0000" to "first line second line",
            "café ‮evil" to "caf evil",
            "  spaced    out  " to "spaced out",
            "gamescope_output_update_failed on std::string" to "gamescope_output_update_failed on std::string",
            "version 0.5.18 of the plugin" to "version 0.5.18 of the plugin",
        ).forEach { (text, expected) -> assertEquals(text.take(40), expected, DiagnosticReport.scrubText(text, 160)) }
    }

    @Test fun theRulesAreTheReferencesOwnEdges() {
        fun scrub(text: String) = DiagnosticReport.scrubText(text, 160)
        // \b is a boundary between a word character and anything else, and a hyphen is not a word character.
        assertEquals("[token]", scrub("a-" + "b".repeat(24) + "1"))
        assertEquals("[token]", scrub("a" + "b".repeat(23) + "1"))
        assertEquals("twenty-three characters are not yet a token", "a" + "b".repeat(21) + "1", scrub("a" + "b".repeat(21) + "1"))
        assertEquals("-[token]", scrub("-" + "b".repeat(24) + "1"))
        assertEquals("[token]-", scrub("x-" + "b".repeat(23) + "1-"))
        assertEquals("[token]", scrub("ab" + "-".repeat(30) + "1"))
        assertEquals("a long run with no digit is a word, not a token", "a".repeat(24), scrub("a".repeat(24)))
        assertEquals("[ip]", scrub("10.0.0.1:8443"))
        assertEquals("an address glued to a word is not an address", "a10.0.0.1", scrub("a10.0.0.1"))
        assertEquals("[path]", scrub("/var/lib/steam"))
        assertEquals("one segment is not a path", "/only", scrub("/only"))
        assertEquals("[url] and [url]", scrub("http://a.b/c and https://d.e"))
        assertEquals("[pin] [pin]", scrub("sha256:" + "ab".repeat(4) + " sha256:" + "CD".repeat(8)))
        assertEquals("fewer than eight hex digits is not a pin", "sha256:abcdef", scrub("sha256:abcdef"))
    }

    @Test fun theResultIsBoundedAndEmptyTextIsAbsent() {
        assertEquals(("word ".repeat(80)).trim().take(159), DiagnosticReport.scrubText("word ".repeat(80), 159))
        assertEquals(160, DiagnosticReport.scrubText("a".repeat(500), 160)!!.length)
        listOf("", "   ", "\u0000\u0001", "‮", null).forEach { assertNull(it, DiagnosticReport.scrubText(it, 160)) }
        // Only the first 1024 code points are looked at.
        assertEquals("a".repeat(1024).take(160), DiagnosticReport.scrubText("a".repeat(5000), 2000)!!.take(160))
        assertEquals(1024, DiagnosticReport.scrubText("a".repeat(5000), 2000)!!.length)
    }

    @Test fun aTokenIsScrubbedBeforeItIsCut() {
        val token = "fixture-token-0001-" + "x".repeat(30)
        assertEquals("[token]", DiagnosticReport.scrubText(token, 160))
        assertFalse((DiagnosticReport.scrubText("y".repeat(150) + " " + token, 160) ?: "").contains("fixture-token"))
        // A cut at 10 would leave "fixture-to" if it ran first.
        assertEquals("[token]", DiagnosticReport.scrubText(token, 10))
    }

    // --- null versus empty ------------------------------------------------------------------------------------------------------

    @Test fun absentInputsAreNullNotEmpty() {
        assertEquals("""{"format":"steamos-companion-report-v1","host":null,"outputs":null,"link":null,"journal":null}""", compact(empty))
        listOf(JsonValue.StringValue("text"), JsonValue.NumberValue("7"), JsonValue.ArrayValue(emptyList()), JsonValue.BooleanValue(true), json("""{"unrelated":1}"""))
            .forEach { assertEquals(JsonValue.NullValue, DiagnosticReport.compose(it, null, null, null)["host"]) }
        listOf(JsonValue.StringValue("text"), JsonValue.NumberValue("7"), JsonValue.ArrayValue(emptyList()), JsonValue.BooleanValue(true), JsonValue.NullValue)
            .forEach { assertEquals(JsonValue.NullValue, DiagnosticReport.compose(null, it, null, null)["outputs"]) }
    }

    @Test fun anEmptyAnswerIsPresentAndEmpty() {
        val journal = DiagnosticReport.compose(null, null, null, HostJournal(emptyList()))["journal"]!!
        assertEquals("""{"count":0,"truncated":false,"records":[]}""", compact(journal))
        val link = DiagnosticReport.compose(null, null, LinkFacts(false, null, null, emptyList()), null)["link"]!!
        assertEquals("""{"available":false,"reason":null,"connectors":[]}""", compact(link))
        val outputs = DiagnosticReport.compose(null, json("""{"available":false,"outputs":[]}"""), null, null)["outputs"]!!
        assertEquals("""{"available":false,"count":0,"outputs":[],"saved_profiles":0,"preview_active":false}""", compact(outputs))
    }

    // --- host -------------------------------------------------------------------------------------------------------------------

    private val goodCheck = """
        {"certificate":{"ready":true,"fingerprint_short":"89abcdef","not_before":"2026-09-13T11:59:00Z","not_after":"2036-09-10T11:59:00Z"},
         "listener":{"bound":true,"address":"192.168.50.42","port":18443},
         "bridge":{"ready":true,"methods":["display","suspend"]},"temperature":{"source":"hwmon"},
         "provider":{"monitor_enabled":true,"state":"running","stale":false,"contract_version":"1"},
         "versions":{"protocol":1,"plugin":"0.5.18"}}
    """.trimIndent()

    private fun host(check: String = goodCheck) = section(DiagnosticReport.compose(json("""{"protocol_version":1,"self_check":$check}"""), null, null, null), "host")

    @Test fun theHostSectionNamesOnlyFacts() {
        val text = compact(host())
        assertEquals(
            """{"plugin_version":"0.5.18","protocol_version":1,"certificate":{"ready":true,"valid_from":"2026-09-13","valid_until":"2036-09-10"},""" +
                """"listener":{"bound":true,"port":18443},"bridge":{"ready":true,"methods":["display","suspend"]},"temperature_source":"hwmon",""" +
                """"sunshine":{"monitor_enabled":true,"state":"running","stale":false,"contract_version":"1"}}""",
            text,
        )
        listOf("89abcdef", "192.168", "fingerprint", "address").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test fun tokensUseTheWholeStringSoATrailingNewlineCannotPass() {
        val hostile = host(goodCheck.replace("\"plugin\":\"0.5.18\"", "\"plugin\":\"0.5.18\\n\"").replace("\"display\",\"suspend\"", "\"display\\n\",\"suspend\""))
        assertEquals(JsonValue.NullValue, hostile["plugin_version"])
        assertEquals(listOf("suspend"), hostile["bridge"].asObject("b")["methods"].list().map { it.text() })
        val connector = DiagnosticReport.compose(null, null, LinkFacts(true, null, null, listOf(
            LinkConnector("DP-1\n", "drm:card0", "x", null, null, false, true, emptyList(), null),
            LinkConnector("DP-2", "drm:card0\n", "x", "GSM\n", null, false, true, listOf("1920x1080\n", "1280x800"), "1\n"),
        )), null)["link"].asObject("link")["connectors"].list().single().asObject("c")
        assertEquals("DP-2", connector["connector"].text())
        assertEquals(JsonValue.NullValue, connector["card"])
        assertEquals(JsonValue.NullValue, connector["vendor"])
        assertEquals(JsonValue.NullValue, connector["output_id"])
        assertEquals(listOf("1280x800"), connector["resolutions"].list().map { it.text() })
        val good = record()
        // The timestamp is whole-string too: "...Z\n" is not a timestamp.
        assertEquals(JsonValue.NullValue, journal(good.copy(updatedAt = "2026-09-13T12:00:00Z\n"))["records"].list().single().asObject("r")["updated_at"])
        assertEquals("2026-09-13T12:00:00Z", journal(good.copy(updatedAt = "2026-09-13T12:00:00Z"))["records"].list().single().asObject("r")["updated_at"].text())
        listOf(good.copy(id = "op-1\n"), good.copy(kind = "power.suspend\n"), good.copy(state = "failed\n")).forEach {
            assertEquals(0L, count(DiagnosticReport.compose(null, null, null, HostJournal(listOf(it)))))
        }
    }

    @Test fun onlyTheDayIsReadFromACertificateDateAndByItsPrefix() {
        val c = host(goodCheck.replace("2026-09-13T11:59:00Z", "2026-09-13Tanything at all")).get("certificate").asObject("c")
        assertEquals("2026-09-13", c["valid_from"].text())
        val bad = host(goodCheck.replace("2026-09-13T11:59:00Z", "٢٠٢٦-09-13T11:59:00Z").replace("2036-09-10T11:59:00Z", "2036-9-10T11:59:00Z"))
        assertEquals(JsonValue.NullValue, bad["certificate"].asObject("c")["valid_from"])
        assertEquals(JsonValue.NullValue, bad["certificate"].asObject("c")["valid_until"])
    }

    @Test fun anUnknownValueIsNullAndAContainerWhereATokenBelongsNullsTheSection() {
        val odd = host(goodCheck.replace("\"hwmon\"", "\"gpu\"").replace("\"running\"", "\"exploding\"").replace("\"plugin\":\"0.5.18\"", "\"plugin\":\"unknown\""))
        assertEquals(JsonValue.NullValue, odd["temperature_source"])
        assertEquals(JsonValue.NullValue, odd["sunshine"].asObject("s")["state"])
        assertEquals("the literal 'unknown' is a version string the host reported", "unknown", odd["plugin_version"].text())
        listOf(goodCheck.replace("\"hwmon\"", "[\"hwmon\"]"), goodCheck.replace("\"running\"", "{}")).forEach {
            assertEquals(JsonValue.NullValue, DiagnosticReport.compose(json("""{"self_check":$it}"""), null, null, null)["host"])
        }
    }

    @Test fun methodsAreCappedAndPortsAndVersionsAreRangeChecked() {
        val names = (0 until 12).joinToString(",") { "\"m$it\"" }
        val capped = host(goodCheck.replace("\"display\",\"suspend\"", names))
        assertEquals(8, capped["bridge"].asObject("b")["methods"].list().size)
        val ranged = host(goodCheck.replace("\"port\":18443", "\"port\":70000").replace("\"protocol\":1", "\"protocol\":100"))
        assertEquals(JsonValue.NullValue, ranged["listener"].asObject("l")["port"])
        assertEquals(JsonValue.NullValue, ranged["protocol_version"])
        listOf("0", "-1", "65536").forEach {
            assertEquals(it, JsonValue.NullValue, host(goodCheck.replace("\"port\":18443", "\"port\":$it"))["listener"].asObject("l")["port"])
        }
        assertEquals("65535", (host(goodCheck.replace("\"port\":18443", "\"port\":65535"))["listener"].asObject("l")["port"] as JsonValue.NumberValue).lexical)
        val decimals = host(goodCheck.replace("\"port\":18443", "\"port\":18443.0").replace("\"protocol\":1", "\"protocol\":1e0"))
        assertEquals(JsonValue.NullValue, decimals["listener"].asObject("l")["port"])
        assertEquals(JsonValue.NullValue, decimals["protocol_version"])
    }

    // --- outputs ------------------------------------------------------------------------------------------------------------------

    private fun outputs(modes: String, current: String = "a") = section(DiagnosticReport.compose(null, json(
        """{"available":true,"outputs":[{"id":"1","name":"N","description":"D","is_internal":false,"current_mode_id":"$current","modes":[$modes]}]}"""), null, null), "outputs")

    @Test fun refreshRatesAreFormattedAlikeInEveryLanguage() {
        fun mode(id: String, hz: String) = """{"id":"$id","width":1920,"height":1080,"refresh_hz":$hz}"""
        val output = outputs(listOf(
            mode("a", "59.94"), mode("b", "60.0"), mode("c", "60"), mode("d", "59.9"), mode("e", "null"), mode("f", "0"), mode("g", "1e999"),
            mode("h", "true"), mode("i", "1000"), mode("j", "1000.01"), mode("k", "0.004"), mode("l", "119.995"), mode("m", "143.999"), mode("n", "100"),
            mode("o", "1.005"), mode("p", "2.675"), mode("q", "59.125"), mode("r", "23.976"), mode("s", "0.005"), mode("t", "0.375"), mode("u", "1.375"),
        ).joinToString(",")).get("outputs").list().single().asObject("o")
        assertEquals(
            listOf(
                "1920x1080@59.94", "1920x1080@60", "1920x1080@60", "1920x1080@59.9", "1920x1080", "1920x1080", "1920x1080", "1920x1080",
                "1920x1080@1000", "1920x1080", "1920x1080@0", "1920x1080@120", "1920x1080@144", "1920x1080@100",
                // Correctly rounded to two decimals on the exact binary value, ties to even, as the reference does.
                "1920x1080@1", "1920x1080@2.67", "1920x1080@59.12", "1920x1080@23.98", "1920x1080@0.01", "1920x1080@0.38", "1920x1080@1.38",
            ),
            output["modes"].list().map { it.text() },
        )
        assertEquals("1920x1080@59.94", output["current_mode"].text())
    }

    @Test fun aModeNeedsIntegerSizesInRangeAndTheCurrentOneIsTheFirstMatchingId() {
        val output = outputs(
            """{"id":"a","width":0,"height":1080},{"id":"b","width":"1920","height":1080},{"id":"c","width":1920.0,"height":1080},null,"x",
               {"id":"a","width":1280,"height":720},{"id":"d","width":16384,"height":16385},{"id":"e","width":3840,"height":2160}""",
        ).get("outputs").list().single().asObject("o")
        // The first mode with id "a" is the invalid one: the current mode is then unknown, never the next "a".
        assertEquals(JsonValue.NullValue, output["current_mode"])
        assertEquals(listOf("1280x720", "3840x2160"), output["modes"].list().map { it.text() })
        assertEquals(2L, (output["mode_count"] as JsonValue.NumberValue).lexical.toLong())
    }

    @Test fun anOutputNeedsAnIdOfAtMostSixteenCharactersAndOnlyNameAndDescriptionAreCopied() {
        val body = json(
            """{"available":true,"outputs":[{"id":"../x","name":"dropped"},{"id":"${"i".repeat(17)}"},{"id":"ok","name":"TV 192.168.1.5","description":"https://evil.example/x",
               "is_internal":true,"edid":"00ffffffffffff00","serial":"SN1","modes":[]},"nope"],"profiles":[{"id":"profile-secret"},{"id":"profile-b"}],"preview":{"preview_id":"op-secret"}}""")
        val text = compact(section(DiagnosticReport.compose(null, body, null, null), "outputs"))
        assertEquals(
            """{"available":true,"count":1,"outputs":[{"id":"ok","name":"TV [ip]","description":"[url]","internal":true,"current_mode":null,"mode_count":0,"modes":[]}],""" +
                """"saved_profiles":2,"preview_active":true}""",
            text,
        )
    }

    @Test fun outputBounds() {
        val modes = (0 until 40).joinToString(",") { """{"id":"m$it","width":${640 + it},"height":480,"refresh_hz":60}""" }
        val outputs = (0 until 12).joinToString(",") { """{"id":"o$it","name":"P","modes":[${if (it == 0) modes else ""}]}""" }
        val profiles = (0 until 300).joinToString(",") { """{"id":"p$it"}""" }
        val report = section(DiagnosticReport.compose(null, json("""{"available":true,"outputs":[$outputs],"profiles":[$profiles]}"""), null, null), "outputs")
        assertEquals(12L, (report["count"] as JsonValue.NumberValue).lexical.toLong())
        assertEquals(DiagnosticReport.MAX_OUTPUTS, report["outputs"].list().size)
        val first = report["outputs"].list().first().asObject("o")
        assertEquals(DiagnosticReport.MAX_MODES, first["modes"].list().size)
        assertEquals("40", (first["mode_count"] as JsonValue.NumberValue).lexical)
        assertEquals("256", (report["saved_profiles"] as JsonValue.NumberValue).lexical)
    }

    // --- link ----------------------------------------------------------------------------------------------------------------------

    private fun connector(
        name: String = "HDMI-A-1", gpu: String = "drm:card0", vendor: String? = "GSM", product: Int? = 49780, internal: Boolean = false, connected: Boolean = true,
        resolutions: List<String> = emptyList(), output: String? = null,
    ) = LinkConnector(name, gpu, "Secret TV", vendor, product, internal, connected, resolutions, output)

    private fun link(vararg connectors: LinkConnector, reason: String? = null) =
        section(DiagnosticReport.compose(null, null, LinkFacts(true, "2026-09-13T12:00:00Z", reason, connectors.toList()), null), "link")

    @Test fun theLinkSectionKeepsOnlyNamedFacts() {
        val text = compact(link(connector(resolutions = listOf("3840x2160", "1920x1080", "3840x2160", "bad", "99x99999999"), output = "1"), reason = "reader at 192.168.50.42"))
        assertEquals(
            """{"available":true,"reason":"reader at [ip]","connectors":[{"connector":"HDMI-A-1","card":"card0","connected":true,"internal":false,""" +
                """"vendor":"GSM","model":49780,"output_id":"1","resolution_count":2,"resolutions":["3840x2160","1920x1080"]}]}""",
            text,
        )
        assertFalse(text.contains("Secret"))
    }

    @Test fun digitsOfOtherScriptsAreNotFacts() {
        val arabicIndic = "\u0661\u0669\u0662\u0660x\u0661\u0660\u0668\u0660"
        val kept = link(connector(resolutions = listOf(arabicIndic, "1920x1080")))["connectors"].list().single().asObject("c")
        assertEquals(listOf("1920x1080"), kept["resolutions"].list().map { it.text() })
        assertEquals("1", (kept["resolution_count"] as JsonValue.NumberValue).lexical)
        assertFalse(compact(kept).contains("\u0661"))
    }

    @Test fun linkBoundsAndIdentifierLimits() {
        val many = (0 until 12).map { connector(name = "DP-$it", resolutions = if (it == 0) (0 until 40).map { n -> "${1000 + n}x${700 + n}" } else emptyList()) }
        val report = link(*many.toTypedArray())
        assertEquals(DiagnosticReport.MAX_CONNECTORS, report["connectors"].list().size)
        val first = report["connectors"].list().first().asObject("c")
        assertEquals(DiagnosticReport.MAX_RESOLUTIONS, first["resolutions"].list().size)
        assertEquals("40", (first["resolution_count"] as JsonValue.NumberValue).lexical)
        val limits = link(
            connector(name = "c".repeat(25)), connector(name = "ok", output = "x".repeat(17), vendor = "TOOLONGVENDOR", product = 70000, gpu = "card1"),
        )["connectors"].list().single().asObject("c")
        assertEquals(JsonValue.NullValue, limits["output_id"])
        assertEquals(JsonValue.NullValue, limits["vendor"])
        assertEquals(JsonValue.NullValue, limits["model"])
        assertEquals(JsonValue.NullValue, limits["card"])
    }

    // --- journal ---------------------------------------------------------------------------------------------------------------------

    private fun record(
        id: String = "op-1", kind: String = "power.suspend", state: String = "failed", updatedAt: String? = "2026-09-13T12:00:00.250000Z",
        outcome: String? = null, reason: String? = null, restoreState: String? = null, restored: Boolean? = null, target: Map<String, JournalValue> = emptyMap(),
    ) = HostJournalRecord(id, kind, state, "2026-09-13T12:00:00Z", updatedAt, outcome, reason, restoreState, restored, "resolved", target)

    private fun journal(vararg records: HostJournalRecord) = section(DiagnosticReport.compose(null, null, null, HostJournal(records.toList())), "journal")
    private fun count(report: JsonValue.ObjectValue) = (section(report, "journal")["count"] as JsonValue.NumberValue).lexical.toLong()

    @Test fun aJournalRecordNeedsAValidIdKindAndStateAndNeverCopiesTheId() {
        val good = record(id = "op-abc123def456")
        val bad = listOf(good.copy(id = "../etc"), good.copy(kind = "power suspend"), good.copy(state = "x".repeat(33)), good.copy(kind = "k".repeat(65)), good.copy(id = ""))
        val report = journal(*bad.toTypedArray(), good)
        assertEquals(1, report["records"].list().size)
        assertEquals("1", (report["count"] as JsonValue.NumberValue).lexical)
        assertFalse(compact(report).contains("abc123def456"))
        assertEquals(setOf("updated_at", "kind", "state", "outcome", "restore_state", "restored", "reason", "target"), report["records"].list().single().asObject("r").fields.keys)
    }

    @Test fun aRecordKeepsOnlyCodesAndAScrubbedReason() {
        val r = journal(record(
            updatedAt = "2026-09-13T12:00:24.250000Z", outcome = "mode_request_returned", restoreState = "succeeded", restored = true,
            reason = "connect to 192.168.50.42:18443 failed",
        ))["records"].list().single().asObject("r")
        assertEquals("2026-09-13T12:00:24Z", r["updated_at"].text())
        assertEquals("connect to [ip] failed", r["reason"].text())
        assertEquals(JsonValue.BooleanValue(true), r["restored"])
        val odd = journal(record(updatedAt = "yesterday", outcome = "Not A Code", restoreState = "x".repeat(40), restored = null, reason = "‮"))["records"].list().single().asObject("r")
        listOf("updated_at", "outcome", "restore_state", "restored", "reason", "target").forEach { assertEquals(it, JsonValue.NullValue, odd[it]) }
    }

    @Test fun aTargetKeepsOnlyTheNamedKeysInTheirOrder() {
        val target = linkedMapOf<String, JournalValue>(
            "token" to JournalValue.Text("fixture-token-0001-x"), "profile_id" to JournalValue.Text("profile-secret"), "request_id" to JournalValue.Text("req-1"),
            "preview_id" to JournalValue.Text("op-secret-preview"), "address" to JournalValue.Text("192.168.50.42"), "serial" to JournalValue.Text("SN123"),
            "target" to JournalValue.Text("t"), "connector" to JournalValue.Text("HDMI-A-1"), "automatic" to JournalValue.Flag(true), "restart" to JournalValue.Flag(false),
            "generation" to JournalValue.Whole(4), "mode_id" to JournalValue.Text("2"), "output_id" to JournalValue.Text("1"),
            "output_keys" to JournalValue.TextList(listOf("a", "b")), "connectors" to JournalValue.TextList(listOf("HDMI-A-1", "DP-1", "DP-2", "DP-3", "DP-4", "DP-5")),
            "mode" to JournalValue.Group(linkedMapOf("width" to JournalValue.Whole(3440), "height" to JournalValue.Whole(1440), "refresh_hz" to JournalValue.Real(59.94))),
        )
        val text = compact(journal(record(target = target))["records"].list().single().asObject("r")["target"]!!)
        assertEquals(
            """{"output_id":"1","mode_id":"2","generation":4,"restart":false,"automatic":true,"connector":"HDMI-A-1","target":"t",""" +
                """"connectors":["HDMI-A-1","DP-1","DP-2","DP-3"],"output_keys":["a","b"],"mode":"3440x1440@59.94"}""",
            text,
        )
        listOf("token", "profile", "req-", "op-secret", "192.168", "SN123").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test fun targetValuesOfTheWrongKindAreDroppedAndAnEmptyTargetIsNull() {
        val target = linkedMapOf<String, JournalValue>(
            "output_id" to JournalValue.Real(1.5), "mode_id" to JournalValue.TextList(listOf("x")), "generation" to JournalValue.Whole(1L shl 31),
            "action" to JournalValue.Text("‮"), "connectors" to JournalValue.TextList(listOf("‮")),
            "mode" to JournalValue.Group(linkedMapOf("width" to JournalValue.Whole(0), "height" to JournalValue.Whole(5))),
        )
        assertEquals(JsonValue.NullValue, journal(record(target = target))["records"].list().single().asObject("r")["target"])
        assertEquals("2147483647", compact(journal(record(target = mapOf("generation" to JournalValue.Whole((1L shl 31) - 1))))["records"].list().single().asObject("r")["target"]!!.asObject("t")["generation"]!!))
        assertEquals("-5", compact(journal(record(target = mapOf("generation" to JournalValue.Whole(-5))))["records"].list().single().asObject("r")["target"]!!.asObject("t")["generation"]!!))
    }

    @Test fun journalCountsCapsAndTruncates() {
        val records = (0 until 25).map { record(id = "op-$it", updatedAt = "2026-09-13T12:00:${"%02d".format(it)}Z") }
        val report = journal(*records.toTypedArray())
        assertEquals("25", (report["count"] as JsonValue.NumberValue).lexical)
        assertEquals(JsonValue.BooleanValue(true), report["truncated"])
        assertEquals(DiagnosticReport.MAX_JOURNAL, report["records"].list().size)
        val exactly = journal(*records.take(20).toTypedArray())
        assertEquals(JsonValue.BooleanValue(false), exactly["truncated"])
        assertEquals(20, exactly["records"].list().size)
    }

    @Test fun theByteLimitCutsTheJournalFromItsOldestEnd() {
        val target = linkedMapOf<String, JournalValue>(
            "target_output_key" to JournalValue.Text("drm:card0:HDMI-A-1" + "x".repeat(40)), "baseline_output_key" to JournalValue.Text("drm:card0:DP-1" + "y".repeat(40)),
            "output_key" to JournalValue.Text("z".repeat(60)), "connector" to JournalValue.Text("c".repeat(60)), "action" to JournalValue.Text("a".repeat(60)),
            "target" to JournalValue.Text("t".repeat(60)), "output_id" to JournalValue.Text("o".repeat(60)), "mode_id" to JournalValue.Text("m".repeat(60)),
            "connectors" to JournalValue.TextList(List(4) { "k".repeat(40) }), "output_keys" to JournalValue.TextList(List(4) { "q".repeat(40) }),
            "mode" to JournalValue.Group(linkedMapOf("width" to JournalValue.Whole(3840), "height" to JournalValue.Whole(2160), "refresh_hz" to JournalValue.Real(59.94))),
            "generation" to JournalValue.Whole(4),
        )
        val records = (0 until 30).map {
            record(id = "op-%04d".format(it), kind = "display.selection.preview", updatedAt = "2026-09-13T12:%02d:00Z".format(it), outcome = "target_active_reconciled_after_reload",
                restoreState = "succeeded", restored = true, reason = "long reason ".repeat(30), target = target)
        }
        val full = DiagnosticReport.compose(null, null, null, HostJournal(records))
        val journal = section(full, "journal")
        assertTrue(BoundedJson.encode(full).size <= DiagnosticReport.MAX_REPORT_BYTES)
        assertEquals(JsonValue.BooleanValue(true), journal["truncated"])
        assertEquals("30", (journal["count"] as JsonValue.NumberValue).lexical)
        val kept = journal["records"].list().map { it.asObject("r")["updated_at"].text() }
        assertTrue("some records were cut, but not all", kept.size in 1 until DiagnosticReport.MAX_JOURNAL)
        assertEquals((0 until kept.size).map { "2026-09-13T12:%02d:00Z".format(it) }, kept)
        // One more record would not have fit.
        val oneMore = DiagnosticReport.compose(null, null, null, HostJournal(records)).let { compact(it) }.length
        assertTrue(oneMore > DiagnosticReport.MAX_REPORT_BYTES - 700)
    }

    @Test fun aCutWithinTwentyRecordsStillSaysTruncated() {
        val target = linkedMapOf<String, JournalValue>(
            "target_output_key" to JournalValue.Text("drm:card0:HDMI-A-1" + "x".repeat(40)), "baseline_output_key" to JournalValue.Text("drm:card0:DP-1" + "y".repeat(40)),
            "output_key" to JournalValue.Text("z".repeat(60)), "connector" to JournalValue.Text("c".repeat(60)), "action" to JournalValue.Text("a".repeat(60)),
            "target" to JournalValue.Text("t".repeat(60)), "output_id" to JournalValue.Text("o".repeat(60)), "mode_id" to JournalValue.Text("m".repeat(60)),
            "connectors" to JournalValue.TextList(List(4) { "k".repeat(40) }), "output_keys" to JournalValue.TextList(List(4) { "q".repeat(40) }),
        )
        val records = (0 until 19).map { record(id = "op-$it", kind = "display.selection.preview", reason = "long reason ".repeat(30), target = target) }
        val report = DiagnosticReport.compose(null, null, null, HostJournal(records))
        val journal = section(report, "journal")
        assertTrue(BoundedJson.encode(report).size <= DiagnosticReport.MAX_REPORT_BYTES)
        assertEquals("19", (journal["count"] as JsonValue.NumberValue).lexical)
        assertTrue("fewer than the 19 records survived the byte limit", journal["records"].list().size < 19)
        assertEquals("fewer than 20 records, but cut for size: still truncated", JsonValue.BooleanValue(true), journal["truncated"])
        // And when nothing is cut, nothing is flagged.
        assertEquals(JsonValue.BooleanValue(false), section(DiagnosticReport.compose(null, null, null, HostJournal(records.take(2))), "journal")["truncated"])
    }

    @Test fun theCutOnlyEverTouchesTheJournal() {
        val big = (0 until 30).map { record(id = "op-$it", reason = "r".repeat(160), target = mapOf("action" to JournalValue.Text("a".repeat(48)))) }
        val outputs = json("""{"available":true,"outputs":[{"id":"1","name":"N","modes":[{"id":"a","width":1920,"height":1080}]}]}""")
        val report = DiagnosticReport.compose(json("""{"self_check":$goodCheck}"""), outputs, LinkFacts(true, null, null, listOf(connector())), HostJournal(big))
        assertNotNull(report["host"]); assertTrue(report["host"] is JsonValue.ObjectValue)
        assertEquals(1L, section(report, "outputs")["count"].let { (it as JsonValue.NumberValue).lexical.toLong() })
        assertEquals(1, section(report, "link")["connectors"].list().size)
    }

    // --- never fails -------------------------------------------------------------------------------------------------------------------

    @Test fun garbageNeverThrowsAndAlwaysFits() {
        val rng = Random(20261004)
        val words = listOf(
            "", "x".repeat(300), "op-1", "0.5.18\n", "‮", "192.168.0.1", "1920x1080", "display.preview", "HDMI-A-1", "drm:card0", "aa:bb:cc:dd:ee:ff",
            "https://evil.example/x", "/run/user/1000/a", "2026-09-13T12:00:00Z", "fixture-token-0001-" + "x".repeat(30), "café", "a b", "٢٠",
        )
        val keys = listOf(
            "id", "kind", "state", "name", "description", "modes", "width", "height", "refresh_hz", "connector", "gpu_id", "resolutions", "target", "reason", "outputs",
            "operations", "drm", "connectors", "self_check", "certificate", "listener", "bridge", "provider", "versions", "temperature", "mode", "methods", "ready",
            "not_before", "not_after", "port", "plugin", "protocol", "source", "state", "current_mode_id", "is_internal", "profiles", "preview", "available", "updated_at",
            "outcome", "restore_state", "restored", "generation", "output_id", "connectors", "output_keys", "steam_output_id", "monitor_vendor", "monitor_product_id",
        )
        val numbers = listOf("0", "-1", "7", "59.94", "60", "1e999", "-0", "99999999999999999999", "0.004", "1000", "1000.5", "2147483648", "16384", "65535", "65536")

        fun junk(depth: Int): JsonValue = when (rng.nextInt(if (depth < 4) 9 else 4)) {
            0 -> JsonValue.NullValue
            1 -> JsonValue.BooleanValue(rng.nextBoolean())
            2 -> JsonValue.NumberValue(numbers[rng.nextInt(numbers.size)])
            3 -> JsonValue.StringValue(words[rng.nextInt(words.size)])
            4 -> JsonValue.ArrayValue(List(rng.nextInt(40)) { junk(depth + 1) })
            5 -> JsonValue.ObjectValue(LinkedHashMap<String, JsonValue>().also { m -> repeat(rng.nextInt(12)) { m[keys[rng.nextInt(keys.size)]] = junk(depth + 1) } })
            else -> JsonValue.ObjectValue(linkedMapOf("id" to JsonValue.StringValue("1"), "kind" to JsonValue.StringValue("power.suspend"), "state" to JsonValue.StringValue("failed"),
                "updated_at" to JsonValue.StringValue("2026-09-13T12:00:00Z"), "reason" to junk(depth + 1), "target" to junk(depth + 1), "modes" to junk(depth + 1)))
        }

        fun word() = words[rng.nextInt(words.size)]
        fun maybe() = if (rng.nextInt(3) == 0) null else word()
        fun target() = LinkedHashMap<String, JournalValue>().also { m ->
            repeat(rng.nextInt(18)) {
                m[keys[rng.nextInt(keys.size)]] = when (rng.nextInt(5)) {
                    0 -> JournalValue.Flag(rng.nextBoolean())
                    1 -> JournalValue.Whole(rng.nextLong() shr rng.nextInt(63))
                    2 -> JournalValue.Real(rng.nextDouble() * 2000 - 500)
                    3 -> JournalValue.Text(word())
                    else -> JournalValue.TextList(List(rng.nextInt(8)) { word() })
                }
            }
            if (rng.nextInt(3) == 0) m["mode"] = JournalValue.Group(linkedMapOf("width" to JournalValue.Whole(rng.nextLong(0, 20000)), "height" to JournalValue.Whole(rng.nextLong(0, 20000)),
                "refresh_hz" to JournalValue.Real(rng.nextDouble() * 1200)))
        }

        fun linkModel() = if (rng.nextInt(5) == 0) null else LinkFacts(rng.nextBoolean(), maybe(), maybe(), List(rng.nextInt(20)) {
            LinkConnector(word(), word(), word(), maybe(), if (rng.nextBoolean()) rng.nextInt(100000) - 100 else null, rng.nextBoolean(), rng.nextBoolean(),
                List(rng.nextInt(60)) { if (rng.nextBoolean()) "${100 + rng.nextInt(20000)}x${100 + rng.nextInt(20000)}" else word() }, maybe())
        })

        fun journalModel() = if (rng.nextInt(5) == 0) null else HostJournal(List(rng.nextInt(60)) {
            HostJournalRecord(if (rng.nextInt(4) == 0) word() else "op-$it", if (rng.nextInt(4) == 0) word() else "display.preview", if (rng.nextInt(4) == 0) word() else "failed",
                maybe(), if (rng.nextBoolean()) "2026-09-13T12:00:${"%02d".format(it % 60)}.250000Z" else maybe(), maybe(), maybe(), maybe(),
                if (rng.nextBoolean()) rng.nextBoolean() else null, maybe(), target())
        })

        repeat(400) { round ->
            val reports = listOf(
                DiagnosticReport.compose(junk(0), junk(0), linkModel(), journalModel()),
                // The real decoders' own output for the same junk: whatever they keep, the composer takes.
                DiagnosticReport.compose(junk(0), junk(0), LinkFactsDecoder.decode(junk(0)), HostJournalDecoder.decode(junk(0))),
            )
            for (report in reports) {
                assertEquals("round $round", listOf("format", "host", "outputs", "link", "journal"), report.fields.keys.toList())
                val bytes = BoundedJson.encode(report)
                assertTrue("round $round: ${bytes.size} bytes", bytes.size <= DiagnosticReport.MAX_REPORT_BYTES)
                // Every character the report can carry is printable ASCII: text is scrubbed and every identifier matched whole.
                assertTrue("round $round", bytes.all { it in 0x20..0x7e })
            }
        }
    }

    @Test fun theComposerCoversTheJsonItReadsAndTheDecodedModelsItNeverSees() {
        // A reading the app holds only as a decoded model (the journal) still carries nothing the model dropped.
        val decoded = HostJournalDecoder.decode(json(
            """{"operations":[{"id":"op-secret-1","request_id":"req-secret","preview_id":"op-secret-p","kind":"display.preview","state":"failed","created_at":"x",
               "updated_at":"2026-09-13T12:00:00Z","reason":"from 192.168.50.42","resolved_by":"x","target":{"profile_id":"profile-secret","output_id":"1"}}]}""")["operations"])
        val text = compact(DiagnosticReport.compose(null, null, null, decoded))
        listOf("secret", "192.168", "profile").forEach { assertFalse(it, text.contains(it)) }
        assertTrue(text.contains("from [ip]") && text.contains("\"output_id\":\"1\""))
        assertEquals("steamos-companion-report-v1", empty["format"].asString("format"))
    }
}
