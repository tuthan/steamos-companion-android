package io.github.tuthan.steamoscompanion.core.wake

import io.github.tuthan.steamoscompanion.core.client.StoredWakeTarget
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.LocalNetworkPermissionState
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeSenderTest {
    private val target = StoredWakeTarget(true, "001122aabbcc", "wlan0", null, null)
    private val wifi = LanPath(
        "wifi", "wlan0", listOf(ip("192.168.4.10")), listOf(ip("192.168.4.255")),
    )

    @Test fun sendsExactMagicPacketToBothBroadcastsOnSelectedBoundNetwork() {
        val network = RecordingNetworks(listOf(wifi))
        val socket = RecordingSocket { assertTrue(network.bound) }
        val result = WakeSender(network, RecordingSockets(socket)).send(target, "wifi")

        assertEquals(listOf(ip("192.168.4.255"), ip("255.255.255.255")), (result as WakeSendResult.Sent).destinations)
        assertEquals(listOf("192.168.4.255", "255.255.255.255"), socket.packets.map { it.address.hostAddress })
        socket.packets.forEach { packet ->
            assertEquals(9, packet.port)
            assertArrayEquals(WakeSender.magicPacket("001122aabbcc"), packet.data)
        }
        assertEquals(102, socket.packets[0].data.size)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, -1, -1, 0, 17, 34, -86, -69, -52), socket.packets[0].data.copyOfRange(0, 12))
        assertTrue(socket.isClosed)
    }

    @Test fun missingTargetOrSelectedNetworkDoesNotCreateSocket() {
        val factory = CountingSockets()
        val sender = WakeSender(RecordingNetworks(listOf(wifi)), factory)
        assertEquals(WakeSendResult.Failed(WakeSendFailure.TARGET_MISSING), sender.send(target.copy(available = false), "wifi"))
        assertEquals(WakeSendResult.Failed(WakeSendFailure.NETWORK_MISSING), sender.send(target, "old-wifi"))
        assertEquals(0, factory.created)
    }

    @Test fun partialSendDoesNotAutomaticallyRetryFailedDestination() {
        val network = RecordingNetworks(listOf(wifi))
        val socket = RecordingSocket { assertTrue(network.bound) }.apply { failOnSend = 2 }
        val result = WakeSender(network, RecordingSockets(socket)).send(target, "wifi")
        assertEquals(WakeSendResult.Partial(listOf(ip("192.168.4.255")), WakeSendFailure.SEND_FAILED), result)
        assertEquals(2, socket.attempts)
        assertEquals(1, socket.packets.size)
    }

    @Test fun missingMandatoryLanGrantDoesNotCreateSocket() {
        val network = RecordingNetworks(listOf(wifi)).apply {
            permission = LocalNetworkPermissionState.RequiredPermissionMissing
        }
        val factory = CountingSockets()
        assertEquals(WakeSendResult.Failed(WakeSendFailure.PERMISSION), WakeSender(network, factory).send(target, "wifi"))
        assertEquals(0, factory.created)
    }

    @Test fun grantRevokedAfterFirstBroadcastReportsPartialWithoutSecondSend() {
        val network = RecordingNetworks(listOf(wifi))
        val socket = RecordingSocket {
            if (network.bound) network.permission = LocalNetworkPermissionState.RequiredPermissionMissing
        }
        val result = WakeSender(network, RecordingSockets(socket)).send(target, "wifi")
        assertEquals(WakeSendResult.Partial(listOf(ip("192.168.4.255")), WakeSendFailure.PERMISSION), result)
        assertEquals(1, socket.attempts)
    }

    private class RecordingNetworks(private val paths: List<LanPath>) : NetworkAccess {
        var bound = false
        var permission = LocalNetworkPermissionState.Allowed
        override fun localNetworkPermissionState() = permission
        override fun activeLanPaths() = paths
        override fun bind(socket: Socket, path: LanPath) = Unit
        override fun bind(socket: DatagramSocket, path: LanPath) { bound = true }
    }

    private class RecordingSockets(private val socket: RecordingSocket) : SocketFactory {
        override fun create() = Socket()
        override fun createDatagram(): DatagramSocket = socket
    }

    private class CountingSockets : SocketFactory {
        var created = 0
        override fun create() = Socket()
        override fun createDatagram(): DatagramSocket { created++; return RecordingSocket {} }
    }

    private class RecordingSocket(private val beforeSend: () -> Unit) : DatagramSocket(null as SocketAddress?) {
        val packets = mutableListOf<DatagramPacket>()
        var attempts = 0
        var failOnSend: Int? = null
        override fun send(packet: DatagramPacket) {
            beforeSend()
            attempts++
            if (attempts == failOnSend) throw IOException("Synthetic packet failure")
            packets += DatagramPacket(packet.data.copyOf(), packet.length, packet.address, packet.port)
        }
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
