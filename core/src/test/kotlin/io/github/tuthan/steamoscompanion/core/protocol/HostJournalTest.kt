package io.github.tuthan.steamoscompanion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `GET /v1/operations` is diagnostic. These tests pin what the vendored fixtures carry through the real
 * codec and, above all, that no shape of the body can make the read fail or throw: it decodes to "no
 * journal" at worst, and every record that is kept was rebuilt from an allowlist.
 */
class HostJournalTest {
    // --- the route -------------------------------------------------------------------------------------

    @Test fun theListRouteIsTheExactBodylessGetAndNothingElse() {
        val route = ApiRoute.match(HttpMethod.GET, "/v1/operations")
        assertEquals(ApiRoute.OPERATION_LIST, route.route)
        assertNull(route.operationId)
        assertTrue(ApiRoute.OPERATION_LIST.requiresBearer)
        assertEquals(ApiRoute.OPERATION, ApiRoute.match(HttpMethod.GET, "/v1/operations/op-1").route)

        listOf(
            "/v1/operations?limit=5", "/v1/operations?", "/v1/operations#x", "/v1/operations/", "/v1/Operations",
            "/v1/operations/%2e", "/v1/operations ", "v1/operations", "//v1/operations", "/v1/operations\n",
            "https://deck.local/v1/operations",
        ).forEach { path ->
            try {
                ApiRoute.match(HttpMethod.GET, path)
                fail("$path must not match a route")
            } catch (_: RequestValidationException) {
                // expected
            }
        }
        try {
            ApiRoute.match(HttpMethod.POST, "/v1/operations")
            fail("POST /v1/operations must not match a route")
        } catch (_: RequestValidationException) {
            // expected
        }
    }

