package io.github.tuthan.steamoscompanion.core.ports

import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException

/** A LAN path contains bounded addressing facts and no Android platform objects. */
data class LanPath(
    val id: String,
    val interfaceName: String?,
    val addresses: List<InetAddress>,
    val broadcastAddresses: List<InetAddress>,
)

/**
 * The app adapter maps path IDs to platform networks and binds sockets when requested.
 * TCP carries discovery and paired traffic; UDP carries Wake packets to the path's broadcast addresses.
 */
interface NetworkAccess {
    fun activeLanPaths(): List<LanPath>

    /** Resolve on the selected network when the platform supports per-network DNS. */
    @Throws(UnknownHostException::class)
    fun resolve(host: String, path: LanPath): List<InetAddress> = InetAddress.getAllByName(host).toList()

    @Throws(IOException::class)
    fun bind(socket: Socket, path: LanPath)

    @Throws(IOException::class)
    fun bind(socket: DatagramSocket, path: LanPath)
}
