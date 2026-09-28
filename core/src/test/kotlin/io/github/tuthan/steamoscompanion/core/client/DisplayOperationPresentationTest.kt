package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRequest
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpMethod
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.JsonValue
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import io.github.tuthan.steamoscompanion.core.protocol.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayOperationPresentationTest {
    private val host = testActiveHost()

    @Test fun previewOperationAndCurrentHostObservationStaySeparateAfterRepositoryReopen() {
        val store = MemoryStateStore()
        val cipher = JvmAesGcmStateCipher()
        val repository = EncryptedStateRepository(store, cipher)
        val preview = requireNotNull(outputs("with-active-preview").preview)
        val entry = entry(1, ApiRoute.DISPLAY_PREVIEW,
            jsonObject("request_id" to string("card-preview"), "output_id" to string("1"),
                "mode_id" to string("2"), "generation" to number(4)))
            .copy(phase = JournalPhase.ACCEPTED, operationId = preview.id)
        assertTrue(repository.transact { it.copy(activeHost = host, journal = listOf(entry)) } is StateCommitResult.Saved)
        val reopened = EncryptedStateRepository(store, cipher)
        val state = (reopened.load() as StateLoadResult.Ready).state
        val current = observation(outputs("with-active-preview"))

        val card = DisplayOperationPresentation.latest(host, state, current, NOW)!!
        assertEquals(DisplayOperationKind.PREVIEW, card.kind)
        assertEquals(JournalPhase.ACCEPTED, card.phase)
        assertEquals(DisplayObservation.PREVIEW_ACTIVE, card.observed)
        assertEquals(DisplayObservation.CONNECTION_LOST,
            DisplayOperationPresentation.latest(host, state, current.copy(unreachable = true), NOW)!!.observed)
        assertEquals(DisplayObservation.NOT_CHECKED,
            DisplayOperationPresentation.latest(host, state, current.copy(stale = true), NOW)!!.observed)
        assertEquals(DisplayObservation.NOT_CHECKED,
            DisplayOperationPresentation.latest(host, state, current.copy(
                source = StatusObservationSource.from(host.copy(bearerToken = "rotated-token-xxxxxxxxxxxxxxxxxxxxxxxx"))), NOW)!!.observed)

        val noPreview = observation(outputs("available"))
        assertEquals(DisplayObservation.MODE_DIFFERS_FROM_REQUEST,
            DisplayOperationPresentation.latest(host, state, noPreview, NOW)!!.observed)
        val matching = noPreview.copy(lastRead = noPreview.lastRead!!.copy(
            outputs = outputs("available").copy(outputs = outputs("available").outputs.map {
                it.copy(currentModeId = "2")
            })))
        assertEquals(DisplayObservation.MODE_MATCHES_REQUEST,
            DisplayOperationPresentation.latest(host, state, matching, NOW)!!.observed)
        assertEquals(JournalPhase.ACCEPTED,
            DisplayOperationPresentation.latest(host, state, matching, NOW)!!.phase)
    }

    @Test fun keepPreviewAbsenceDoesNotBecomeAConfirmedRestore() {
        val previewId = requireNotNull(outputs("with-active-preview").preview).id
        val entry = entry(1, ApiRoute.DISPLAY_CONFIRM,
            jsonObject("request_id" to string("card-keep"), "preview_id" to string(previewId),
                "visible" to JsonValue.BooleanValue(true)))
            .copy(phase = JournalPhase.UNKNOWN)
        val state = PersistentState(activeHost = host, journal = listOf(entry))
        val card = DisplayOperationPresentation.latest(host, state, observation(outputs("available")), NOW)!!
        assertEquals(DisplayOperationKind.KEEP, card.kind)
        assertEquals(JournalPhase.UNKNOWN, card.phase)
        assertEquals(DisplayObservation.PREVIEW_ABSENT, card.observed)
        assertEquals(DisplayObservation.PREVIEW_ACTIVE,
            DisplayOperationPresentation.latest(host, state, observation(outputs("with-active-preview")), NOW)!!.observed)
    }

    @Test fun savedRestoreShowsProfileReadbackAndLegacyIdentityAmbiguity() {
        val profile = outputs("with-profile").profiles.single()
        val entry = entry(1, ApiRoute.DISPLAY_RESTORE,
            jsonObject("request_id" to string("card-restore"), "source" to string("verified"),
                "profile_id" to string(profile.id)))
            .copy(phase = JournalPhase.FAILED, rejectionCode = JournalRejectionCode.PROFILE_NOT_FOUND)
        val state = PersistentState(activeHost = host, journal = listOf(entry))
        val card = DisplayOperationPresentation.latest(host, state, observation(outputs("with-profile")), NOW)!!
        assertEquals(DisplayOperationKind.RESTORE_SAVED, card.kind)
        assertEquals(JournalPhase.FAILED, card.phase)
        assertEquals(JournalRejectionCode.PROFILE_NOT_FOUND, card.failure)
        assertEquals(DisplayObservation.MODE_MATCHES_REQUEST, card.observed)
        assertEquals(DisplayObservation.PROFILE_NOT_LISTED,
            DisplayOperationPresentation.latest(host, state, observation(outputs("available")), NOW)!!.observed)

        val legacy = entry.copy(phase = JournalPhase.UNKNOWN, rejectionCode = null,
            originalPairedAtMillis = null, originalBearerSha256 = null)
        val unproven = DisplayOperationPresentation.latest(host,
            state.copy(journal = listOf(legacy)), observation(outputs("with-profile")), NOW)!!
        assertEquals(DisplayObservation.UNPROVEN_PAIRING, unproven.observed)
        assertEquals(false, unproven.pairingProven)
        assertNull(DisplayOperationPresentation.latest(host.copy(bearerToken = "different-token"), state,
            observation(outputs("with-profile")), NOW))
    }

    @Test fun saveCurrentObservationDoesNotUseAReenumeratedOutput() {
        val entry = entry(1, ApiRoute.DISPLAY_SAVE_CURRENT,
            jsonObject("request_id" to string("card-save-current"), "output_id" to string("1"),
                "generation" to number(4), "visible" to JsonValue.BooleanValue(true)))
            .copy(phase = JournalPhase.UNKNOWN)
        val state = PersistentState(activeHost = host, journal = listOf(entry))
        val inventory = outputs("available")
        assertEquals(DisplayObservation.DISPLAY_REACHABLE,
            DisplayOperationPresentation.latest(host, state, observation(inventory), NOW)!!.observed)
        val changed = inventory.copy(outputs = inventory.outputs.map { it.copy(generation = 5) })
        val card = DisplayOperationPresentation.latest(host, state, observation(changed), NOW)!!
        assertEquals(DisplayOperationKind.SAVE_CURRENT, card.kind)
        assertEquals(JournalPhase.UNKNOWN, card.phase)
        assertEquals(DisplayObservation.OUTPUT_REENUMERATED, card.observed)
    }

    private fun entry(localGeneration: Long, route: ApiRoute, body: JsonValue.ObjectValue): JournalEntry {
        val request = ApiRequest(HttpMethod.POST, route.path, body, host.bearerToken)
        return JournalEntry.create(localGeneration, host, request, BoundedJson.encode(body), 100)
    }

    private fun observation(inventory: VersionedPayload.DisplayOutputs): StatusPollState = StatusPollState(
        lastRead = PairedStatusRead(status(), inventory, null),
        lastSuccessMonotonicNanos = 1_000_000_000L,
        source = StatusObservationSource.from(host),
    )

    private fun status() = fixture(ApiRoute.STATUS, "status/sunshine-stopped") as VersionedPayload.Status
    private fun outputs(name: String) = fixture(ApiRoute.DISPLAY_OUTPUTS,
        "display-outputs/$name") as VersionedPayload.DisplayOutputs

    private fun fixture(route: ApiRoute, name: String): VersionedPayload {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        return (ProtocolCodec.decodeResponse(route, HttpResponse(200, emptyMap(), BoundedJson.encode(body))) as ApiReply.Versioned).payload
    }

    private fun string(value: String) = JsonValue.StringValue(value)
    private fun number(value: Long) = JsonValue.NumberValue(value.toString())
    private companion object { const val NOW = 2_000_000_000L }
}