    @Test fun theListRequestCarriesNoBodyAndNeedsACredential() {
        val request = ApiRequest(HttpMethod.GET, "/v1/operations", bearerToken = "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx")
        assertEquals(ApiRoute.OPERATION_LIST, request.validate().route)
        assertTrue(ProtocolCodec.encodeRequest(request).body.isEmpty())
        listOf(
            ApiRequest(HttpMethod.GET, "/v1/operations"),
            ApiRequest(HttpMethod.GET, "/v1/operations", bearerToken = " "),
            ApiRequest(HttpMethod.GET, "/v1/operations", jsonObject(), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx"),
            ApiRequest(HttpMethod.POST, "/v1/operations", jsonObject(), "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx"),
        ).forEach { bad ->
            try {
                bad.validate()
                fail("$bad must not validate")
            } catch (_: RequestValidationException) {
                // expected
            }
        }
    }

    // --- pinned fixtures -------------------------------------------------------------------------------

    @Test fun emptyFixtureIsAJournalWithNoRecords() {
        assertEquals(HostJournal(emptyList()), journal("operations/list-empty"))
    }

    @Test fun mixedFixtureCarriesEveryRecordInTheHostsOrderWithTheAllowlistedFields() {
        val records = checkNotNull(journal("operations/list-mixed")).records
        assertEquals(
            listOf("op-fixture0007xxxxxxxxxxxxx", "op-fixture0005xxxxxxxxxxxxx", "op-fixture0004xxxxxxxxxxxxx",
                "op-fixture0002xxxxxxxxxxxxx", "op-fixture0001xxxxxxxxxxxxx"),
            records.map { it.id },
        )

        assertEquals(
            HostJournalRecord(
                id = "op-fixture0007xxxxxxxxxxxxx", kind = "display.preview", state = "observed_return",
                createdAt = "2026-09-13T12:00:24.250000Z", updatedAt = "2026-09-13T12:00:24.250000Z",
                outcome = "mode_request_returned",
                reason = "Preview is waiting for visible-picture confirmation or host timeout",
                restoreState = null, restored = null, resolvedBy = null,
                target = mapOf("output_id" to text("1"), "mode_id" to text("2"), "generation" to JournalValue.Whole(4)),
            ),
            records[0],
        )
        assertEquals(
            HostJournalRecord(
                id = "op-fixture0005xxxxxxxxxxxxx", kind = "display.preference", state = "succeeded",
                createdAt = "2026-09-13T12:00:21.250000Z", updatedAt = "2026-09-13T12:00:21.250000Z",
                outcome = "gamescope_output_configured",
                reason = "Gamescope output preference saved; leave and re-enter Gaming Mode to apply it.",
                restoreState = null, restored = null, resolvedBy = null,
                target = linkedMapOf(
                    "output_keys" to JournalValue.TextList(listOf("drm:card0:HDMI-A-1", "drm:card0:DP-1")),
                    "connectors" to JournalValue.TextList(listOf("HDMI-A-1", "DP-1")),
                    "generation" to JournalValue.Whole(4),
                    "restart" to JournalValue.Flag(false),
                ),
            ),
            records[1],
        )
        assertEquals(
            HostJournalRecord(
                id = "op-fixture0004xxxxxxxxxxxxx", kind = "display.selection.preview", state = "failed",
                createdAt = "2026-09-13T12:00:15.250000Z", updatedAt = "2026-09-13T12:00:19.250000Z",
                outcome = null, reason = "host preview deadline expired; baseline screen restored",
                restoreState = "succeeded", restored = true, resolvedBy = "active_output_and_mode",
                target = mapOf(
                    "target_output_key" to text("drm:card0:HDMI-A-1"), "baseline_output_key" to text("drm:card0:DP-1"),
                    "generation" to JournalValue.Whole(4),
                ),
            ),
            records[2],
        )
        assertEquals("power.suspend", records[3].kind)
        assertEquals("method_returned", records[3].outcome)
        assertEquals(mapOf("action" to text("suspend")), records[3].target)
        assertEquals("display.save-current", records[4].kind)
        assertEquals("current_mode_visible_and_read_back", records[4].outcome)
        assertNull(records[4].reason)
        assertEquals(mapOf("profile_id" to text("profile-fixture0001xxxxxxxxxxxxx")), records[4].target)
    }

    @Test fun theHostsOlderListFixtureKeepsItsOneLevelModeObject() {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/operations-list.json"))
        val records = checkNotNull(decodeBody(fixture)).records
        assertEquals(listOf("op-save-current", "op-host-display-selection"), records.map { it.id })
        assertEquals(
            mapOf("output_id" to text("output-1"), "mode" to JournalValue.Group(mapOf(
                "width" to JournalValue.Whole(1920), "height" to JournalValue.Whole(1080), "refresh_hz" to JournalValue.Whole(60)))),
            records[1].target,
        )
    }

    @Test fun theUnauthorizedAndOlderHostEnvelopesAreRejectionsNotJournals() {
        val reply = decodeFixtureReply("operations/list-unauthorized")
        assertTrue(reply is ApiReply.Rejected)
        reply as ApiReply.Rejected
        assertEquals(401, reply.status)
        assertEquals("unauthorized", reply.error)

        val older = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST,
            HttpResponse(404, emptyMap(), """{"error":"not_found","message":"not found"}""".toByteArray()))
        assertTrue(older is ApiReply.Rejected)
        assertEquals(404, (older as ApiReply.Rejected).status)
        assertEquals("not_found", older.error)
    }

    // --- the allowlist ---------------------------------------------------------------------------------

    @Test fun onlyTheAllowlistedFieldsSurviveWhateverElseTheRecordCarries() {
        val hostile = record {
            it["request_id"] = "REQUEST_MARKER".asJson()
            it["preview_id"] = "PREVIEW_MARKER".asJson()
            it["client_id"] = "CLIENT_MARKER".asJson()
            it["authorization"] = "Bearer TOKEN_MARKER".asJson()
            it["__proto__"] = jsonObject("polluted" to true.asJson())
            it["extra"] = JsonValue.ArrayValue(listOf("EXTRA_MARKER".asJson()))
        }
        val kept = checkNotNull(decodeRecords(hostile)).records.single()
        val rendered = kept.toString()
        listOf("REQUEST_MARKER", "PREVIEW_MARKER", "CLIENT_MARKER", "TOKEN_MARKER", "EXTRA_MARKER", "polluted").forEach {
            assertFalse("kept $it", rendered.contains(it))
        }
        assertEquals("op-1", kept.id)
    }

