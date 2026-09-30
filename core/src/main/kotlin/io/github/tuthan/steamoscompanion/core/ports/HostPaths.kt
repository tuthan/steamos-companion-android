package io.github.tuthan.steamoscompanion.core.ports

import io.github.tuthan.steamoscompanion.core.protocol.HostEndpoint
import java.net.Inet4Address

/** Chooses which networks to try for the paired host, using where that host actually is. */
object HostPaths {
    private val IPV4 = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")

    /**
     * For Wake, a broadcast only helps on the host's own LAN, and a tunnel reaches it only through a relay. So try
     * the network whose subnet holds the host's address, then any tunnel. When neither applies (for
     * example the host is addressed by name) keep the previous behaviour: every network, preferring
     * the one named like the host's recorded interface.
     */
    fun candidates(paths: List<LanPath>, endpoint: String, preferredInterface: String?): List<LanPath> {
        val hostAddress = hostIpv4(endpoint)
        val lan = if (hostAddress == null) emptyList() else paths.filter { path ->
            !path.tunnel && path.ipv4Subnets.any { contains(it.address as? Inet4Address, it.prefixLength, hostAddress) }
        }
        val tunnels = paths.filter { it.tunnel }
        if (lan.isNotEmpty() || tunnels.isNotEmpty()) return lan + tunnels
        return paths.sortedBy { it.interfaceName != preferredInterface }
    }

    private fun hostIpv4(endpoint: String): Inet4Address? = try {
        HostEndpoint.parse(endpoint).host.takeIf { IPV4.matches(it) }
            ?.let { java.net.InetAddress.getByName(it) as? Inet4Address }
    } catch (_: Exception) { null }

    private fun contains(network: Inet4Address?, prefix: Int, address: Inet4Address): Boolean {
        if (network == null || prefix !in 0..32) return false
        val mask = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
        return (value(network) and mask) == (value(address) and mask)
    }

    private fun value(address: Inet4Address): Long =
        address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xffL) }
}
