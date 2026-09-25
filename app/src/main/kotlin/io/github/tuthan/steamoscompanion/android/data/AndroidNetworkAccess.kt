package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import io.github.tuthan.steamoscompanion.core.ports.LanPath
import io.github.tuthan.steamoscompanion.core.ports.Ipv4Subnet
import io.github.tuthan.steamoscompanion.core.ports.NetworkAccess
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/** Selects Wi-Fi/Ethernet networks and binds DNS plus each socket to the same Android Network. */
class AndroidNetworkAccess(context: Context) : NetworkAccess {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: throw IllegalStateException("Connectivity service is unavailable")
    private val networks = ConcurrentHashMap<String, Network>()

    @Suppress("DEPRECATION") // getAllNetworks is the API-21-compatible way to enumerate simultaneous Wi-Fi/Ethernet links.
    override fun activeLanPaths(): List<LanPath> {
        val found = linkedMapOf<String, LanPath>()
        connectivity.allNetworks.forEach { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEach
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) return@forEach
            val properties = connectivity.getLinkProperties(network) ?: return@forEach
            val id = network.toString()
            networks[id] = network
            found[id] = properties.toLanPath(id)
        }
        networks.keys.retainAll(found.keys)
        return found.values.toList()
    }

    override fun resolve(host: String, path: LanPath): List<InetAddress> {
        val network = networks[path.id] ?: refresh(path.id)
            ?: throw UnknownHostException("Selected LAN network is no longer available")
        return network.getAllByName(host).take(MAX_ADDRESSES)
    }

    override fun bind(socket: Socket, path: LanPath) {
        val network = networks[path.id] ?: refresh(path.id)
            ?: throw IOException("Selected LAN network is no longer available")
        if (socket.isConnected) throw IOException("Network must be bound before connect")
        network.bindSocket(socket)
    }

    override fun bind(socket: DatagramSocket, path: LanPath) {
        val network = networks[path.id] ?: refresh(path.id)
            ?: throw IOException("Selected LAN network is no longer available")
        if (socket.isConnected) throw IOException("Network must be bound before connect")
        network.bindSocket(socket)
    }

    @Suppress("DEPRECATION")
    private fun refresh(id: String): Network? {
        val network = connectivity.allNetworks.firstOrNull { it.toString() == id } ?: return null
        networks[id] = network
        return network
    }

    private fun LinkProperties.toLanPath(id: String): LanPath {
        val addresses = linkAddresses.map { it.address }.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
        val broadcasts = linkAddresses.mapNotNull { link ->
            val address = link.address as? Inet4Address ?: return@mapNotNull null
            val prefix = link.prefixLength
            if (prefix !in 0..30) return@mapNotNull null
            val ip = address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xffL) }
            val mask = if (prefix == 0) 0L else (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
            val broadcast = (ip and mask) or (mask xor 0xffff_ffffL)
            InetAddress.getByAddress(byteArrayOf(
                (broadcast shr 24).toByte(), (broadcast shr 16).toByte(), (broadcast shr 8).toByte(), broadcast.toByte(),
            ))
        }.distinct()
        return LanPath(
            id = id,
            interfaceName = interfaceName,
            addresses = addresses,
            broadcastAddresses = broadcasts,
            ipv4Subnets = linkAddresses.mapNotNull { link ->
                val address = link.address as? Inet4Address ?: return@mapNotNull null
                if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) return@mapNotNull null
                Ipv4Subnet(address, link.prefixLength)
            },
        )
    }

    private companion object {
        const val MAX_ADDRESSES = 16
    }
}
