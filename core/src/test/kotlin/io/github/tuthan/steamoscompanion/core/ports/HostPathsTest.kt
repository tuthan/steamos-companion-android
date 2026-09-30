package io.github.tuthan.steamoscompanion.core.ports

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class HostPathsTest {
    private fun ip(value: String) = InetAddress.getByName(value)
    private fun path(id: String, address: String, prefix: Int, tunnel: Boolean = false) = LanPath(
        id, id, listOf(ip(address)), emptyList(), listOf(Ipv4Subnet(ip(address), prefix)), tunnel,
    )

    private val home = path("wlan0", "192.168.1.20", 24)
    private val cafe = path("wlan1", "172.20.5.9", 24)
    private val wg = path("tun0", "10.8.0.2", 32, tunnel = true)

    @Test fun prefersTheNetworkHoldingTheHostThenTunnels() {
        assertEquals(listOf(home, wg), HostPaths.candidates(listOf(wg, cafe, home), "192.168.1.50:18443", null))
    }

    @Test fun awayFromHomeOnlyTheTunnelIsUsedNotTheForeignWifi() {
        assertEquals(listOf(wg), HostPaths.candidates(listOf(cafe, wg), "192.168.1.50:18443", null))
    }

    @Test fun hostNamedByHostnameKeepsTheOldBehaviourWithoutATunnel() {
        assertEquals(listOf(cafe, home), HostPaths.candidates(listOf(home, cafe), "deck.local:18443", "wlan1"))
    }

    @Test fun noPathsMeansNoCandidates() {
        assertEquals(emptyList<LanPath>(), HostPaths.candidates(emptyList(), "192.168.1.50:18443", null))
    }
}
