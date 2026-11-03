package com.phonemic

import android.content.Context
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class LanTransport(private val context: Context, private val emit: (WritableMap) -> Unit) {
    private fun clientIdentity(): String = synchronized(LanTransport::class.java) {
        val preferences = context.getSharedPreferences("phonemic.identity", Context.MODE_PRIVATE)
        preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
            if (!preferences.edit().putString("id", it).commit()) throw IllegalStateException("Could not save device identity")
        }
    }
    private val io = Executors.newCachedThreadPool()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var generation = 0
    @Volatile private var opened = false
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var lastNetwork: Network? = connectivity.activeNetwork
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged(network)
        override fun onLost(network: Network) {
            if (lastNetwork == network) networkChanged(null)
        }
    }
    init { connectivity.registerDefaultNetworkCallback(networkCallback) }
    @Synchronized private fun networkChanged(network: Network?) {
        if (lastNetwork == network) return
        lastNetwork = network
        val wasActive = socket != null
        disconnect()
        if (wasActive || network != null) status("disconnected", "Network changed")
    }
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    private fun client(pin: String?, observed: AtomicReference<String>? = null): OkHttpClient {
        if (pin != null) require(pin.matches(Regex("[a-f0-9]{64}"))) { "Invalid certificate fingerprint" }
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw java.security.cert.CertificateException("Client certificates unsupported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("Missing certificate")
                chain[0].checkValidity()
                val actual = fingerprint(chain[0])
                observed?.set(actual)
                if (pin != null && actual != pin) throw java.security.cert.CertificateException("Computer certificate changed; pair again")
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager)
            // The exact pinned certificate authenticates self-signed LAN endpoints.
            .hostnameVerifier { _, _ -> true }.connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
            .pingInterval(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).proxy(java.net.Proxy.NO_PROXY).build()
    }
    private fun url(host: String, path: String) = HttpUrl.Builder().scheme("https").host(host.trim()).port(8765).addPathSegment(path).build()
    private fun status(state: String, message: String = "") {
        emit(Arguments.createMap().apply { putString("type", "status"); putString("transport", "lan"); putString("state", state); putString("message", message) })
    }
    fun discover(promise: Promise) {
        io.execute {
            var lock: WifiManager.MulticastLock? = null
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("phonemic-discovery")?.apply { setReferenceCounted(false); acquire() }
                val nonce = UUID.randomUUID().toString()
                val request = JSONObject().put("service", "phonemic-discover-v1").put("nonce", nonce).toString().toByteArray()
                val found = LinkedHashMap<String, JSONObject>()
                DatagramSocket().use { udp ->
                    udp.broadcast = true; udp.soTimeout = 300
                    val addresses = linkedSetOf(InetAddress.getByName("255.255.255.255"))
                    NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }?.forEach { network ->
                        network.interfaceAddresses.mapNotNull { it.broadcast }.forEach { addresses.add(it) }
                    }
                    for (address in addresses) try { udp.send(DatagramPacket(request, request.size, address, 45873)) } catch (_: Exception) {}
                    val deadline = System.currentTimeMillis() + 2200
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val packet = DatagramPacket(ByteArray(4096), 4096)
                            udp.receive(packet)
                            val data = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                            if (data.optString("service") == "phonemic" && data.optString("nonce") == nonce && data.optString("fingerprint").matches(Regex("[a-f0-9]{64}"))) {
                                data.put("host", packet.address.hostAddress)
                                found[data.getString("id")] = data
                            }
                        } catch (_: SocketTimeoutException) {} catch (_: org.json.JSONException) {}
                    }
                }
                val result = Arguments.createArray()
                found.values.forEach { result.pushMap(jsonMap(it)) }
                promise.resolve(result)
            } catch (e: Exception) { promise.reject("DISCOVERY", e.message, e) }
            finally { try { lock?.release() } catch (_: Exception) {} }
        }
    }
    private fun jsonMap(data: JSONObject): WritableMap = Arguments.createMap().apply {
        for (key in listOf("id", "name", "host", "fingerprint", "token")) if (data.has(key)) putString(key, data.getString(key))
    }
    fun probe(host: String, promise: Promise) {
        io.execute {
            var probeClient: OkHttpClient? = null
            try {
                val actual = AtomicReference<String>()
                probeClient = client(null, actual)
                probeClient.newCall(Request.Builder().url(url(host, "identity")).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Computer unavailable")
                    val data = JSONObject(response.body?.string() ?: "{}")
                    if (data.optString("service") != "phonemic" || data.optString("fingerprint") != actual.get()) throw IllegalStateException("Not a PhoneMIC receiver")
                    data.put("host", host.trim()); promise.resolve(jsonMap(data))
                }
            } catch (e: Exception) { promise.reject("PROBE", e.message, e) }
            finally { probeClient?.connectionPool?.evictAll(); probeClient?.dispatcher?.executorService?.shutdown() }
        }
    }
    fun pair(host: String, fingerprint: String, code: String, promise: Promise) {
        io.execute {
            var pairingClient: OkHttpClient? = null
            var failureCode = "PAIR"
            try {
                pairingClient = client(fingerprint)
                val body = JSONObject().put("clientId", clientIdentity()).put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("fingerprint", fingerprint).toString()
                val request = Request.Builder().url(url(host, "pair")).post(body.toRequestBody("application/json".toMediaType())).build()
                pairingClient.newCall(request).execute().use { response ->
                    val data = JSONObject(response.body?.string() ?: "{}")
                    if (!response.isSuccessful) {
                        if (response.code == 409) failureCode = "LINKED"
                        throw IllegalStateException(data.optString("error", "Connection failed"))
                    }
                    if (data.optString("fingerprint") != fingerprint) throw IllegalStateException("Certificate mismatch")
                    data.put("host", host); promise.resolve(jsonMap(data))
                }
            } catch (e: Exception) { promise.reject(failureCode, e.message, e) }
            finally { pairingClient?.connectionPool?.evictAll(); pairingClient?.dispatcher?.executorService?.shutdown() }
        }
    }
    @Synchronized fun connect(target: String, promise: Promise) {
        disconnect()
        val current = generation
        try {
            val data = JSONObject(target)
            val host = data.getString("host")
            val nextClient = client(data.getString("fingerprint"))
            client = nextClient
            val request = Request.Builder().url(url(host, "ws")).header("Authorization", "Bearer ${data.getString("token")}").build()
            status("connecting")
            socket = nextClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (current == generation) { opened = true; status("connected") }
                    else webSocket.close(1000, "Superseded")
                }
                override fun onMessage(webSocket: WebSocket, text: String) { if (current == generation) emit(Arguments.createMap().apply { putString("type", "message"); putString("transport", "lan"); putString("data", text) }) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (current == generation) { opened = false; status(if (response?.code == 401) "unauthorized" else "disconnected", t.message ?: "Connection failed") }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (current == generation) { opened = false; status("disconnected", reason) } }
            })
            promise.resolve(true)
        } catch (e: Exception) { status("disconnected", e.message ?: "Connection failed"); promise.reject("CONNECT", e.message, e) }
    }
    fun send(payload: String, promise: Promise) {
        if (payload.length > 120000) { promise.reject("MESSAGE_SIZE", "Message too large"); return }
        if (opened && socket?.send(payload) == true) promise.resolve(true) else promise.reject("DISCONNECTED", "Computer disconnected")
    }
    @Synchronized fun disconnect() {
        generation++
        opened = false
        socket?.cancel(); socket = null
        client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown(); client = null
    }
    fun close() {
        connectivity.unregisterNetworkCallback(networkCallback)
        disconnect(); io.shutdown()
    }
}
