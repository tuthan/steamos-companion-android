package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.DisplayRecoveryProfile
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayInventoryTest {
    @Test fun retainsModesAndProfilesAndOrdersCurrentFirst() {
        val inventory = fixture("with-profile")
        val output = inventory.outputs.single()
        assertEquals(3, output.modes.size)
        assertEquals(false, output.isInternal)
        assertEquals(listOf("3352", "2", "0"), DisplayInventory.orderedModes(output).map { it.id })
        assertEquals("3352", inventory.profiles.single().mode.id)
        assertEquals("1", inventory.profiles.single().outputId)
        assertEquals(DisplaySelection("1", 4, "3352"), DisplayInventory.select(inventory, null))
    }

    @Test fun previewChangesDoNotChangeInventoryOrSilentlyMoveSelection() {
        val available = fixture("available")
        val withPreview = fixture("with-active-preview")
        assertTrue(DisplayInventory.sameInventory(available, withPreview))
        assertNotNull(withPreview.preview)
        assertEquals("op-fixture0001xxxxxxxxxxxxx", withPreview.preview!!.id)
        assertEquals("2", withPreview.preview!!.targetMode.id)
        assertNull(DisplayInventory.select(withPreview, DisplaySelection("missing-output", 4, "2")))
    }

    @Test fun preservesValidSelectionByIdentityAndGeneration() {
        val inventory = fixture("available")
        assertEquals(DisplaySelection("1", 4, "2"), DisplayInventory.select(inventory, DisplaySelection("1", 4, "2")))
        assertEquals(DisplaySelection("1", 4, "3352"), DisplayInventory.select(inventory, DisplaySelection("1", 3, "2")))
        assertFalse(DisplayInventory.sameInventory(inventory, inventory.copy(generation = 5)))
    }

    @Test fun distinctProfilesKeepNewestPerOutputAndResolutionNewestFirst() {
        fun profile(id: String, output: String, width: Long, hz: String?) =
            DisplayRecoveryProfile(id, output, DisplayModeSummary("m$id", width, width * 9 / 16, hz))
        val host = listOf(
            profile("a", "1", 1920, "60"), profile("b", "1", 1280, "60"), profile("c", "1", 1920, "60"),
            profile("d", "2", 1920, "60"), profile("e", "1", 1920, "144"), profile("f", "1", 1920, "60"),
        )
        assertEquals(listOf("f", "e", "d", "b"), DisplayInventory.distinctProfiles(host).map { it.id })
        assertEquals(emptyList<DisplayRecoveryProfile>(), DisplayInventory.distinctProfiles(emptyList()))
    }

    private fun fixture(name: String): VersionedPayload.DisplayOutputs {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/display-outputs/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        val decoded = ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_OUTPUTS, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return ((decoded as ApiReply.Versioned).payload as VersionedPayload.DisplayOutputs)
    }
}
