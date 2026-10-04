package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HostJournal
import io.github.tuthan.steamoscompanion.core.protocol.HostJournalRecord
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JournalValue
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of the Host journal. The embedded table must equal Decky's `operation-vocabulary.json`,
 * the neutral wording shared by every client, and no completed entry may read as a visible picture.
 */
class HostJournalPresentationTest {
    // --- the shared vocabulary -------------------------------------------------------------------------

    private val vocabulary: JsonValue.ObjectValue by lazy {
        BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("operation-vocabulary.json"))
    }

    private fun table(name: String): Map<String, String> =
        vocabulary[name].asObject(name).fields.mapValues { (key, value) -> value.asString("$name.$key") }

    @Test fun theEmbeddedTableEqualsTheVendoredVocabularyFileExactly() {
        assertEquals(1L, (vocabulary["version"] as JsonValue.NumberValue).lexical.toLong())
        assertEquals(table("states"), OperationVocabulary.states)
        assertEquals(table("kinds"), OperationVocabulary.kinds)
        assertEquals(table("outcomes"), OperationVocabulary.outcomes)
        assertEquals(table("restore_states"), OperationVocabulary.restoreStates)
        // The file has no table the app does not know about, so a new kind of wording cannot slip past this test.
        assertEquals(setOf("version", "description", "states", "kinds", "outcomes", "restore_states"), vocabulary.fields.keys)
    }

    @Test fun onlyCodesContainingVisibleProduceWordingContainingVisible() {
        fun visible(text: String) = text.contains("visible", ignoreCase = true)
        listOf(
            "state" to OperationVocabulary.states, "kind" to OperationVocabulary.kinds,
            "outcome" to OperationVocabulary.outcomes, "restore state" to OperationVocabulary.restoreStates,
        ).forEach { (what, words) ->
            words.forEach { (code, label) -> assertEquals("$what $code -> $label", visible(code), visible(label)) }
        }
        // The three outcomes that carry the owner's confirmation are exactly the ones that may say it.
        assertEquals(
            setOf("visible_picture_confirmed", "active_screen_visible_and_confirmed", "current_mode_visible_and_read_back"),
            OperationVocabulary.outcomes.filterValues(::visible).keys,
        )
        assertEquals(emptySet<String>(), OperationVocabulary.states.filterValues(::visible).keys)
        assertEquals("a Completed state is only Completed", "Completed", OperationVocabulary.states["succeeded"])
    }

    @Test fun noCombinationOfCodesSaysVisibleUnlessACodeDoes() {
        var combinations = 0
        val kinds = OperationVocabulary.kinds.keys + "future.kind"
        val states = OperationVocabulary.states.keys + "paused"
        val outcomes = OperationVocabulary.outcomes.keys + listOf(null, "future_outcome")
        val restores = OperationVocabulary.restoreStates.keys + listOf(null, "future_restore")
        for (kind in kinds) for (state in states) for (outcome in outcomes) for (restore in restores) {
            val record = HostJournalRecord("op-1", kind, state, null, null, outcome, null, restore, null, null, emptyMap())
            val row = HostJournalPresentation.row(record)
            val words = row.title + " " + row.lines.joinToString(" ") + " " + row.spoken
            val codeSaysVisible = listOfNotNull(kind, state, outcome, restore).any { it.contains("visible") }
            assertEquals("$kind/$state/$outcome/$restore -> $words", codeSaysVisible, words.contains("visible", ignoreCase = true))
            combinations++
        }
        assertEquals(kinds.size * states.size * outcomes.size * restores.size, combinations)
    }

    @Test fun theStandingNoteIsExactlyTheSharedWordingAndItsOwnClaimIsNegative() {
        assertEquals(
            "Entries record what the host did and read back. A completed entry does not by itself show that the picture is visible.",
            HostJournalPresentation.STANDING_NOTE,
        )
        assertTrue(HostJournalPresentation.STANDING_NOTE.contains("does not by itself show"))
    }

    // --- the real fixtures -----------------------------------------------------------------------------

    @Test fun everyRecordInTheMixedFixtureReadsAsTheHostRecordedIt() {
        val view = HostJournalPresentation.view(journalFromContractFixture("operations/list-mixed"))
        assertEquals(5, view.total)
        assertFalse(view.truncated)
        assertEquals(
            listOf(
                HostJournalRow(
                    "op-fixture0007xxxxxxxxxxxxx", "Display mode preview · Returned, not confirmed",
                    listOf(
                        "Requested · output id: 1 · mode id: 2 · generation: 4",
                        "Recorded · Mode request returned; waiting for confirmation",
                        "Preview is waiting for visible-picture confirmation or host timeout",
                        "Updated 2026-09-13 12:00:24 UTC",
                    ),
                    "Display mode preview, Returned, not confirmed. Requested, output id: 1, mode id: 2, generation: 4. " +
                        "Recorded, Mode request returned; waiting for confirmation. " +
                        "Preview is waiting for visible-picture confirmation or host timeout. Updated 2026-09-13 12:00:24 UTC",
                ),
                HostJournalRow(
                    "op-fixture0005xxxxxxxxxxxxx", "Gaming Mode display preference · Completed",
                    listOf(
                        "Requested · output keys: drm:card0:HDMI-A-1, drm:card0:DP-1 · connectors: HDMI-A-1, DP-1 · generation: 4 · restart: false",
                        "Recorded · Gamescope output saved; applies after Gaming Mode restarts",
                        "Gamescope output preference saved; leave and re-enter Gaming Mode to apply it.",
                        "Updated 2026-09-13 12:00:21 UTC",
                    ),
                    "Gaming Mode display preference, Completed. " +
                        "Requested, output keys: drm:card0:HDMI-A-1, drm:card0:DP-1, connectors: HDMI-A-1, DP-1, generation: 4, restart: false. " +
                        "Recorded, Gamescope output saved; applies after Gaming Mode restarts. " +
                        "Gamescope output preference saved; leave and re-enter Gaming Mode to apply it. Updated 2026-09-13 12:00:21 UTC",
                ),
                HostJournalRow(
                    "op-fixture0004xxxxxxxxxxxxx", "Screen selection preview · Failed",
                    listOf(
                        "Requested · target output key: drm:card0:HDMI-A-1 · baseline output key: drm:card0:DP-1 · generation: 4",
                        "Previous screen restored",
                        "host preview deadline expired; baseline screen restored",
                        "Updated 2026-09-13 12:00:19 UTC",
                    ),
                    "Screen selection preview, Failed. " +
                        "Requested, target output key: drm:card0:HDMI-A-1, baseline output key: drm:card0:DP-1, generation: 4. " +
                        "Previous screen restored. host preview deadline expired; baseline screen restored. Updated 2026-09-13 12:00:19 UTC",
                ),
                HostJournalRow(
                    "op-fixture0002xxxxxxxxxxxxx", "Suspend · Returned, not confirmed",
                    listOf(
                        "Requested · action: suspend",
                        "Recorded · Steam returned from the call; the physical result is not confirmed",
                        "Steam returned; physical suspend transition is not confirmed by this response",
                        "Updated 2026-09-13 12:00:05 UTC",
                    ),
                    "Suspend, Returned, not confirmed. Requested, action: suspend. " +
                        "Recorded, Steam returned from the call; the physical result is not confirmed. " +
                        "Steam returned; physical suspend transition is not confirmed by this response. Updated 2026-09-13 12:00:05 UTC",
                ),
                HostJournalRow(
                    "op-fixture0001xxxxxxxxxxxxx", "Save current display mode · Completed",
                    listOf(
                        "Requested · profile id: profile-fixture0001xxxxxxxxxxxxx",
                        "Recorded · Current mode read back; owner confirmed the picture was visible",
                        "Updated 2026-09-13 12:00:00 UTC",
                    ),
                    "Save current display mode, Completed. Requested, profile id: profile-fixture0001xxxxxxxxxxxxx. " +
                        "Recorded, Current mode read back; owner confirmed the picture was visible. Updated 2026-09-13 12:00:00 UTC",
                ),
            ),
            view.rows,
        )
    }

    @Test fun everyRecordInTheOlderListFixtureReadsAsTheHostRecordedIt() {
        val body = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/operations-list.json"))
        val journal = decodeList(body)
        val rows = HostJournalPresentation.view(journal).rows
        assertEquals(
            listOf(
                "Save current display mode · Completed" to listOf(
                    "Requested · profile id: profile-example",
                    "Recorded · Current mode read back; owner confirmed the picture was visible",
                    "Updated 2026-09-25 10:00:01 UTC",
                ),
                "Screen selection preview · Completed" to listOf(
                    "Requested · output id: output-1 · mode: 1920x1080@60",
                    "Recorded · Previous mode restored and read back",
                    "Updated 2026-09-25 09:59:16 UTC",
                ),
            ),
            rows.map { it.title to it.lines },
        )
    }

    @Test fun theEmptyFixtureHasNoRowsAndIsNotTruncated() {
        val view = HostJournalPresentation.view(journalFromContractFixture("operations/list-empty"))
        assertEquals(HostJournalView(emptyList(), 0), view)
        assertFalse(view.truncated)
    }

    // --- unknown codes are shown, sanitised ------------------------------------------------------------

    @Test fun anUnknownCodeIsShownAsSanitisedTextNotHidden() {
        val row = HostJournalPresentation.row(record(
            kind = "future.kind-2", state = "paused", outcome = "weird\u0000 ‮outcome\u0007", restoreState = "half_done",
        ))
        assertEquals("future.kind-2 · paused", row.title)
        assertEquals(listOf("Recorded · weird outcome", "Restore state · half_done"), row.lines)
    }

    @Test fun unknownCodesAreBoundedAndNeverBlank() {
        val long = "x".repeat(200)
        val row = HostJournalPresentation.row(record(kind = "k".repeat(100), state = "s".repeat(100), outcome = long, restoreState = long))
        assertEquals("k".repeat(64) + " · " + "s".repeat(64), row.title)
        assertEquals("Recorded · " + "x".repeat(64), row.lines[0])
        assertEquals("Restore state · " + "x".repeat(64), row.lines[1])
        // Nothing printable left: say so rather than show an empty word.
        assertEquals("Recorded · Unrecognised", HostJournalPresentation.row(record(outcome = "\u0000‮")).lines.single())
    }

    @Test fun knownCodesUseTheSharedWordsEvenWhenTheOtherCodesAreUnknown() {
        val words = HostJournalPresentation.wording("power.restart", "failed", "revoked", "unknown")
        assertEquals(JournalWording("Restart", "Failed", "This client's access was revoked", "Restore result not confirmed"), words)
        assertEquals(JournalWording("Restart", "Failed", null, null), HostJournalPresentation.wording("power.restart", "failed", null, null))
    }

    // --- the requested line ----------------------------------------------------------------------------

    @Test fun theRequestedLineShowsTheFirstFourFactsWithReadableKeys() {
        val target = linkedMapOf<String, JournalValue>(
            "output_id" to JournalValue.Text("1"), "mode_id" to JournalValue.Text("2"), "generation" to JournalValue.Whole(4),
            "restart" to JournalValue.Flag(true), "fifth" to JournalValue.Text("never shown"),
        )
        val line = HostJournalPresentation.row(record(target = target)).lines.single()
        assertEquals("Requested · output id: 1 · mode id: 2 · generation: 4 · restart: true", line)
    }

    @Test fun targetValuesReadAsPlainFacts() {
        fun line(vararg entries: Pair<String, JournalValue>) =
            HostJournalPresentation.row(record(target = linkedMapOf(*entries))).lines.single()
        assertEquals("Requested · rate: 59.94", line("rate" to JournalValue.Real(59.94)))
        assertEquals("Requested · rate: 60", line("rate" to JournalValue.Real(60.0)))
        assertEquals("Requested · rate: -3", line("rate" to JournalValue.Whole(-3)))
        assertEquals("Requested · keys: a, b", line("keys" to JournalValue.TextList(listOf("a", "b"))))
        assertEquals("Requested · mode: 1920x1080", line("mode" to JournalValue.Group(mapOf(
            "width" to JournalValue.Whole(1920), "height" to JournalValue.Whole(1080)))))
        assertEquals("Requested · mode: 2560x1440@60", line("mode" to JournalValue.Group(mapOf(
            "width" to JournalValue.Whole(2560), "height" to JournalValue.Whole(1440), "refresh_hz" to JournalValue.Real(59.94)))))
        assertEquals("Requested · mode: scale 2, label x", line("mode" to JournalValue.Group(linkedMapOf(
            "scale" to JournalValue.Whole(2), "label" to JournalValue.Text("x")))))
    }

    @Test fun anOverlongRequestedLineIsCutWithAnEllipsisNotShownInFull() {
        val keys = (1..16).map { "k$it".padEnd(128, 'x') }
        val line = HostJournalPresentation.row(record(target = mapOf("keys" to JournalValue.TextList(keys)))).lines.single()
        assertEquals(300, line.length)
        assertTrue(line.endsWith("…"))
        assertTrue(line.startsWith("Requested · keys: k1"))
    }

    @Test fun aRecordWithNoTargetOutcomeRestoreReasonOrTimeIsJustItsTitle() {
        val row = HostJournalPresentation.row(record())
        assertEquals("Save current display mode · Completed", row.title)
        assertEquals(emptyList<String>(), row.lines)
        assertEquals("Save current display mode, Completed", row.spoken)
    }

    // --- time ------------------------------------------------------------------------------------------

    @Test fun onlyAZuluTimestampIsShownAndAnythingElseIsLeftOut() {
        fun updated(stamp: String?) = HostJournalPresentation.row(record(updatedAt = stamp)).lines
        assertEquals(listOf("Updated 2026-09-13 12:00:24 UTC"), updated("2026-09-13T12:00:24.250000Z"))
        assertEquals(listOf("Updated 2026-09-13 12:00:24 UTC"), updated("2026-09-13T12:00:24Z"))
        listOf(null, "", "yesterday", "2026-09-13T12:00:24+07:00", "2026-09-13 12:00:24Z", "2026-09-13T12:00:24").forEach {
            assertEquals(it, emptyList<String>(), updated(it))
        }
    }

    // --- the newest twenty -----------------------------------------------------------------------------

    @Test fun onlyTheNewestTwentyAreShownOfTheRecordsKept() {
        fun journal(count: Int) = HostJournal((1..count).map { record(id = "op-$it") })
        val thirty = HostJournalPresentation.view(journal(30))
        assertEquals((1..20).map { "op-$it" }, thirty.rows.map { it.id })
        assertEquals(30, thirty.total)
        assertTrue(thirty.truncated)

        val twenty = HostJournalPresentation.view(journal(20))
        assertEquals(20, twenty.rows.size)
        assertFalse(twenty.truncated)

        val full = HostJournalPresentation.view(journal(256))
        assertEquals(HostJournalPresentation.MAX_ROWS, full.rows.size)
        assertEquals(256, full.total)
        assertEquals(20, HostJournalPresentation.MAX_ROWS)

        val one = HostJournalPresentation.view(journal(1))
        assertEquals(1, one.rows.size)
        assertFalse(one.truncated)
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun record(
        id: String = "op-1",
        kind: String = "display.save-current",
        state: String = "succeeded",
        updatedAt: String? = null,
        outcome: String? = null,
        restoreState: String? = null,
        target: Map<String, JournalValue> = emptyMap(),
    ) = HostJournalRecord(id, kind, state, null, updatedAt, outcome, null, restoreState, null, null, target)

    private fun journalFromContractFixture(name: String): HostJournal {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        return decodeList(fixture["response"].asObject("response")["body"].asObject("body"))
    }

    private fun decodeList(body: JsonValue.ObjectValue): HostJournal {
        val reply = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return checkNotNull(((reply as ApiReply.Versioned).payload as VersionedPayload.OperationList).journal)
    }
}