    @Test fun aRecordWhoseIdKindOrStateIsNotASafeTokenIsDroppedAndTheRestSurvive() {
        val tokens = listOf(
            "", " ", "-leading", ".leading", "_leading", ":leading", "has space", "has/slash", "has\"quote", "<b>", "semi;colon",
            "tab\there", "ok\n", "ok\r\n", "line\nbreak", "nul\u0000", "ünïcode", "x".repeat(129), "emoji😀",
        )
        fun bad(field: String, value: JsonValue) = record { it[field] = value }
        val hostile = tokens.flatMap { token ->
            listOf("id", "kind", "state").map { field -> bad(field, token.asJson()) }
        } + listOf("id", "kind", "state").flatMap { field ->
            listOf(JsonValue.NullValue, 7L.asJson(), true.asJson(), JsonValue.ArrayValue(emptyList()), jsonObject())
                .map { value -> bad(field, value) }
        } + listOf("id", "kind", "state").map { field -> record { it.remove(field) } }
        val good = record { it["id"] = "op-good".asJson() }
        val kept = checkNotNull(decodeRecords(*(hostile + good).toTypedArray())).records
        assertEquals(listOf("op-good"), kept.map { it.id })

        // Boundaries of the token: 128 characters pass, and ':' '.' '-' '_' are allowed inside.
        val edge = listOf("a".repeat(128), "0", "A-b_c.d:e", "display.selection.preview").map { value ->
            record { it["id"] = value.asJson(); it["kind"] = value.asJson(); it["state"] = value.asJson() }
        }
        assertEquals(edge.size, checkNotNull(decodeRecords(*edge.toTypedArray())).records.size)
    }

    @Test fun anUnknownKindOrStateIsKeptAsATokenSoItCanBeShownNotHidden() {
        val kept = checkNotNull(decodeRecords(record { it["kind"] = "future.kind".asJson(); it["state"] = "paused".asJson() })).records.single()
        assertEquals("future.kind", kept.kind)
        assertEquals("paused", kept.state)
    }

    @Test fun textFieldsAreTruncatedAndStrippedOfControlCharacters() {
        val noisy = "ab\u0000c\u0007d\u001be\u007ff\u0085g‮h​i j kl"
        val kept = checkNotNull(decodeRecords(record {
            it["reason"] = noisy.asJson()
            it["outcome"] = "out\u0000come".asJson()
            it["restore_state"] = "rest\u0007ore".asJson()
            it["resolved_by"] = "by\nme".asJson()
            it["created_at"] = "2026-09-13T12:00:00Z\u0000".asJson()
        })).records.single()
        assertEquals("abcdefghijkl", kept.reason)
        assertEquals("outcome", kept.outcome)
        assertEquals("restore", kept.restoreState)
        assertEquals("by me", kept.resolvedBy)
        assertEquals("2026-09-13T12:00:00Z", kept.createdAt)
        listOfNotNull(kept.reason, kept.outcome, kept.restoreState, kept.resolvedBy, kept.createdAt).forEach { text ->
            assertTrue(text, text.none { Character.getType(it).toByte().let { type ->
                type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
            } })
        }
    }

