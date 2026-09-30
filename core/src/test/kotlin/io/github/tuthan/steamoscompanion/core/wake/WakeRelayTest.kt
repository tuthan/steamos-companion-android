package io.github.tuthan.steamoscompanion.core.wake

import io.github.tuthan.steamoscompanion.core.ports.LanPath
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeRelayTest {
    @Test fun parsesIpv4LiteralWithDefaultAndExplicitPort() {
        assertEquals(WakeRelay(InetAddress.getByName("10.8.0.1"), 9), WakeRelay.parse(" 10.8.0.1 "))
        assertEquals(WakeRelay(InetAddress.getByName("10.8.0.1"), 9009), WakeRelay.parse("10.8.0.1:9009"))
    }

    @Test fun rejectsHostnamesBadOctetsBadPortsAndUnusableAddresses() {
        listOf("relay.example", "10.8.0", "10.8.0.256", "10.8.0.1:0", "10.8.0.1:65536", "10.8.0.1:", "",
            "0.0.0.0", "127.0.0.1", "224.0.0.1", "10.8.0.1 x", "::1").forEach { assertNull(it, WakeRelay.parse(it)) }
    }

    @Test fun formatRoundTripsAndOmitsDefaultPort() {
        assertEquals("10.8.0.1", WakeRelay.parse("10.8.0.1:9")!!.format())
        assertEquals("10.8.0.1:9009", WakeRelay.parse("10.8.0.1:9009")!!.format())
        assertEquals(WakeRelay.parse("10.8.0.1:9009"), WakeRelay.parse(WakeRelay.parse("10.8.0.1:9009")!!.format()))
    }

    private fun path(id: String, tunnel: Boolean, vararg gateways: String) = LanPath(
        id, id, listOf(InetAddress.getByName("192.168.1.20")), emptyList(), tunnel = tunnel,
        gateways = gateways.map(InetAddress::getByName),
    )

    @Test fun suggestsTheLanGatewayAndNeverATunnelOrIpv6Gateway() {
        assertEquals(WakeRelay(InetAddress.getByName("192.168.1.1")),
            WakeRelay.suggestionFrom(listOf(path("wg", true, "10.8.0.1"), path("wlan0", false, "fe80::1", "192.168.1.1"))))
        assertNull(WakeRelay.suggestionFrom(listOf(path("wg", true, "10.8.0.1"))))
        assertNull(WakeRelay.suggestionFrom(listOf(path("wlan0", false))))
        assertNull(WakeRelay.suggestionFrom(listOf(path("wlan0", false, "0.0.0.0"))))
        assertNull(WakeRelay.suggestionFrom(emptyList()))
    }
}
