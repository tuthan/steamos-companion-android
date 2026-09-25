package io.github.tuthan.steamoscompanion.core.ports

import java.net.DatagramSocket
import java.net.Socket

/** Injectable socket creation for deterministic transport fakes and platform adapters. */
interface SocketFactory {
    fun create(): Socket

    /** An unbound datagram socket, so the adapter can bind it to a LAN path before use. */
    fun createDatagram(): DatagramSocket
}