    @Test fun everyTextFieldIsTruncatedToItsSchemaBoundWithoutSplittingACharacter() {
        val long = "x".repeat(4000)
        val kept = checkNotNull(decodeRecords(record {
            it["reason"] = long.asJson()
            it["outcome"] = long.asJson()
            it["restore_state"] = long.asJson()
            it["resolved_by"] = long.asJson()
            it["created_at"] = long.asJson()
            it["updated_at"] = long.asJson()
        })).records.single()
        assertEquals(256, kept.reason!!.length)
        assertEquals(128, kept.outcome!!.length)
        assertEquals(64, kept.restoreState!!.length)
        assertEquals(128, kept.resolvedBy!!.length)
        assertEquals(64, kept.createdAt!!.length)
        assertEquals(64, kept.updatedAt!!.length)

        // A pair of surrogates at the cut is kept or dropped whole, never halved.
        val emoji = "😀"
        val cut = checkNotNull(decodeRecords(record { it["reason"] = ("y".repeat(255) + emoji + "z").asJson() })).records.single().reason!!
        assertEquals("y".repeat(255) + emoji, cut)
        assertEquals(256, cut.codePointCount(0, cut.length))
    }

    @Test fun mistypedOptionalFieldsAreNullNotErrors() {
        val kept = checkNotNull(decodeRecords(record {
            it["created_at"] = 5L.asJson()
            it["updated_at"] = true.asJson()
            it["outcome"] = jsonObject()
            it["reason"] = JsonValue.ArrayValue(listOf("x".asJson()))
            it["restore_state"] = 1L.asJson()
            it["restored"] = "true".asJson()
            it["resolved_by"] = JsonValue.NullValue
            it["target"] = "not an object".asJson()
        })).records.single()
        assertEquals(
            HostJournalRecord("op-1", "display.save-current", "succeeded", null, null, null, null, null, null, null, emptyMap()),
            kept,
        )
        // Blank text is "not reported", not an empty string to render.
        val blank = checkNotNull(decodeRecords(record { it["reason"] = " \t\n ".asJson(); it["outcome"] = "".asJson() })).records.single()
        assertNull(blank.reason)
        assertNull(blank.outcome)
    }

    // --- the list itself -------------------------------------------------------------------------------

