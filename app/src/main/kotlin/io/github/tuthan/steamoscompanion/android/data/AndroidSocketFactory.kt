package io.github.tuthan.steamoscompanion.android.data

import io.github.tuthan.steamoscompanion.core.ports.SocketFactory
import java.net.DatagramSocket
import java.net.Socket

/** Creates unbound sockets so Android Network.bindSocket can select the route before connect. */
class AndroidSocketFactory : SocketFactory {
    override fun create(): Socket = Socket()
    override fun createDatagram(): DatagramSocket = DatagramSocket(null as java.net.SocketAddress?)
}
