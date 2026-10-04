package io.github.tuthan.steamoscompanion.core.client

import io.github.tuthan.steamoscompanion.core.protocol.ApiReply
import io.github.tuthan.steamoscompanion.core.protocol.ApiRoute
import io.github.tuthan.steamoscompanion.core.protocol.BoundedJson
import io.github.tuthan.steamoscompanion.core.protocol.DisplayModeSummary
import io.github.tuthan.steamoscompanion.core.protocol.HttpResponse
import io.github.tuthan.steamoscompanion.core.protocol.LinkConnector
import io.github.tuthan.steamoscompanion.core.protocol.LinkFacts
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolCodec
import io.github.tuthan.steamoscompanion.core.protocol.ProtocolFixtureAssertions
import io.github.tuthan.steamoscompanion.core.protocol.VersionedPayload
import io.github.tuthan.steamoscompanion.core.protocol.asObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The advisory rules against the REAL host fixtures: when it warns, and every case in which it stays silent. */
class LinkAdvisoryTest {
    private fun mode(width: Long, height: Long, refresh: String? = "60", id: String = "m$width-$height-$refresh") =
        DisplayModeSummary(id, width, height, refresh)

    // --- the rule, on available.json (output "1" is HDMI-A-1: 3840x2160, 1920x1080) --------------------------

    @Test fun anUnlistedResolutionOnTheJoinedOutputWarns() {
        val facts = facts("available")
        val notice = checkNotNull(LinkAdvisory.evaluate(facts, "1", mode(3440, 1440)))
        assertEquals("3440x1440", notice.resolution)
        assertEquals("HDMI-A-1", notice.connector.connector)
        assertEquals("LG TV SSCR2", notice.connector.displayName)
    }

    @Test fun listedResolutionsDoNotWarn() {
        val facts = facts("available")
        assertNull(LinkAdvisory.evaluate(facts, "1", mode(3840, 2160)))
        assertNull(LinkAdvisory.evaluate(facts, "1", mode(1920, 1080)))
    }

    @Test fun refreshRateNeverChangesTheAnswer() {
        val facts = facts("available")
        listOf("24", "59.94", "60", "144", "240.000", null, "not-a-number").forEach { hz ->
            assertNull("listed at $hz Hz", LinkAdvisory.evaluate(facts, "1", mode(3840, 2160, hz)))
            assertNull("listed at $hz Hz", LinkAdvisory.evaluate(facts, "1", mode(1920, 1080, hz)))
            assertEquals("unlisted at $hz Hz", "3440x1440", LinkAdvisory.evaluate(facts, "1", mode(3440, 1440, hz))?.resolution)
        }
    }

    @Test fun theOutputsCurrentModeNeverWarnsEvenWhenItsResolutionIsNotListed() {
        val facts = facts("available")
        // Steam's own current mode on this output is 3440x1440, which the link does not list.
        assertNull(LinkAdvisory.evaluate(facts, "1", mode(3440, 1440, id = "3352"), currentModeId = "3352"))
        // Another mode of that resolution is a choice the user made, so it does.
        assertNotNull(LinkAdvisory.evaluate(facts, "1", mode(3440, 1440, "100", id = "9"), currentModeId = "3352"))
    }

    @Test fun theVendoredDisplayOutputsFixtureJoinsTheDrmFixture() {
        val inventory = outputs("available")
        val output = inventory.outputs.single()
        assertEquals("1", output.id)
        val facts = facts("available")
        assertEquals("HDMI-A-1", LinkAdvisory.connectorFor(facts, output.id)?.connector)
        // Selecting each listed mode of that real output, with its real current mode: only the host-listed ones are quiet.
        val verdicts = output.modes.associate { it.id to LinkAdvisory.evaluate(facts, output.id, it, output.currentModeId)?.resolution }
        assertEquals(mapOf("3352" to null, "2" to null, "0" to null), verdicts)
    }

    // --- the host's join, never a name -----------------------------------------------------------------------

    @Test fun anUnjoinedOutputNeverWarns() {
        val facts = facts("available")
        listOf("2", "0", "3", "99", "").forEach { assertNull("output '$it'", LinkAdvisory.evaluate(facts, it, mode(3440, 1440))) }
        assertNull(LinkAdvisory.evaluate(facts, null, mode(3440, 1440)))
    }

    @Test fun aConnectorOrDisplayNameIsNeverUsedAsAnOutputId() {
        val facts = facts("available")
        listOf("HDMI-A-1", "DP-1", "eDP-1", "LG TV SSCR2", "drm:card0", "card0").forEach {
            assertNull("matched by name '$it'", LinkAdvisory.connectorFor(facts, it))
            assertNull("matched by name '$it'", LinkAdvisory.evaluate(facts, it, mode(7680, 4320)))
        }
    }

