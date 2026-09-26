package io.github.tuthan.steamoscompanion.android.data

import android.content.Context
import android.net.ConnectivityManager
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
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
    }
    @Volatile private var registered = false

    @Synchronized fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        registered = true
    }

    @Synchronized override fun close() {
        if (!registered) return
        registered = false
        connectivity.unregisterNetworkCallback(callback)
    }

    private fun changed() {
        if (!registered) return
        discovery.networkChanged()
        poller.networkChanged()
        reevaluateWakeInterface()
    }
}
