package com.phonemic

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothKeyboard(private val context: Context, private val emit: (WritableMap) -> Unit) {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var hid: BluetoothHidDevice? = null
    private var registered = false
    private var registering = false
    @Volatile private var desired = ""
    @Volatile private var connected: BluetoothDevice? = null
    private val io = Executors.newSingleThreadExecutor()
    private var session = ""
    private var lastText = ""
    private var sequence = 0
    private var finished = false
    @Volatile private var cancelledSession = ""
    private var requestedProxy = false
    private fun status(state: String, message: String = "") {
        emit(Arguments.createMap().apply { putString("type", "status"); putString("transport", "bluetooth"); putString("state", state); putString("message", message) })
    }
    private fun message(data: JSONObject) {
        emit(Arguments.createMap().apply { putString("type", "message"); putString("transport", "bluetooth"); putString("data", data.toString()) })
    }
    fun devices(promise: Promise) {
        try {
            val a = adapter ?: throw IllegalStateException("Bluetooth unavailable")
            if (!a.isEnabled) throw IllegalStateException("Enable Bluetooth first")
            ensureProfile()
            val result = Arguments.createArray()
            a.bondedDevices.forEach { device -> result.pushMap(Arguments.createMap().apply { putString("id", device.address); putString("address", device.address); putString("name", device.name ?: device.address) }) }
            promise.resolve(result)
        } catch (e: Exception) { promise.reject("BLUETOOTH", e.message, e) }
    }
    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registering = false
            registered = isRegistered
            if (isRegistered && desired.isNotEmpty()) {
                try { hid?.connect(adapter?.getRemoteDevice(desired)) }
                catch (e: Exception) { status("disconnected", e.message ?: "Bluetooth connection failed") }
            } else if (!isRegistered && desired.isNotEmpty()) { connected = null; status("disconnected", "Keyboard profile unavailable; keep PhoneMIC open") }
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            if (device == null || device.address != desired) return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { connected = device; status("connected") }
                BluetoothProfile.STATE_DISCONNECTED -> { connected = null; status("disconnected") }
                BluetoothProfile.STATE_CONNECTING -> status("connecting")
            }
        }
        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            if (device != null) hid?.replyReport(device, type, id, ByteArray(8))
        }
        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            if (device != null) hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }
    private fun register() {
        if (registered || registering) return
        registering = true
        val descriptor = intArrayOf(
            0x05,0x01,0x09,0x06,0xa1,0x01,0x85,0x01,
            0x05,0x07,0x19,0xe0,0x29,0xe7,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x08,0x81,0x02,
            0x95,0x01,0x75,0x08,0x81,0x01,
            0x95,0x05,0x75,0x01,0x05,0x08,0x19,0x01,0x29,0x05,0x91,0x02,
            0x95,0x01,0x75,0x03,0x91,0x01,
            0x95,0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81,0x00,0xc0
        ).map { it.toByte() }.toByteArray()
        val settings = BluetoothHidDeviceAppSdpSettings("PhoneMIC", "Voice keyboard", "PhoneMIC", BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor)
        if (hid?.registerApp(settings, null, null, context.mainExecutor, callback) != true) { registering = false; status("disconnected", "This phone does not support Bluetooth HID keyboard mode") }
    }
    private fun ensureProfile() {
        val a = adapter ?: throw IllegalStateException("Bluetooth unavailable")
        if (hid != null) { if (!registered) register(); return }
        if (requestedProxy) return
        requestedProxy = true
        val accepted = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { hid = proxy as BluetoothHidDevice; register() }
            override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; registering = false; connected = null; requestedProxy = false; if (desired.isNotEmpty()) status("disconnected") }
        }, BluetoothProfile.HID_DEVICE)
        if (!accepted) { requestedProxy = false; throw IllegalStateException("Bluetooth HID unavailable on this phone") }
    }
    fun connect(address: String, promise: Promise) {
        try {
            val a = adapter ?: throw IllegalStateException("Bluetooth unavailable")
            if (!a.isEnabled) throw IllegalStateException("Enable Bluetooth first")
            require(a.bondedDevices.any { it.address == address }) { "Pair the computer in Bluetooth settings first" }
            if (desired != address) { connected?.let { hid?.disconnect(it) }; connected = null }
            desired = address
            status("connecting")
            if (hid != null) {
                if (registered) {
                    if (connected?.address == address) status("connected")
                    else if (hid?.connect(a.getRemoteDevice(address)) != true) status("disconnected", "Computer did not accept the keyboard connection")
                } else register()
            } else ensureProfile()
            promise.resolve(true)
        } catch (e: Exception) { promise.reject("BLUETOOTH", e.message, e); status("disconnected", e.message ?: "Bluetooth unavailable") }
    }
    private fun press(device: BluetoothDevice, usage: Int, shift: Boolean = false) {
        val data = ByteArray(8)
        data[0] = if (shift) 2 else 0; data[2] = usage.toByte()
        if (hid?.sendReport(device, 1, data) != true) throw IllegalStateException("Bluetooth keyboard disconnected")
        SystemClock.sleep(5)
        if (hid?.sendReport(device, 1, ByteArray(8)) != true) throw IllegalStateException("Bluetooth keyboard disconnected")
        SystemClock.sleep(5)
    }
    private fun key(char: Char): Pair<Int, Boolean> {
        if (char in 'a'..'z') return Pair(4 + char.code - 'a'.code, false)
        if (char in 'A'..'Z') return Pair(4 + char.code - 'A'.code, true)
        if (char in '1'..'9') return Pair(30 + char.code - '1'.code, false)
        if (char == '0') return Pair(39, false)
        val plain = "\n\t -=[]\\;'`,./"
        val codes = intArrayOf(40,43,44,45,46,47,48,49,51,52,53,54,55,56)
        val index = plain.indexOf(char)
        if (index >= 0) return Pair(codes[index], false)
        val shifted = "!@#$%^&*()_+{}|:\"~<>?"
        val shiftedCodes = intArrayOf(30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,51,52,53,54,55,56)
        val shiftIndex = shifted.indexOf(char)
        if (shiftIndex >= 0) return Pair(shiftedCodes[shiftIndex], true)
        throw IllegalArgumentException("Bluetooth keyboard supports English ASCII only; use LAN for Chinese")
    }
    private fun shortcutUsage(name: String): Int {
        if (name.length == 1 && name[0] in 'A'..'Z') return 4 + name[0].code - 'A'.code
        if (name.length == 1 && name[0] in '0'..'9') return if (name == "0") 39 else 30 + name[0].code - '1'.code
        name.removePrefix("F").toIntOrNull()?.let { if (name.startsWith("F") && it in 1..24) return if (it <= 12) 57 + it else 104 + it - 13 }
        if (name.startsWith("Numpad") && name.length == 7 && name.last() in '0'..'9') return if (name.last() == '0') 98 else 89 + name.last().code - '1'.code
        return mapOf("Enter" to 40, "Escape" to 41, "Backspace" to 42, "Tab" to 43, "Space" to 44,
            "Minus" to 45, "Equal" to 46, "BracketLeft" to 47, "BracketRight" to 48, "Backslash" to 49,
            "Semicolon" to 51, "Quote" to 52, "Backquote" to 53, "Comma" to 54, "Period" to 55, "Slash" to 56,
            "CapsLock" to 57, "PrintScreen" to 70, "Pause" to 72, "Insert" to 73, "Home" to 74, "PageUp" to 75,
            "Delete" to 76, "End" to 77, "PageDown" to 78, "ArrowRight" to 79, "ArrowLeft" to 80, "ArrowDown" to 81, "ArrowUp" to 82,
            "ScrollLock" to 71, "NumLock" to 83, "NumpadDivide" to 84, "NumpadMultiply" to 85, "NumpadSubtract" to 86,
            "NumpadAdd" to 87, "NumpadEnter" to 88, "NumpadDecimal" to 99, "ContextMenu" to 101
        )[name] ?: throw IllegalArgumentException("Unsupported shortcut key")
    }
    private fun shortcut(device: BluetoothDevice, data: JSONObject) {
        val report = ByteArray(8)
        val modifiers = data.optJSONArray("modifiers")
        var mask = 0
        if (modifiers != null) for (i in 0 until modifiers.length()) {
            mask = mask or when (modifiers.getString(i)) {
                "Control" -> 1; "Shift" -> 2; "Alt" -> 4; "Meta" -> 8
                else -> throw IllegalArgumentException("Invalid shortcut modifier")
            }
        }
        report[0] = mask.toByte(); report[2] = shortcutUsage(data.getString("key")).toByte()
        try {
            check(hid?.sendReport(device, 1, report) == true) { "Bluetooth keyboard disconnected" }
            SystemClock.sleep(8)
        } finally { hid?.sendReport(device, 1, ByteArray(8)) }
    }
    fun send(payload: String, promise: Promise) {
        try {
            val control = JSONObject(payload)
            if (control.optString("type") == "cancel") cancelledSession = control.optString("session")
        } catch (_: Exception) {}
        io.execute {
            try {
                val data = JSONObject(payload)
                if (data.getString("type") == "ping") { message(JSONObject().put("type", "pong").put("nonce", data.getLong("nonce"))); promise.resolve(true); return@execute }
                if (data.getString("type") == "cancel") {
                    if (data.optString("session") == session) finished = true
                    message(JSONObject().put("type", "ack").put("session", data.optString("session")).put("seq", data.optInt("seq")))
                    promise.resolve(true); return@execute
                }
                val device = connected ?: throw IllegalStateException("Computer disconnected")
                val id = data.getString("session"); val seq = data.getInt("seq"); val final = data.optBoolean("final")
                if (data.getString("type") == "begin") {
                    if (id != session) { session = id; sequence = 1; lastText = ""; finished = false; cancelledSession = "" }
                } else {
                    require(session == id && seq <= sequence + 1) { "Invalid keyboard session" }
                    if (seq > sequence) {
                        require(!finished) { "Keyboard session already completed" }
                        val text = data.optString("text")
                        val keys = text.map { key(it) }
                        var common = 0
                        while (common < lastText.length && common < text.length && lastText[common] == text[common]) common++
                        for (i in common until lastText.length) { check(cancelledSession != id) { "Keyboard input cancelled" }; press(device, 42) }
                        for (i in common until keys.size) { check(cancelledSession != id) { "Keyboard input cancelled" }; press(device, keys[i].first, keys[i].second) }
                        lastText = text; sequence = seq; finished = final
                        if (final && text.isNotBlank()) data.optJSONObject("shortcut")?.let {
                            check(cancelledSession != id) { "Keyboard input cancelled" }
                            shortcut(device, it)
                        }
                    }
                }
                message(JSONObject().put("type", "ack").put("session", id).put("seq", seq).put("final", final))
                promise.resolve(true)
            } catch (e: Exception) {
                try { val d = JSONObject(payload); message(JSONObject().put("type", "error").put("session", d.optString("session")).put("seq", d.optInt("seq")).put("code", "BLUETOOTH_INPUT").put("message", e.message)) } catch (_: Exception) {}
                promise.reject("BLUETOOTH_INPUT", e.message, e)
            }
        }
    }
    fun disconnect() { desired = ""; connected?.let { try { hid?.disconnect(it) } catch (_: Exception) {} }; connected = null; if (registered) try { hid?.unregisterApp() } catch (_: Exception) {}; registered = false }
    fun close() { disconnect(); hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }; hid = null; io.shutdown() }
}
