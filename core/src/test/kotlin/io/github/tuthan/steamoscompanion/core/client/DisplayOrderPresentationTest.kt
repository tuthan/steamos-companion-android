package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayOrderPresentationTest {
    @Test fun savedOrderResolvesNamesAndConnectionFromHostKeys() {
        val order = fixture("saved-order")
        assertTrue(DisplayOrderPresentation.offered(order.snapshot))
        val view = DisplayOrderPresentation.project(order.snapshot)
        assertEquals(listOf("DELL U2723QE", "LG TV SSCR2", "HDMI-A-2", "eDP-1"), view.current.map { it.label })
        assertEquals(listOf(true, true, false, true), view.current.map { it.connected })
        assertEquals(listOf("LG TV SSCR2", "DELL U2723QE"), view.saved.map { it.label })
        assertTrue(view.restartRequired)
    }

    @Test fun unsupportedHostDoesNotOfferTheTab() {
        assertFalse(DisplayOrderPresentation.offered(fixture("unsupported-host").snapshot))
        assertFalse(DisplayOrderPresentation.offered(null))
    }

    private fun fixture(name: String): VersionedPayload.DisplayOrder {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/display-order-get/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        val decoded = ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_ORDER, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return (decoded as ApiReply.Versioned).payload as VersionedPayload.DisplayOrder
    }
}
