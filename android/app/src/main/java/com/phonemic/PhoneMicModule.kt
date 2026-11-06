package com.phonemic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.os.Vibrator
import android.media.AudioAttributes
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import android.view.KeyEvent
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ViewManager
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PhoneMicPackage : ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(PhoneMicModule(context))
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*, *>> = emptyList()
}

class PhoneMicModule(private val context: ReactApplicationContext) : ReactContextBaseJavaModule(context), LifecycleEventListener {
    private val io = Executors.newSingleThreadExecutor()
    private val speech = OfflineSpeech(context) { event -> emit("PhoneMicVoice", event) }
    private val recordingState = SpeechRecordingState()
    private val voiceprint = VoiceprintRecorder(context) { event -> emit("PhoneMicEnrollment", event) }
    private var headsetEnabled = false
    private val headset = HeadsetButtons(context) { action ->
        if (!voiceprint.isActive()) emit("PhoneMicHeadset", Arguments.createMap().apply { putString("action", action) })
    }
    private val lan = LanTransport(context) { event -> emit("PhoneMicTransport", event) }
    private val bluetooth = BluetoothKeyboard(context) { event -> emit("PhoneMicTransport", event) }
    private val prefs = context.getSharedPreferences("phonemic.v1", Context.MODE_PRIVATE)

