package com.phonemic

import android.content.Context
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
import java.io.IOException
import javax.net.ssl.SSLException
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
    private val networks = LanNetworks(connectivity) { networkChanged() }
    private var lastNetwork = networks.signature()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networkChanged()
        override fun onLost(network: Network) = networkChanged()
    }
    init { networks.start(); connectivity.registerDefaultNetworkCallback(networkCallback) }
    @Synchronized private fun networkChanged() {
        val next = networks.signature()
        if (lastNetwork == next) return
        lastNetwork = next
        val wasActive = socket != null
        disconnect()
        if (wasActive) status("disconnected", "Network changed")
    }
    private fun fingerprint(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    private fun client(pin: String?, observed: AtomicReference<String>? = null, network: Network? = null): OkHttpClient {
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
        val builder = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, manager)
            // The exact pinned certificate authenticates self-signed LAN endpoints.
            .hostnameVerifier { _, _ -> true }.connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .pingInterval(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).proxy(java.net.Proxy.NO_PROXY)
        if (network != null) builder.socketFactory(network.socketFactory).dns(object : Dns {
            override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
        })
        return builder.build()
    }
    private fun url(host: String, path: String) = LanEndpoint.parse(host).newBuilder().addPathSegment(path).build()
    private fun status(state: String, message: String = "") {
        emit(Arguments.createMap().apply { putString("type", "status"); putString("transport", "lan"); putString("state", state); putString("message", message) })
    }
    private fun failureCode(error: Exception, fallback: String) = when (error) {
        is IllegalArgumentException -> "ADDRESS"
        is SSLException -> "CERTIFICATE"
        is IOException -> "UNREACHABLE"
        else -> fallback
    }
    fun discover(promise: Promise) {
        io.execute {
            try {
                val result = Arguments.createArray()
                LanDiscovery(context, networks).scan().forEach { result.pushMap(jsonMap(it)) }
                promise.resolve(result)
            } catch (e: Exception) { promise.reject("DISCOVERY", e.message, e) }
        }
    }
    private fun jsonMap(data: JSONObject): WritableMap = Arguments.createMap().apply {
        for (key in listOf("id", "name", "host", "fingerprint", "token")) if (data.has(key)) putString(key, data.getString(key))
    }
    fun probe(host: String, promise: Promise) {
        io.execute {
            try {
                val endpoint = LanEndpoint.parse(host)
                var failure: IOException? = null
                for (network in networks.routes(endpoint.host)) {
                    val actual = AtomicReference<String>()
                    val probeClient = client(null, actual, network)
                    try {
                        probeClient.newCall(Request.Builder().url(url(host, "identity")).build()).execute().use { response ->
                            if (!response.isSuccessful) throw IllegalStateException("Computer unavailable")
                            val data = JSONObject(response.body?.string() ?: "{}")
                            if (data.optString("service") != "phonemic" || data.optString("id").isBlank() || data.optString("fingerprint") != actual.get()) throw IllegalStateException("Not a PhoneMIC receiver")
                            data.put("host", LanEndpoint.address(endpoint)); promise.resolve(jsonMap(data)); return@execute
                        }
                    } catch (e: IOException) { failure = e }
                    finally { dispose(probeClient) }
                }
                throw failure ?: IOException("Computer unavailable")
            } catch (e: Exception) { promise.reject(failureCode(e, "PROBE"), e.message, e) }
        }
    }
    fun pair(host: String, fingerprint: String, code: String, promise: Promise) {
        io.execute {
            var pairingFailure = "PAIR"
            try {
                val endpoint = LanEndpoint.parse(host)
                val body = JSONObject().put("clientId", clientIdentity()).put("name", "${Build.MANUFACTURER} ${Build.MODEL}").put("fingerprint", fingerprint).toString()
                val request = Request.Builder().url(url(host, "pair")).post(body.toRequestBody("application/json".toMediaType())).build()
                var failure: IOException? = null
                for (network in networks.routes(endpoint.host)) {
                    val pairingClient = client(fingerprint, network = network)
                    try {
                        pairingClient.newCall(request).execute().use { response ->
                            val data = JSONObject(response.body?.string() ?: "{}")
                            if (!response.isSuccessful) {
                                if (response.code == 409) pairingFailure = "LINKED"
                                throw IllegalStateException(data.optString("error", "Connection failed"))
                            }
                            if (data.optString("fingerprint") != fingerprint) throw IllegalStateException("Certificate mismatch")
                            data.put("host", LanEndpoint.address(endpoint)); promise.resolve(jsonMap(data)); return@execute
                        }
                    } catch (e: IOException) { failure = e }
                    finally { dispose(pairingClient) }
                }
                throw failure ?: IOException("Computer unavailable")
            } catch (e: Exception) { promise.reject(if (pairingFailure == "LINKED") pairingFailure else failureCode(e, "PAIR"), e.message, e) }
        }
    }
    @Synchronized fun connect(target: String, promise: Promise) {
        disconnect()
        val current = generation
        try {
            val data = JSONObject(target)
            val host = data.getString("host")
            val request = Request.Builder().url(url(host, "ws")).header("Authorization", "Bearer ${data.getString("token")}").build()
            status("connecting")
            openSocket(data.getString("fingerprint"), request, networks.routes(LanEndpoint.parse(host).host), 0, current)
            promise.resolve(true)
        } catch (e: Exception) { status("disconnected", e.message ?: "Connection failed"); promise.reject("CONNECT", e.message, e) }
    }
    @Synchronized private fun openSocket(pin: String, request: Request, routes: List<Network?>, index: Int, current: Int) {
        if (current != generation) return
        val nextClient = client(pin, network = routes[index])
        client = nextClient
        socket = nextClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (current == generation) { opened = true; status("connected") }
                    else webSocket.close(1000, "Superseded")
                }
                override fun onMessage(webSocket: WebSocket, text: String) { if (current == generation) emit(Arguments.createMap().apply { putString("type", "message"); putString("transport", "lan"); putString("data", text) }) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(this@LanTransport) {
                        if (current != generation) return
                        val retry = !opened && response == null && index + 1 < routes.size
                        opened = false
                        dispose(nextClient)
                        if (retry) openSocket(pin, request, routes, index + 1, current)
                        else status(if (response?.code == 401) "unauthorized" else "disconnected", t.message ?: "Connection failed")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (current == generation) { opened = false; status("disconnected", reason) } }
        })
    }
    fun send(payload: String, promise: Promise) {
        if (payload.length > 120000) { promise.reject("MESSAGE_SIZE", "Message too large"); return }
        if (opened && socket?.send(payload) == true) promise.resolve(true) else promise.reject("DISCONNECTED", "Computer disconnected")
    }
    @Synchronized fun disconnect() {
        generation++
        opened = false
        socket?.cancel(); socket = null
        client?.let { dispose(it) }; client = null
    }
    private fun dispose(value: OkHttpClient) { value.connectionPool.evictAll(); value.dispatcher.executorService.shutdown() }
    fun close() {
        connectivity.unregisterNetworkCallback(networkCallback)
        networks.close()
        disconnect(); io.shutdown()
    }
}
