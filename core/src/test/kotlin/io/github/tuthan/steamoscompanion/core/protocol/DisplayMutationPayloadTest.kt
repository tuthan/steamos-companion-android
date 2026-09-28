package io.github.tuthan.steamoscompanion.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DisplayMutationPayloadTest {
    @Test fun previewReplyExposesTypedHostPreviewSeparatelyFromOperation() {
        val reply = decode(ApiRoute.DISPLAY_PREVIEW, "display-preview/accepted")
        assertEquals("display.preview", reply.operation?.kind)
        assertEquals(reply.operation?.id, reply.preview?.id)
        assertEquals("1", reply.preview?.outputId)
        assertEquals("3352", reply.preview?.baselineMode?.id)
        assertEquals("2", reply.preview?.targetMode?.id)
        assertEquals("1789300815.25", reply.preview?.deadlineSeconds)
        assertNull(reply.profile)
    }

    @Test fun saveAndKeepRepliesExposeTypedVerifiedProfileSeparatelyFromOperation() {
        val saved = decode(ApiRoute.DISPLAY_SAVE_CURRENT, "display-save-current/saved")
        assertEquals("display.save-current", saved.operation?.kind)
        assertEquals("profile-fixture0001xxxxxxxxxxxxx", saved.profile?.id)
        assertEquals("1", saved.profile?.outputId)
        assertEquals("3352", saved.profile?.mode?.id)
        assertNull(saved.preview)

        val confirmed = decode(ApiRoute.DISPLAY_CONFIRM, "display-confirm/confirmed")
        assertEquals("display.confirm", confirmed.operation?.kind)
        assertEquals("profile-fixture0001xxxxxxxxxxxxx", confirmed.profile?.id)
        assertEquals("2", confirmed.profile?.mode?.id)
    }

    @Test fun malformedOptionalDisplayFactsAreRejectedInsteadOfLeftAsRawJson() {
        for ((route, fixture, field) in listOf(
            Triple(ApiRoute.DISPLAY_PREVIEW, "display-preview/accepted", "preview"),
            Triple(ApiRoute.DISPLAY_CONFIRM, "display-confirm/confirmed", "profile"),
            Triple(ApiRoute.DISPLAY_SAVE_CURRENT, "display-save-current/saved", "profile"),
        )) {
            val body = fixtureBody(fixture)
            val malformed = JsonValue.ObjectValue(body.fields + (field to JsonValue.StringValue("invalid")))
            assertThrows(JsonFormatException::class.java) {
                ProtocolCodec.decodeResponse(route, HttpResponse(202, emptyMap(), BoundedJson.encode(malformed)))
            }
        }
    }

    @Test fun typedDisplayFactsMustMatchTheReturnedOperationIdentity() {
        val previewBody = fixtureBody("display-preview/accepted")
        val preview = previewBody["preview"].asObject("preview")
        val wrongPreview = JsonValue.ObjectValue(preview.fields +
            ("preview_id" to JsonValue.StringValue("another-operation")))
        assertThrows(JsonFormatException::class.java) {
            ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_PREVIEW,
                HttpResponse(202, emptyMap(), BoundedJson.encode(
                    JsonValue.ObjectValue(previewBody.fields + ("preview" to wrongPreview)))))
        }
        val previewOperation = previewBody["operation"].asObject("operation")
        val wrongDeclaredId = JsonValue.ObjectValue(previewOperation.fields +
            ("preview_id" to JsonValue.StringValue("another-operation")))
        assertThrows(JsonFormatException::class.java) {
            ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_PREVIEW,
                HttpResponse(202, emptyMap(), BoundedJson.encode(
                    JsonValue.ObjectValue(previewBody.fields + ("operation" to wrongDeclaredId)))))
        }

        val saveBody = fixtureBody("display-save-current/saved")
        val profile = saveBody["profile"].asObject("profile")
        val wrongProfile = JsonValue.ObjectValue(profile.fields +
            ("id" to JsonValue.StringValue("another-profile")))
        assertThrows(JsonFormatException::class.java) {
            ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_SAVE_CURRENT,
                HttpResponse(202, emptyMap(), BoundedJson.encode(
                    JsonValue.ObjectValue(saveBody.fields + ("profile" to wrongProfile)))))
        }
        val saveOperation = saveBody["operation"].asObject("operation")
        val target = saveOperation["target"].asObject("operation.target")
        val malformedTarget = JsonValue.ObjectValue(target.fields +
            ("profile_id" to JsonValue.NumberValue("3")))
        val alteredOperation = JsonValue.ObjectValue(saveOperation.fields + ("target" to malformedTarget))
        assertThrows(JsonFormatException::class.java) {
            ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_SAVE_CURRENT,
                HttpResponse(202, emptyMap(), BoundedJson.encode(
                    JsonValue.ObjectValue(saveBody.fields + ("operation" to alteredOperation)))))
        }
    }

    private fun decode(route: ApiRoute, fixture: String): VersionedPayload.Mutation =
        (ProtocolCodec.decodeResponse(route,
            HttpResponse(202, emptyMap(), BoundedJson.encode(fixtureBody(fixture)))) as ApiReply.Versioned)
            .payload as VersionedPayload.Mutation

    private fun fixtureBody(name: String): JsonValue.ObjectValue {
        val fixture = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/$name.json"))
        return fixture["response"].asObject("response")["body"].asObject("body")
    }
}
