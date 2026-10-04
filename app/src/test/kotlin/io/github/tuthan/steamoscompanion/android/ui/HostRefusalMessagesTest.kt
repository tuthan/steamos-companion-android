package io.github.tuthan.steamoscompanion.android.ui

import io.github.tuthan.steamoscompanion.core.client.ActiveHost
import io.github.tuthan.steamoscompanion.core.client.JournalEntry
import io.github.tuthan.steamoscompanion.core.client.JournalPhase
import io.github.tuthan.steamoscompanion.core.client.JournalRejectionCode
import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the owner reads after the host answers a request with 403 `forbidden`: the plain refusal, not a generic failure.
 * Every other outcome keeps the wording it already had.
 */
class HostRefusalMessagesTest {
    private val plain = "The host owner has not allowed this action for this pairing."

    private fun entry(phase: JournalPhase, code: JournalRejectionCode?): JournalEntry {
        val host = ActiveHost("host-fixture0001xxxxxxxxxxxxx", "client-android", "deck.local:18443", "sha256:" + "ab".repeat(32),
            "fixture-token-0002-xxxxxxxxxxxxxxxxxxxxxxxx", listOf("status.read"),
            StoredWakeTarget(false, "", null, null, null), 1_780_000_000_000)
        val request = ApiRequest(HttpMethod.POST, "/v1/power",
            jsonObject("request_id" to JsonValue.StringValue("power-1"), "action" to JsonValue.StringValue("suspend")),
            host.bearerToken)
        return JournalEntry.create(1, host, request, BoundedJson.encode(checkNotNull(request.body)), 100)
            .copy(phase = phase, rejectionCode = code)
    }

    @Test fun aForbiddenPowerOrSunshineRequestIsTheOwnersPlainRefusal() {
        assertEquals(plain, controlFailure(entry(JournalPhase.FAILED, JournalRejectionCode.FORBIDDEN)))
    }

    @Test fun otherPowerFailuresKeepTheGenericWording() {
        JournalRejectionCode.entries.filter { it != JournalRejectionCode.FORBIDDEN }.forEach {
            assertEquals(it.name, "The host rejected the request", controlFailure(entry(JournalPhase.FAILED, it)))
        }
        assertEquals("The host rejected the request", controlFailure(entry(JournalPhase.FAILED, null)))
    }

    @Test fun aForbiddenDisplayRequestIsTheSamePlainRefusal() {
        assertEquals(plain, failure(JournalRejectionCode.FORBIDDEN))
    }

    @Test fun everyOtherDisplayFailureIsNotTheRefusal() {
        (JournalRejectionCode.entries.filter { it != JournalRejectionCode.FORBIDDEN } + listOf(null)).forEach {
            assertNotEquals(it?.name ?: "none", plain, failure(it))
        }
        assertEquals("Host did not confirm the display request", failure(null))
    }

    @Test fun neitherRefusalSaysTheCredentialWasRemovedOrRevoked() {
        listOf(controlFailure(entry(JournalPhase.FAILED, JournalRejectionCode.FORBIDDEN)),
            failure(JournalRejectionCode.FORBIDDEN)).forEach { said ->
            listOf("revok", "remov", "unpair", "invalid", "forget").forEach { word ->
                assertEquals("$said mentions $word", false, word in said.lowercase())
            }
        }
    }
}