    @Test fun theAmbiguousConnectorsStayQuietButTheUnambiguousOneStillWarns() {
        val facts = facts("ambiguous-connector")
        val dp = facts.connectors.filter { it.connector == "DP-1" }
        assertEquals(2, dp.size)
        dp.forEach { assertNull(it.steamOutputId) }
        // No output id reaches either DP-1, whatever resolution is chosen.
        listOf("2", "3", "DP-1", "0").forEach {
            assertNull("output '$it'", LinkAdvisory.evaluate(facts, it, mode(7680, 4320)))
        }
        assertEquals("HDMI-A-1", LinkAdvisory.connectorFor(facts, "1")?.connector)
        assertEquals("3440x1440", LinkAdvisory.evaluate(facts, "1", mode(3440, 1440))?.resolution)
        assertNull(LinkAdvisory.evaluate(facts, "1", mode(1920, 1080)))
    }

    @Test fun anUnreportingBridgeJoinsNothing() {
        val facts = facts("bridge-unavailable")
        listOf("1", "2", "0").forEach { assertNull("output '$it'", LinkAdvisory.evaluate(facts, it, mode(7680, 4320))) }
    }

    @Test fun anUnavailableHostSaysNothing() {
        val facts = facts("no-connectors")
        listOf("1", "2").forEach { assertNull(LinkAdvisory.evaluate(facts, it, mode(3440, 1440))) }
        // Even a hand-built unavailable block that still lists a joined connector is not used.
        val unavailableWithConnector = facts("available").copy(available = false)
        assertNull(LinkAdvisory.evaluate(unavailableWithConnector, "1", mode(3440, 1440)))
    }

    @Test fun noFactsOrNoModeSaysNothing() {
        assertNull(LinkAdvisory.evaluate(null, "1", mode(3440, 1440)))
        assertNull(LinkAdvisory.evaluate(facts("available"), "1", null))
        assertNull(LinkAdvisory.connectorFor(null, "1"))
    }

    @Test fun twoConnectorsJoinedToOneOutputIsAmbiguousSoNothingIsSaid() {
        val base = facts("available")
        val hdmi = base.connectors.first { it.connector == "HDMI-A-1" }
        val twin = hdmi.copy(connector = "DP-2", steamOutputId = "1")
        val facts = base.copy(connectors = base.connectors + twin)
        assertNull(LinkAdvisory.connectorFor(facts, "1"))
        assertNull(LinkAdvisory.evaluate(facts, "1", mode(3440, 1440)))
    }

    // --- when the joined connector says nothing useful -------------------------------------------------------

    @Test fun anInternalPanelNeverWarns() {
        val base = facts("available")
        val panel = base.connectors.first { it.connector == "eDP-1" }
        assertEquals(true, panel.isInternal)
        val joined = base.withConnector("eDP-1") { it.copy(steamOutputId = "9") }
        // Steam exposes scaled modes the connector list does not, so the panel is skipped...
        assertNull(LinkAdvisory.evaluate(joined, "9", mode(1920, 1080)))
        assertNull(LinkAdvisory.evaluate(joined, "9", mode(1280, 720)))
        // ...and only because it is internal: the same connector as an external display would warn.
        val external = joined.withConnector("eDP-1") { it.copy(isInternal = false) }
        assertEquals("1920x1080", LinkAdvisory.evaluate(external, "9", mode(1920, 1080))?.resolution)
        assertNull(LinkAdvisory.evaluate(external, "9", mode(1280, 800)))
    }

    @Test fun aDisconnectedConnectorNeverWarns() {
        val base = facts("available")
        val joined = base.withConnector("HDMI-A-2") { it.copy(steamOutputId = "5", resolutions = listOf("1920x1080")) }
        assertNull(LinkAdvisory.evaluate(joined, "5", mode(3440, 1440)))
        val connected = joined.withConnector("HDMI-A-2") { it.copy(connected = true) }
        assertNotNull(LinkAdvisory.evaluate(connected, "5", mode(3440, 1440)))
    }

    @Test fun anEmptyResolutionListNeverWarns() {
        val base = facts("available")
        val empty = base.withConnector("HDMI-A-1") { it.copy(resolutions = emptyList()) }
        assertNull(LinkAdvisory.evaluate(empty, "1", mode(3440, 1440)))
        assertNotNull(LinkAdvisory.evaluate(base, "1", mode(3440, 1440)))
    }

