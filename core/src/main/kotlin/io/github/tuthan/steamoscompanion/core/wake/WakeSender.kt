package io.github.tuthan.steamoscompanion.core.wake

import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

sealed interface WakeSendResult {
    /** [viaRelay]: the packet went to a relay, which must re-broadcast it; nothing reached the LAN from here. */
    data class Sent(val destinations: List<InetAddress>, val viaRelay: Boolean = false) : WakeSendResult
    /** One or more packets may have left the phone. Only a new user action may retry. */
    data class Partial(
        val destinations: List<InetAddress>, val reason: WakeSendFailure, val detail: String? = null,
    ) : WakeSendResult
    /** [detail] is a bounded platform error such as "sendto failed: EPERM", for the Wake screen only. */
    data class Failed(val reason: WakeSendFailure, val detail: String? = null) : WakeSendResult
}

enum class WakeSendFailure { TARGET_MISSING, NETWORK_MISSING, PERMISSION, SEND_FAILED, RELAY_REQUIRED }

/** A LAN machine that re-broadcasts the magic packet it receives, reached over a tunnel path. */
data class WakeRelay(val address: InetAddress, val port: Int = DEFAULT_PORT) {
    init { require(port in 1..65535) }

    fun format(): String = if (port == DEFAULT_PORT) address.hostAddress!! else "${address.hostAddress}:$port"

    companion object {
        const val DEFAULT_PORT = 9
        private val TEXT = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?::(\d{1,5}))?""")

        /**
         * A starting point for the relay field: the default gateway of a real LAN, which is often the router
         * or VPN server that can forward wake packets. Never used to send without the user saving it.
         */
        fun suggestionFrom(paths: List<LanPath>): WakeRelay? = paths.asSequence()
            .filter { !it.tunnel }
            .flatMap { it.gateways.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
            ?.let { WakeRelay(it) }

        /** Accepts only an IPv4 literal with an optional port, so saving or waking never does DNS. */
        fun parse(text: String): WakeRelay? {
            val match = TEXT.matchEntire(text.trim()) ?: return null
            val octets = match.groupValues.subList(1, 5).map { it.toInt() }
            if (octets.any { it > 255 }) return null
            val port = match.groupValues[5].takeIf { it.isNotEmpty() }?.toInt() ?: DEFAULT_PORT
            if (port !in 1..65535) return null
            val address = InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return null
            return WakeRelay(address, port)
        }
    }
}

/** A single foreground Wake activation. It never interprets packet transmission as host readiness. */
class WakeSender(
    private val networks: NetworkAccess,
    private val sockets: SocketFactory,
) {
    /**
     * Tries [candidates] in order until one transmits. A path that cannot be used (for example the
     * platform refuses the socket on it) must not hide a path that works. An unusable tunnel without a
     * relay is reported ahead of a LAN failure because adding a relay is the actionable fix.
     */
    fun send(target: StoredWakeTarget, candidates: List<LanPath>, relay: WakeRelay?): WakeSendResult {
        var last: WakeSendResult.Failed? = null
        var relayNeeded: WakeSendResult.Failed? = null
        for (path in candidates) {
            when (val result = send(target, path.id, relay)) {
                is WakeSendResult.Sent, is WakeSendResult.Partial -> return result
                is WakeSendResult.Failed -> when (result.reason) {
                    WakeSendFailure.TARGET_MISSING, WakeSendFailure.PERMISSION -> return result
                    WakeSendFailure.RELAY_REQUIRED -> relayNeeded = result
                    else -> last = result
                }
            }
        }
        return relayNeeded ?: last ?: WakeSendResult.Failed(WakeSendFailure.NETWORK_MISSING)
    }

    fun send(target: StoredWakeTarget, selectedPathId: String, relay: WakeRelay? = null): WakeSendResult {
        if (!target.available || target.mac == null || !MAC.matches(target.mac)) {
            return WakeSendResult.Failed(WakeSendFailure.TARGET_MISSING)
        }
        val path = try {
            if (networks.localNetworkPermissionState() == LocalNetworkPermissionState.RequiredPermissionMissing) {
                return WakeSendResult.Failed(WakeSendFailure.PERMISSION)
            }
            networks.activeLanPaths().firstOrNull { it.id == selectedPathId }
        } catch (_: SecurityException) {
            return WakeSendResult.Failed(WakeSendFailure.PERMISSION)
        }
            ?: return WakeSendResult.Failed(WakeSendFailure.NETWORK_MISSING)
        if (path.tunnel && relay == null) return WakeSendResult.Failed(WakeSendFailure.RELAY_REQUIRED)
        val destinations = if (path.tunnel) relayDestinations(relay!!) else destinations(path)
        if (destinations.isEmpty()) return WakeSendResult.Failed(WakeSendFailure.NETWORK_MISSING)
        val port = if (path.tunnel) relay!!.port else WAKE_PORT
        val payload = magicPacket(target.mac)
        val sent = mutableListOf<InetAddress>()
        var failure: WakeSendFailure? = null
        var detail: String? = null
        try {
            sockets.createDatagram().use { socket ->
                networks.bind(socket, path)
                socket.bind(InetSocketAddress(0))
                socket.broadcast = !path.tunnel
                for (address in destinations) {
                    if (networks.localNetworkPermissionState() == LocalNetworkPermissionState.RequiredPermissionMissing) {
                        failure = WakeSendFailure.PERMISSION
                        break
                    }
                    // A failed send did not transmit that packet, so the next destination is still tried:
                    // some networks refuse the limited broadcast or the directed one, rarely both.
                    try {
                        socket.send(DatagramPacket(payload, payload.size, address, port))
                        sent += address
                    } catch (error: IOException) {
                        if (failure == null) {
                            failure = WakeSendFailure.SEND_FAILED
                            detail = describe(error)
                        }
                    }
                }
            }
        } catch (error: SecurityException) {
            return failure(sent, WakeSendFailure.PERMISSION, describe(error))
        } catch (error: IOException) {
            return failure(sent, WakeSendFailure.SEND_FAILED, describe(error))
        }
        return failure?.let { failure(sent, it, detail) } ?: WakeSendResult.Sent(sent, viaRelay = path.tunnel)
    }

    private fun failure(sent: List<InetAddress>, reason: WakeSendFailure, detail: String?): WakeSendResult =
        if (sent.isEmpty()) WakeSendResult.Failed(reason, detail) else WakeSendResult.Partial(sent, reason, detail)

    companion object {
        fun describe(error: Exception): String =
            "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim().trimEnd(':').take(120)

        private const val WAKE_PORT = 9
        private val MAC = Regex("[0-9a-f]{12}")
        private val LIMITED_BROADCAST: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

        fun relayDestinations(relay: WakeRelay): List<InetAddress> =
            listOf(relay.address).filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }

        fun destinations(path: LanPath): List<InetAddress> {
            if (path.addresses.none { it is Inet4Address }) return emptyList()
            return (path.broadcastAddresses.filterIsInstance<Inet4Address>() + LIMITED_BROADCAST)
                .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
                .distinct()
        }

        fun magicPacket(mac: String): ByteArray {
            require(MAC.matches(mac)) { "Invalid Wake MAC address" }
            val hardware = ByteArray(6) { index -> mac.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
            return ByteArray(102) { index -> if (index < 6) 0xff.toByte() else hardware[(index - 6) % 6] }
        }
    }
}
