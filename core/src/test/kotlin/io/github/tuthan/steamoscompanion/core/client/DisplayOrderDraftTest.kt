package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayOrderOutput
import io.github.tuthan.steamoscompanion.core.protocol.DisplayOrderSummary
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayOrderDraftTest {
    private val dp = "drm:card0:DP-1"
    private val hdmi = "drm:card0:HDMI-A-1"
    private val edp = "drm:card0:eDP-1"

    @Test fun draftStartsWithConnectedSavedKeysFirstThenInventoryOrder() {
        assertEquals(listOf(dp, hdmi, edp), DisplayOrderDraft.from(order("available")).keys)
        // Saved: HDMI-A-1 then DP-1; HDMI-A-2 (disconnected) never becomes editable.
        assertEquals(listOf(hdmi, dp, edp), DisplayOrderDraft.from(order("saved-order")).keys)
        assertEquals(listOf(dp, hdmi, edp), DisplayOrderDraft.from(order("saved-output-disconnected")).keys)
    }

    @Test fun movingIsBoundedAndMakesTheDraftDirtyOnlyWhenTheOrderChanges() {
        val draft = DisplayOrderDraft.from(order("available"))
        assertFalse(draft.canMove(dp, -1))
        assertFalse(draft.canMove(edp, 1))
        val moved = draft.move(hdmi, -1)
        assertEquals(listOf(hdmi, dp, edp), moved.keys)
        assertTrue(moved.dirty)
        assertFalse(moved.move(hdmi, 1).dirty)
        assertEquals(draft, draft.move("unknown", 1))
    }

    @Test fun backgroundReadsNeverReorderADirtyDraftButAdoptHostChangesWhenClean() {
        val available = order("available")
        val moved = DisplayOrderDraft.from(available).move(hdmi, -1)
        assertEquals(moved, moved.observed(available))
        // A clean draft follows the host's saved order.
        val clean = DisplayOrderDraft.from(available).observed(order("available")).let { it.observed(available) }
        assertEquals(listOf(dp, hdmi, edp), clean.keys)
        assertFalse(clean.stale)
    }

    @Test fun aTopologyChangeMarksTheDraftStaleAndOnlyRefreshAcceptsIt() {
        val available = order("available")
        val moved = DisplayOrderDraft.from(available).move(hdmi, -1)
        val changed = available.copy(generation = 2)
        val stale = moved.observed(changed)
        assertTrue(stale.stale)
        assertEquals(moved.keys, stale.keys)
        assertFalse(stale.canMove(dp, 1))
        val refreshed = stale.refreshed(changed)
        assertFalse(refreshed.stale)
        assertFalse(refreshed.dirty)

        val unplugged = available.copy(outputs = available.outputs.map { if (it.key == edp) it.copy(connected = false) else it })
        assertTrue(moved.observed(unplugged).stale)
    }

    @Test fun aSavedOrderHoldsTheDraftUntilTheHostReportsIt() {
        val available = order("available")
        val submitted = listOf(hdmi, dp, edp)
        val saved = DisplayOrderDraft.from(available).move(hdmi, -1).saved(submitted)
        assertFalse(saved.dirty)
        assertFalse(saved.canMove(dp, 1))
        // Readback still shows the old order: keep waiting and keep the submitted keys.
        assertEquals(saved, saved.observed(available))
        val reported = available.copy(savedOutputKeys = listOf(hdmi, dp))
        val done = saved.observed(reported)
        assertEquals(null, done.pendingKeys)
        assertEquals(submitted, done.keys)
        assertTrue(done.canMove(dp, 1))
    }

    @Test fun hostUnsupportedAmbiguousOrStaleMakesTheDraftStale() {
        assertTrue(DisplayOrderDraft.from(order("unsupported-host")).stale)
        assertTrue(DisplayOrderDraft.from(order("ambiguous-connector")).stale)
        val available = order("available")
        assertTrue(DisplayOrderDraft.from(available).observed(available.copy(stale = true)).stale)
    }

    @Test fun editableRequiresAnAvailableUnambiguousReading() {
        assertTrue(DisplayOrderPresentation.editable(order("available")))
        assertFalse(DisplayOrderPresentation.editable(order("ambiguous-connector")))
        assertFalse(DisplayOrderPresentation.editable(order("unsupported-host")))
        assertFalse(DisplayOrderPresentation.editable(order("no-physical-inventory")))
    }

    private fun order(name: String): DisplayOrderSummary {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/display-order-get/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        val decoded = ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_ORDER, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return ((decoded as ApiReply.Versioned).payload as VersionedPayload.DisplayOrder).snapshot
    }
}
