package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.tuthan.steamoscompanion.core.client.StatusPoller
import io.github.tuthan.steamoscompanion.core.discovery.DiscoveryScanner
import java.io.Closeable

/** Registered only while the paired graph is foregrounded. Network changes trigger reads only. */
class PairedNetworkMonitor(
    context: Context,
    private val poller: StatusPoller,
    private val discovery: DiscoveryScanner,
    private val reevaluateWakeInterface: () -> Unit,
) : Closeable {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: throw IllegalStateException("Connectivity service is unavailable")
    private data class SeenNetwork(val transports: Int? = null, val link: String? = null)
    private val seen = mutableMapOf<Network, SeenNetwork>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = synchronized(this@PairedNetworkMonitor) {
                if (!registered || network in seen) false else { seen[network] = SeenNetwork(); true }
            }
            if (changed) dispatchChange()
        }

        override fun onLost(network: Network) {
            val changed = synchronized(this@PairedNetworkMonitor) { registered && seen.remove(network) != null }
            if (changed) dispatchChange()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val flags = (if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 1 else 0) or
                (if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 2 else 0) or
                (if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) 4 else 0)
            val changed = synchronized(this@PairedNetworkMonitor) {
                if (!registered) false else {
                    val previous = seen[network]
                    seen[network] = (previous ?: SeenNetwork()).copy(transports = flags)
                    previous?.transports != null && previous.transports != flags
                }
            }
            if (changed) dispatchChange()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val signature = properties.interfaceName.orEmpty() + ":" +
                properties.linkAddresses.map { it.toString() }.sorted().joinToString(",")
            val changed = synchronized(this@PairedNetworkMonitor) {
                if (!registered) false else {
                    val previous = seen[network]
                    seen[network] = (previous ?: SeenNetwork()).copy(link = signature)
                    previous?.link != null && previous.link != signature
                }
            }
            if (changed) dispatchChange()
        }
    }
    @Volatile private var registered = false

    @Synchronized fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        registered = true
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (error: RuntimeException) {
            registered = false
            throw error
        }
    }

    @Synchronized override fun close() {
        if (!registered) return
        registered = false
        connectivity.unregisterNetworkCallback(callback)
        seen.clear()
    }

    private fun dispatchChange() {
        if (!registered) return
        discovery.networkChanged()
        poller.networkChanged()
        reevaluateWakeInterface()
    }
}
