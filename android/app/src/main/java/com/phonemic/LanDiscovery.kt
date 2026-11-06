package com.phonemic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class LanDiscovery(private val context: Context, private val networks: LanNetworks) {
    fun scan(): List<JSONObject> {
        val found = ConcurrentHashMap<String, JSONObject>()
        val deadline = SystemClock.elapsedRealtime() + 3500
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        var lock: WifiManager.MulticastLock? = null
        val routes = networks.local().map { it as Network? }.ifEmpty { listOf(null) }
        val workers = Executors.newFixedThreadPool(routes.size + 1)
        try {
            // Some OEMs refuse multicast locks; UDP unicast and manual connections still work.
            try { lock = wifi?.createMulticastLock("phonemic-discovery")?.apply { setReferenceCounted(false); acquire() } } catch (_: Exception) {}
            val jobs = routes.map { network -> workers.submit { broadcast(network, deadline, found) } } +
                workers.submit { mdns(deadline, found) }
            jobs.forEach { try { it.get(4, TimeUnit.SECONDS) } catch (_: Exception) { it.cancel(true) } }
            return found.values.sortedBy { it.optString("name") }
        } finally {
            workers.shutdownNow()
            try { lock?.release() } catch (_: Exception) {}
        }
    }

    private fun add(data: JSONObject, host: String, port: Int, found: ConcurrentHashMap<String, JSONObject>) {
        if (data.optString("service") != "phonemic" || data.optString("id").isBlank() ||
            !data.optString("fingerprint").matches(Regex("[a-f0-9]{64}")) || port !in 1..65535) return
        val endpoint = okhttp3.HttpUrl.Builder().scheme("https").host(host).port(port).build()
        data.put("host", LanEndpoint.address(endpoint))
        // Retain the first interface that responds, instead of overwriting it with a VPN address.
        found.putIfAbsent(data.getString("id"), data)
    }

    private fun broadcast(network: Network?, deadline: Long, found: ConcurrentHashMap<String, JSONObject>) {
        try {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val properties = network?.let { connectivity.getLinkProperties(it) }
            val interfaces = if (properties != null) listOfNotNull(NetworkInterface.getByName(properties.interfaceName))
                else NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
            val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
            interfaces.forEach { adapter -> adapter.interfaceAddresses.mapNotNull { it.broadcast }.forEach { addresses.add(it) } }
            val nonce = UUID.randomUUID().toString()
            val request = JSONObject().put("service", "phonemic-discover-v1").put("nonce", nonce).toString().toByteArray()
            DatagramSocket().use { udp ->
                network?.bindSocket(udp)
                udp.broadcast = true
                udp.soTimeout = 180
                var nextSend = 0L
                var sends = 0
                while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
                    if (sends < 3 && SystemClock.elapsedRealtime() >= nextSend) {
                        addresses.forEach { try { udp.send(DatagramPacket(request, request.size, it, 45873)) } catch (_: Exception) {} }
                        sends++
                        nextSend = SystemClock.elapsedRealtime() + 650
                    }
                    try {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        udp.receive(packet)
                        val data = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                        if (data.optString("nonce") == nonce) add(data, packet.address.hostAddress ?: continue, data.optInt("port", 8765), found)
                    } catch (_: SocketTimeoutException) {} catch (_: org.json.JSONException) {} catch (_: IllegalArgumentException) {}
                }
            }
        } catch (_: Exception) { /* DNS-SD and manual connection remain available. */ }
    }

    @Suppress("DEPRECATION")
    private fun mdns(deadline: Long, found: ConcurrentHashMap<String, JSONObject>) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val queue = LinkedBlockingQueue<NsdServiceInfo>(64)
        val seen = HashSet<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) { queue.offer(info) }
        }
        try {
            nsd.discoverServices("_phonemic._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            while (SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted) {
                val info = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (!seen.add(info.serviceName)) continue
                val ready = CountDownLatch(1)
                val resolved = AtomicReference<NsdServiceInfo>()
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) { ready.countDown() }
                    override fun onServiceResolved(service: NsdServiceInfo) { resolved.set(service); ready.countDown() }
                })
                // Resolve one at a time: older Android versions reject concurrent resolutions.
                ready.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                val service = resolved.get() ?: continue
                val data = JSONObject().put("service", "phonemic")
                for (key in listOf("id", "name", "fingerprint")) service.attributes[key]?.let { data.put(key, String(it, Charsets.UTF_8)) }
                val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
                addresses.filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }.sortedBy {
                    if (networks.routes(it.hostAddress ?: "").firstOrNull() != null) 0 else if (it is java.net.Inet4Address) 1 else 2
                }.firstOrNull()?.hostAddress?.let { host ->
                    // Scoped link-local IPv6 needs per-interface URL support; prefer advertised IPv4/ULA.
                    if (!host.contains('%')) add(data, host, service.port, found)
                }
            }
        } catch (_: Exception) { /* UDP discovery is independent of the system DNS-SD service. */ }
        finally { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {} }
    }
}
