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
    data class Sent(val destinations: List<InetAddress>) : WakeSendResult
    /** One or more packets may have left the phone. Only a new user action may retry. */
    data class Partial(val destinations: List<InetAddress>, val reason: WakeSendFailure) : WakeSendResult
    data class Failed(val reason: WakeSendFailure) : WakeSendResult
}

enum class WakeSendFailure { TARGET_MISSING, NETWORK_MISSING, PERMISSION, SEND_FAILED }

/** A single foreground Wake activation. It never interprets packet transmission as host readiness. */
class WakeSender(
    private val networks: NetworkAccess,
    private val sockets: SocketFactory,
) {
    fun send(target: StoredWakeTarget, selectedPathId: String): WakeSendResult {
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
        val destinations = destinations(path)
        if (destinations.isEmpty()) return WakeSendResult.Failed(WakeSendFailure.NETWORK_MISSING)
        val payload = magicPacket(target.mac)
        val sent = mutableListOf<InetAddress>()
        try {
            sockets.createDatagram().use { socket ->
                networks.bind(socket, path)
                socket.bind(InetSocketAddress(0))
                socket.broadcast = true
                for (address in destinations) {
                    if (networks.localNetworkPermissionState() == LocalNetworkPermissionState.RequiredPermissionMissing) {
                        return failure(sent, WakeSendFailure.PERMISSION)
                    }
                    // Do not continue after a failure: the first packet may already have arrived.
                    socket.send(DatagramPacket(payload, payload.size, address, WAKE_PORT))
                    sent += address
                }
            }
        } catch (error: SecurityException) {
            return failure(sent, WakeSendFailure.PERMISSION)
        } catch (error: IOException) {
            return failure(sent, WakeSendFailure.SEND_FAILED)
        }
        return WakeSendResult.Sent(sent)
    }

    private fun failure(sent: List<InetAddress>, reason: WakeSendFailure): WakeSendResult =
        if (sent.isEmpty()) WakeSendResult.Failed(reason) else WakeSendResult.Partial(sent, reason)

    companion object {
        private const val WAKE_PORT = 9
        private val MAC = Regex("[0-9a-f]{12}")
        private val LIMITED_BROADCAST: InetAddress = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))

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
