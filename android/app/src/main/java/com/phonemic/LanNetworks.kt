package com.phonemic

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress

internal class LanNetworks(private val connectivity: ConnectivityManager, changed: () -> Unit) {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = changed()
    }

    fun start() {
        // Observe Wi-Fi without requiring Internet validation (hotspots and offline LANs).
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
    }

    fun local(): List<Network> = connectivity.allNetworks.filter { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    fun routes(host: String): List<Network?> {
        // Only parse numeric IPs here; DNS must run on the selected network below.
        val numeric = host.takeIf { it.contains(':') || it.matches(Regex("[0-9.]+")) }
            ?.let { try { InetAddress.getByName(it) } catch (_: Exception) { null } }
        val physical = local()
        val matching = physical.filter { network ->
            numeric != null && connectivity.getLinkProperties(network)?.routes?.any {
                !it.isDefaultRoute && it.destination.contains(numeric)
            } == true
        }
        // Keep the system route as a fallback for VPN-only endpoints and routed networks.
        return (matching + listOf<Network?>(null) + physical).distinct()
    }

    fun signature(): String = (local() + listOfNotNull(connectivity.activeNetwork)).distinct().joinToString("|") {
        "$it:${connectivity.getLinkProperties(it)?.linkAddresses}"
    }

    fun close() = connectivity.unregisterNetworkCallback(callback)
}