    // --- the link line ---------------------------------------------------------------------------------------

    @Test fun theLineNamesConnectorCardDisplayAndIdentity() {
        val base = facts("available")
        assertEquals(LinkLine("HDMI-A-1", "card0", "LG TV SSCR2", "GSM", 49780), LinkAdvisory.line(base.connectors.first { it.connector == "HDMI-A-1" }))
        assertEquals(LinkLine("DP-1", "card0", "DELL U2723QE", "DEL", 41340), LinkAdvisory.line(base.connectors.first { it.connector == "DP-1" && it.gpuId == "drm:card0" }))
    }

    @Test fun theLineOmitsADisplayNameThatOnlyRepeatsTheConnectorAndAnIncompleteIdentity() {
        val ambiguous = facts("ambiguous-connector")
        val second = ambiguous.connectors.first { it.connector == "DP-1" && it.gpuId == "drm:card1" }
        assertEquals(LinkLine("DP-1", "card1", null, null, null), LinkAdvisory.line(second))
        val connector = facts("available").connectors.first { it.connector == "HDMI-A-1" }
        assertEquals(LinkLine("HDMI-A-1", "card0", "LG TV SSCR2", null, null), LinkAdvisory.line(connector.copy(monitorProductId = null)))
        assertEquals(LinkLine("HDMI-A-1", "card0", "LG TV SSCR2", null, null), LinkAdvisory.line(connector.copy(monitorVendor = null)))
        assertEquals(LinkLine("HDMI-A-1", "card0", null, "GSM", 49780), LinkAdvisory.line(connector.copy(displayName = "HDMI-A-1")))
    }

    // --- topology key ----------------------------------------------------------------------------------------

    @Test fun theTopologyKeyChangesOnlyWhenOutputsOrGenerationsDo() {
        val inventory = outputs("available")
        val key = checkNotNull(LinkFactsTopology.key(inventory))
        assertEquals("a status poll builds a fresh but equal inventory", key, LinkFactsTopology.key(outputs("available")))
        assertEquals("a preview that changes nothing about the outputs keeps the key", key, LinkFactsTopology.key(outputs("with-active-preview")))
        assertNotEquals(key, LinkFactsTopology.key(inventory.copy(generation = 5)))
        val output = inventory.outputs.single()
        assertNotEquals(key, LinkFactsTopology.key(inventory.copy(outputs = listOf(output.copy(generation = 5)))))
        assertNotEquals(key, LinkFactsTopology.key(inventory.copy(outputs = listOf(output.copy(id = "7")))))
        assertNotEquals(key, LinkFactsTopology.key(inventory.copy(outputs = listOf(output, output.copy(id = "2")))))
    }

    @Test fun thereIsNoTopologyWithoutAnAvailableCurrentInventory() {
        assertNull(LinkFactsTopology.key(null))
        assertNull(LinkFactsTopology.key(outputs("available").copy(available = false)))
        assertNull("a legacy host cannot have the route", LinkFactsTopology.key(outputs("available").copy(legacyShape = true)))
    }

    // --- facts stay out of the status read ---------------------------------------------------------------------

    @Test fun theStatusReadCarriesNoLinkFacts() {
        val holders = listOf(PairedStatusRead::class.java, VersionedPayload.Status::class.java, VersionedPayload.DisplayOutputs::class.java)
        holders.forEach { type ->
            val offending = type.declaredFields.filter { "Link" in it.genericType.typeName }
            assertEquals("${type.simpleName} must not carry link facts", emptyList<Any>(), offending)
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun LinkFacts.withConnector(name: String, edit: (LinkConnector) -> LinkConnector) =
        copy(connectors = connectors.map { if (it.connector == name) edit(it) else it })

    private fun facts(name: String): LinkFacts {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/drm/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        val reply = ProtocolCodec.decodeResponse(ApiRoute.DRM, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return checkNotNull(((reply as ApiReply.Versioned).payload as VersionedPayload.Drm).facts)
    }

    private fun outputs(name: String): VersionedPayload.DisplayOutputs {
        val document = BoundedJson.parseObject(ProtocolFixtureAssertions.readResource("fixtures/contract/display-outputs/$name.json"))
        val body = document["response"].asObject("response")["body"].asObject("body")
        val reply = ProtocolCodec.decodeResponse(ApiRoute.DISPLAY_OUTPUTS, HttpResponse(200, emptyMap(), BoundedJson.encode(body)))
        return (reply as ApiReply.Versioned).payload as VersionedPayload.DisplayOutputs
    }
}