    @Test fun aListThatIsNotAnArrayIsNoJournalAndStillAVersionedReply() {
        listOf(
            """{"protocol_version":1}""",
            """{"protocol_version":1,"operations":null}""",
            """{"protocol_version":1,"operations":"nope"}""",
            """{"protocol_version":1,"operations":{"0":{"id":"op-1"}}}""",
            """{"protocol_version":1,"operations":7}""",
            """{"protocol_version":1,"operations":true}""",
        ).forEach { json ->
            val reply = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST, HttpResponse(200, emptyMap(), json.toByteArray()))
            assertTrue(json, reply is ApiReply.Versioned)
            val payload = (reply as ApiReply.Versioned).payload as VersionedPayload.OperationList
            assertNull(json, payload.journal)
        }
    }

    @Test fun entriesThatAreNotObjectsAreDroppedWithoutEndingTheList() {
        val items = listOf(
            JsonValue.NullValue, 1L.asJson(), "text".asJson(), true.asJson(), JsonValue.ArrayValue(listOf(record())),
            record { it["id"] = "op-a".asJson() }, jsonObject(), record { it["id"] = "op-b".asJson() },
        )
        assertEquals(listOf("op-a", "op-b"), checkNotNull(decodeRecords(*items.toTypedArray())).records.map { it.id })
    }

    @Test fun twoHundredFiftySixRecordsAreKeptAndMoreIsNotAConformingHost() {
        fun many(count: Int) = (1..count).map { n -> record { it["id"] = "op-$n".asJson() } }.toTypedArray()
        val exactly = checkNotNull(decodeRecords(*many(256)))
        assertEquals(256, exactly.records.size)
        assertEquals("op-1", exactly.records.first().id)
        assertEquals("op-256", exactly.records.last().id)

        assertNull("257 records is over the schema's maxItems", decodeRecords(*many(257)))
        assertNull("far over", decodeRecords(*many(1500)))
        // The bound is on what the host sent, not on what survived the allowlist.
        assertNull(decodeRecords(*Array(257) { JsonValue.NullValue }))
    }

    @Test fun theHostsOrderIsKeptNotReSorted() {
        val kept = checkNotNull(decodeRecords(
            record { it["id"] = "op-b".asJson(); it["updated_at"] = "2026-01-01T00:00:00Z".asJson() },
            record { it["id"] = "op-a".asJson(); it["updated_at"] = "2027-01-01T00:00:00Z".asJson() },
        )).records
        assertEquals(listOf("op-b", "op-a"), kept.map { it.id })
    }

    @Test fun duplicateRecordIdsDoNotBreakTheList() {
        val kept = checkNotNull(decodeRecords(record(), record(), record())).records
        assertEquals(3, kept.size)
    }

    // --- target ----------------------------------------------------------------------------------------

    @Test fun targetKeepsScalarsStringListsAndOneLevelOfScalars() {
        val kept = checkNotNull(decodeRecords(record {
            it["target"] = parse("""
                {"flag": true, "count": 4, "negative": -3, "rate": 59.94, "name": "HDMI-A-1",
                 "keys": ["a", "b"], "mode": {"width": 1920, "height": 1080, "refresh_hz": 59.94, "hdr": false, "label": "x"}}
            """)
        })).records.single()
        assertEquals(
            linkedMapOf(
                "flag" to JournalValue.Flag(true), "count" to JournalValue.Whole(4), "negative" to JournalValue.Whole(-3),
                "rate" to JournalValue.Real(59.94), "name" to text("HDMI-A-1"),
                "keys" to JournalValue.TextList(listOf("a", "b")),
                "mode" to JournalValue.Group(linkedMapOf(
                    "width" to JournalValue.Whole(1920), "height" to JournalValue.Whole(1080),
                    "refresh_hz" to JournalValue.Real(59.94), "hdr" to JournalValue.Flag(false), "label" to text("x"))),
            ),
            kept.target,
        )
    }

    @Test fun targetIsBoundedToEightKeysAndDropsWhatDoesNotFit() {
        val twelve = (1..12).joinToString(",") { "\"k$it\": $it" }
        val kept = checkNotNull(decodeRecords(record { it["target"] = parse("{$twelve}") })).records.single()
        assertEquals((1..8).map { "k$it" }, kept.target.keys.toList())
        assertTrue(kept.target.size <= HostJournalDecoder.MAX_TARGET_KEYS)

        // Eight entries are considered; invalid keys use up a slot, so a ninth valid entry never appears.
        val withBadKeys = checkNotNull(decodeRecords(record {
            it["target"] = parse("""{"bad key": 1, "": 2, "-x": 3, "ok1": 4, "ok2": 5, "ok3": 6, "ok4": 7, "ok5": 8, "ok6": 9, "ok7": 10}""")
        })).records.single()
        assertEquals(listOf("ok1", "ok2", "ok3", "ok4", "ok5"), withBadKeys.target.keys.toList())
    }

    @Test fun targetEntriesWithOddValuesAreDroppedIndividually() {
        // Eight entries are considered, so the odd shapes are split over two targets, each keeping only "fine".
        val seventeen = (1..17).joinToString(",") { "\"s$it\"" }
        val first = checkNotNull(decodeRecords(record {
            it["target"] = parse("""
                {"fine": "kept", "null": null, "mixed": ["a", 1], "nested_list": [["a"]], "objects": [{"a": 1}], "empty": [],
                 "emptyobj": {}, "deep": {"a": {"b": 1}}}
            """)
        })).records.single()
        assertEquals(mapOf("fine" to text("kept")), first.target)
        val second = checkNotNull(decodeRecords(record {
            it["target"] = parse("""{"fine": "kept", "grouplist": {"a": ["x"]}, "long": [$seventeen]}""")
        })).records.single()
        assertEquals(mapOf("fine" to text("kept")), second.target)
        // Sixteen strings is the longest list that is kept, and it is kept whole.
        val sixteen = (1..16).map { "s$it" }
        val kept = checkNotNull(decodeRecords(record {
            it["target"] = parse("""{"list": [${sixteen.joinToString(",") { "\"$it\"" }}]}""")
        })).records.single()
        assertEquals(mapOf("list" to JournalValue.TextList(sixteen)), kept.target)
    }

    @Test fun targetNestedValuesBeyondOneLevelOfScalarsAreDroppedFromTheGroup() {
        val kept = checkNotNull(decodeRecords(record {
            it["target"] = parse("""{"mode": {"w": 1, "inner": {"x": 1}, "list": [1], "nothing": null, "ok": "y", "bad key": 5}}""")
        })).records.single()
        assertEquals(mapOf("mode" to JournalValue.Group(linkedMapOf("w" to JournalValue.Whole(1), "ok" to text("y")))), kept.target)

        // A group holds at most six entries.
        val big = (1..10).joinToString(",") { "\"n$it\": $it" }
        val six = checkNotNull(decodeRecords(record { it["target"] = parse("""{"mode": {$big}}""") })).records.single()
        assertEquals((1..6).map { "n$it" }, (six.target.getValue("mode") as JournalValue.Group).entries.keys.toList())
    }

    @Test fun targetNumbersAreOnlyKeptWhenTheyAreFiniteAndBelowTwoToTheFiftyThird() {
        val kept = checkNotNull(decodeRecords(record {
            it["target"] = parse("""
                {"a": 9007199254740991, "b": 9007199254740992, "c": 99999999999999999999, "d": 1e400, "e": -9007199254740991,
                 "f": 1e30, "g": 0.5, "h": 1E2}
            """)
        })).records.single()
        assertEquals(
            mapOf(
                "a" to JournalValue.Whole(9007199254740991), "e" to JournalValue.Whole(-9007199254740991),
                "g" to JournalValue.Real(0.5), "h" to JournalValue.Real(100.0),
            ),
            kept.target,
        )
        val more = checkNotNull(decodeRecords(record {
            it["target"] = parse("""{"zero": -0, "frac": 12345678901234567890.5, "ok": 7}""")
        })).records.single()
        assertEquals(mapOf("zero" to JournalValue.Whole(0), "ok" to JournalValue.Whole(7)), more.target)
    }

    @Test fun targetTextIsTruncatedAndStrippedLikeEveryOtherString() {
        val kept = checkNotNull(decodeRecords(record {
            it["target"] = parse("""{"name": "a\u0000b‮c${"z".repeat(500)}", "keys": ["x\u0007y", "${"q".repeat(300)}"], "blank": "  ", "mode": {"label": "m\u0000${"w".repeat(300)}"}}""")
        })).records.single()
        assertEquals(128, (kept.target["name"] as JournalValue.Text).value.length)
        assertTrue((kept.target["name"] as JournalValue.Text).value.startsWith("abcz"))
        assertEquals(listOf("xy", "q".repeat(128)), (kept.target["keys"] as JournalValue.TextList).values)
        assertNull("blank text is not a fact", kept.target["blank"])
        assertEquals(128, ((kept.target["mode"] as JournalValue.Group).entries["label"] as JournalValue.Text).value.length)
    }

    @Test fun aTargetWithOnlyUnusableEntriesIsEmpty() {
        val kept = checkNotNull(decodeRecords(record { it["target"] = parse("""{"a": null, "b": [], "c": {}}""") })).records.single()
        assertTrue(kept.target.isEmpty())
        assertTrue(checkNotNull(decodeRecords(record { it["target"] = JsonValue.NullValue })).records.single().target.isEmpty())
        assertTrue(checkNotNull(decodeRecords(record { it.remove("target") })).records.single().target.isEmpty())
    }

    // --- nothing can fail the read ---------------------------------------------------------------------

    @Test fun noShapeOfTheOperationsValueCanThrowFromTheCodec() {
        val shapes = listOf<JsonValue>(
            JsonValue.NullValue, "x".asJson(), 1L.asJson(), true.asJson(), jsonObject(), jsonObject("id" to "op-1".asJson()),
            JsonValue.ArrayValue(listOf(JsonValue.NullValue)),
            JsonValue.ArrayValue(listOf(JsonValue.ArrayValue(listOf(JsonValue.ArrayValue(emptyList()))))),
            JsonValue.ArrayValue(listOf(jsonObject("id" to JsonValue.NullValue, "kind" to jsonObject()))),
            JsonValue.ArrayValue(listOf(record { it["target"] = JsonValue.ArrayValue(listOf(jsonObject())) })),
            JsonValue.ArrayValue(listOf(record { it["target"] = jsonObject("k" to jsonObject("j" to jsonObject("i" to jsonObject()))) })),
            JsonValue.ArrayValue(List(300) { record() }),
        )
        shapes.forEach { shape ->
            val reply = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST, HttpResponse(200, emptyMap(),
                BoundedJson.encode(jsonObject("protocol_version" to 1L.asJson(), "operations" to shape))))
            assertTrue(shape.toString(), reply is ApiReply.Versioned)
            // And the decoder itself, called directly with the same shapes, returns rather than throws.
            HostJournalDecoder.decode(shape)
        }
        assertNull(HostJournalDecoder.decode(null))
    }

    @Test fun anotherVersionOrAnErrorEnvelopeNeverReachesTheDecoder() {
        val newer = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST,
            HttpResponse(200, emptyMap(), """{"protocol_version":2,"operations":[]}""".toByteArray()))
        assertTrue(newer is ApiReply.UnsupportedVersion)
        val failed = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST,
            HttpResponse(500, emptyMap(), """{"error":"internal_error","message":"boom"}""".toByteArray()))
        assertTrue(failed is ApiReply.Rejected)
    }

    // --- text cleaning ---------------------------------------------------------------------------------

    @Test fun cleanTextCollapsesWhitespaceTrimsAndReturnsNullWhenNothingIsLeft() {
        assertEquals("a b c", JournalText.clean("  a \t b\n\r c  ", 64))
        assertEquals("a b", JournalText.clean("a  b", 64))
        assertEquals("ab", JournalText.clean("a\u0000b", 64))
        assertNull(JournalText.clean("", 64))
        assertNull(JournalText.clean(" \u0000‮ \n", 64))
        assertEquals("abc", JournalText.clean("abcdef", 3))
        assertEquals("ab", JournalText.clean("ab  cd", 3))
        assertNotNull(JournalText.clean("😀", 1))
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private fun text(value: String) = JournalValue.Text(value)

    private fun parse(json: String): JsonValue = BoundedJson.parseObject(json.toByteArray(Charsets.UTF_8))

    /** A conforming record that [edit] may then corrupt. */
    private fun record(edit: (MutableMap<String, JsonValue>) -> Unit = {}): JsonValue.ObjectValue {
        val fields = linkedMapOf<String, JsonValue>(
            "id" to "op-1".asJson(),
            "request_id" to "req-1".asJson(),
            "state" to "succeeded".asJson(),
            "kind" to "display.save-current".asJson(),
            "created_at" to "2026-09-13T12:00:00Z".asJson(),
            "updated_at" to "2026-09-13T12:00:01Z".asJson(),
        )
        edit(fields)
        return JsonValue.ObjectValue(fields)
    }

    private fun fixtureReply(name: String) =
        BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))["response"].asObject("response")

    private fun decodeFixtureReply(name: String): ApiReply {
        val response = fixtureReply(name)
        return ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST,
            HttpResponse(response["status"].asLong("status").toInt(), emptyMap(), BoundedJson.encode(response["body"]!!)))
    }

    private fun journal(name: String): HostJournal? =
        decodeBody(fixtureReply(name)["body"].asObject("body"))

    private fun decodeBody(body: JsonValue.ObjectValue): HostJournal? {
        val reply = ProtocolCodec.decodeResponse(ApiRoute.OPERATION_LIST, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        assertTrue("the read must still succeed: $reply", reply is ApiReply.Versioned)
        return ((reply as ApiReply.Versioned).payload as VersionedPayload.OperationList).journal
    }

    private fun decodeRecords(vararg items: JsonValue): HostJournal? =
        decodeBody(jsonObject("protocol_version" to 1L.asJson(), "operations" to JsonValue.ArrayValue(items.toList())))
}