    init { context.addLifecycleEventListener(this) }
    override fun getName() = "PhoneMic"
    override fun getConstants(): Map<String, Any> = mapOf("appVersion" to BuildConfig.VERSION_NAME)
    private fun emit(name: String, event: WritableMap) {
        if (name == "PhoneMicVoice" && event.hasKey("state")) {
            synchronized(recordingState) {
                val value = recordingState.update(event.getString("state") ?: "",
                    if (event.hasKey("session")) event.getString("session") ?: "" else "")
                if (value != null) {
                    headset.recording(value)
                    ModelKeepAliveService.recording(value)
                }
            }
        }
        if (context.hasActiveReactInstance()) context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java).emit(name, event)
    }

    @ReactMethod fun addListener(name: String) {}
    @ReactMethod fun removeListeners(count: Double) {}
    @ReactMethod fun prepareSpeech(language: String, promise: Promise) = speech.prepare(language, promise)
    @ReactMethod fun startSpeech(language: String, session: String, options: ReadableMap, promise: Promise) {
        if (voiceprint.isActive()) { promise.reject("BUSY", "Voiceprint enrollment in progress"); return }
        val profile = if (options.hasKey("speakerEmbedding")) options.getArray("speakerEmbedding")?.let { values ->
            require(values.size() in 1..512) { "Invalid voiceprint" }
            val mean = FloatArray(values.size()) { values.getDouble(it).toFloat() }
            val references = if (options.hasKey("speakerTemplates")) options.getArray("speakerTemplates")?.let { list ->
                require(list.size() <= 8) { "Too many voiceprint templates" }
                (0 until list.size()).map { i ->
                    val vector = requireNotNull(list.getArray(i))
                    require(vector.size() == mean.size) { "Invalid voiceprint template" }
                    FloatArray(vector.size()) { vector.getDouble(it).toFloat() }
                }
            } ?: emptyList() else emptyList()
            SpeakerVoiceprint.Profile(mean, references)
        } else null
        speech.start(language, session, promise,
            (options.hasKey("preview") && options.getBoolean("preview")) || (options.hasKey("streaming") && options.getBoolean("streaming")),
            if (options.hasKey("hotwords")) options.getString("hotwords") ?: "" else "", profile,
            options.hasKey("background") && options.getBoolean("background"),
        )
    }
    @ReactMethod fun setHeadsetControls(enabled: Boolean) {
        headsetEnabled = enabled
        headset.configure(enabled)
        context.currentActivity?.runOnUiThread { ModelKeepAliveService.configureHeadset(context, enabled) }
    }
    fun headsetKey(event: KeyEvent) = headset.handle(event)
    fun headsetDiagnostics() = headset.diagnostics()
    @ReactMethod fun startVoiceprint(promise: Promise) {
        if (speech.isActive()) { promise.reject("BUSY", "Dictation in progress"); return }
        voiceprint.start(promise)
    }
    @ReactMethod fun cancelVoiceprint() = voiceprint.cancel()
    @ReactMethod fun stopSpeech() = speech.stop()
    @ReactMethod fun cancelSpeech() = speech.cancel()
    @ReactMethod fun discover(promise: Promise) = lan.discover(promise)
    @ReactMethod fun probe(host: String, promise: Promise) = lan.probe(host, promise)
    @ReactMethod fun pair(host: String, fingerprint: String, code: String, promise: Promise) = lan.pair(host, fingerprint, code, promise)
    @ReactMethod fun connect(target: String, promise: Promise) = lan.connect(target, promise)
    @ReactMethod fun disconnect() = lan.disconnect()
    @ReactMethod fun send(payload: String, promise: Promise) = lan.send(payload, promise)
    @ReactMethod fun bluetoothDevices(promise: Promise) = bluetooth.devices(promise)
    @ReactMethod fun connectBluetooth(address: String, promise: Promise) = bluetooth.connect(address, promise)
    @ReactMethod fun disconnectBluetooth() = bluetooth.disconnect()
    @ReactMethod fun sendBluetooth(payload: String, promise: Promise) = bluetooth.send(payload, promise)
    @ReactMethod fun makeDiscoverable(promise: Promise) {
        try {
            val intent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            val activity = context.currentActivity ?: throw IllegalStateException("Activity unavailable")
            activity.runOnUiThread { activity.startActivity(intent); promise.resolve(true) }
        } catch (e: Exception) { promise.reject("BLUETOOTH", e.message, e) }
    }
    @ReactMethod fun openBluetoothSettings() { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    @ReactMethod fun copyText(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PhoneMIC", text))
    }
    @ReactMethod fun haptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        if (!vibrator.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= 30 && vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK).firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES)
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createOneShot(26, if (vibrator.hasAmplitudeControl()) 180 else VibrationEffect.DEFAULT_AMPLITUDE)
        // This app's explicit recording confirmation is independent of keyboard-touch settings.
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        else vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
    }
    @ReactMethod fun keepAwake(enabled: Boolean) {
        context.currentActivity?.runOnUiThread {
            if (enabled) context.currentActivity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else context.currentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("phonemic.storage", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("phonemic.storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @ReactMethod fun getStore(key: String, promise: Promise) {
        io.execute {
            try {
                val encoded = prefs.getString(key, null)
                if (encoded == null) { promise.resolve(null); return@execute }
                val data = Base64.decode(encoded, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
                cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
                promise.resolve(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
            } catch (e: Exception) { promise.reject("STORAGE", e.message, e) }
        }
    }
    @ReactMethod fun setStore(key: String, value: String?, promise: Promise) {
        io.execute {
            try {
                if (value == null) {
                    if (!prefs.edit().remove(key).commit()) throw IllegalStateException("Storage write failed")
                } else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                    cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
                    val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
                    if (!prefs.edit().putString(key, encoded).commit()) throw IllegalStateException("Storage write failed")
                }
                promise.resolve(true)
            } catch (e: Exception) { promise.reject("STORAGE", e.message, e) }
        }
    }
    override fun onHostResume() {
        headset.configure(headsetEnabled); headset.foreground(true)
        if (headsetEnabled) ModelKeepAliveService.configureHeadset(context, true)
    }
    override fun onHostPause() { speech.onBackground(); voiceprint.cancel(); headset.foreground(false) }
    override fun onHostDestroy() { speech.cancel(); voiceprint.cancel(); headset.configure(false); lan.disconnect(); bluetooth.disconnect() }
    override fun invalidate() {
        speech.close(); voiceprint.close(); headset.close(); lan.close(); bluetooth.close(); io.shutdown()
        context.removeLifecycleEventListener(this)
        super.invalidate()
    }
}
