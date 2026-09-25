package io.github.tuthan.steamoscompanion.core

import io.github.tuthan.steamoscompanion.core.ports.Clock
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import io.github.tuthan.steamoscompanion.core.ports.StateStore
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Phase 01 smoke check: the manual composition boundary assembles from plain JVM fakes. */
class CorePortsSmokeTest {
    private class FakeClock(var wall: Long, var monotonic: Long) : Clock {
        override fun wallTimeMillis() = wall
        override fun monotonicNanos() = monotonic
    }

    private class MemoryStateStore : StateStore {
        private var value: ByteArray? = null
        override fun read() = value?.copyOf()
        override fun writeAtomically(value: ByteArray) {
            this.value = value.copyOf()
        }
        override fun clear() {
            value = null
        }
    }

    private class RecordingNetworkAccess(private val paths: List<LanPath>) : NetworkAccess {
        val bound = mutableListOf<String>()
        override fun activeLanPaths() = paths
        override fun bind(socket: Socket, path: LanPath) {
            bound += "tcp:${path.id}"
        }
        override fun bind(socket: DatagramSocket, path: LanPath) {
            bound += "udp:${path.id}"
        }
    }

    private object UnboundSockets : SocketFactory {
        override fun create() = Socket()
        override fun createDatagram() = DatagramSocket(null as SocketAddress?)
    }

    @Test
    fun portsAssembleWithoutAndroid() {
        val clock = FakeClock(wall = 1_758_000_000_000, monotonic = 5)
        val store = MemoryStateStore()
        val path = LanPath(
            id = "wifi-1",
            interfaceName = "wlan0",
            addresses = listOf(InetAddress.getByName("192.168.1.20")),
            broadcastAddresses = listOf(InetAddress.getByName("192.168.1.255")),
        )
        val network = RecordingNetworkAccess(listOf(path))
        val ports = CorePorts(clock, store, network, UnboundSockets)

        assertSame(clock, ports.clock)
        assertEquals(listOf(path), ports.networkAccess.activeLanPaths())

        assertNull(ports.stateStore.read())
        ports.stateStore.writeAtomically(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), ports.stateStore.read())
        ports.stateStore.clear()
        assertNull(ports.stateStore.read())

        ports.socketFactory.create().use { ports.networkAccess.bind(it, path) }
        ports.socketFactory.createDatagram().use { datagram ->
            assertEquals(false, datagram.isBound)
            ports.networkAccess.bind(datagram, path)
        }
        assertEquals(listOf("tcp:wifi-1", "udp:wifi-1"), network.bound)
    }
}
