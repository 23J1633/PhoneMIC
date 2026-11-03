package com.phonemic

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicrophoneInput private constructor(private val context: Context, private val interrupted: () -> Unit) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val originalMode = audioManager.mode
    private var changedMode = false
    private var communicationDeviceSet = false
    private var legacySco = false
    private var focus: AudioFocusRequest? = null
    private var receiver: BroadcastReceiver? = null
    lateinit var recorder: AudioRecord
        private set
    var headsetRequested = false
        private set

    private fun external(type: Int) = type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)

    @Suppress("DEPRECATION")
    private fun open() {
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) interrupted() }
            .build().also { audioManager.requestAudioFocus(it) }
        var bluetooth = false
        if (Build.VERSION.SDK_INT >= 31) {
            val available = audioManager.availableCommunicationDevices.filter { external(it.type) }
            val current = audioManager.communicationDevice
            val device = available.firstOrNull { it.id == current?.id } ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET } ?: available.firstOrNull()
            if (device != null) {
                headsetRequested = true
                bluetooth = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                if (bluetooth && originalMode == AudioManager.MODE_NORMAL) { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION; changedMode = true }
                val ready = CountDownLatch(1)
                val listener = AudioManager.OnCommunicationDeviceChangedListener { selected -> if (selected?.id == device.id) ready.countDown() }
                audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
                try {
                    communicationDeviceSet = audioManager.setCommunicationDevice(device)
                    if (communicationDeviceSet && bluetooth && audioManager.communicationDevice?.id != device.id) ready.await(2200, TimeUnit.MILLISECONDS)
                } finally { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
            }
        } else if (BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED) {
            headsetRequested = true; bluetooth = true
            val ready = CountDownLatch(1)
            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED) ready.countDown()
                }
            }.also { context.registerReceiver(it, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)) }
            if (originalMode == AudioManager.MODE_NORMAL) { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION; changedMode = true }
            audioManager.startBluetoothSco(); legacySco = true
            ready.await(2200, TimeUnit.MILLISECONDS)
            audioManager.isBluetoothScoOn = true
        }
        val size = maxOf(32000, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        recorder = AudioRecord(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        val preferred = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { external(it.type) && (!bluetooth || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
        if (preferred != null) { headsetRequested = true; recorder.setPreferredDevice(preferred) }
    }

    fun isHeadsetRouted() = recorder.routedDevice?.let { external(it.type) } ?: false

    @Suppress("DEPRECATION")
    fun close() {
        if (::recorder.isInitialized) { try { recorder.stop() } catch (_: Exception) {}; recorder.release() }
        if (Build.VERSION.SDK_INT >= 31 && communicationDeviceSet) audioManager.clearCommunicationDevice()
        if (legacySco) { audioManager.isBluetoothScoOn = false; audioManager.stopBluetoothSco() }
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        if (changedMode && audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = originalMode
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    companion object {
        fun open(context: Context, interrupted: () -> Unit = {}): MicrophoneInput {
            val input = MicrophoneInput(context.applicationContext, interrupted)
            try { input.open(); return input }
            catch (error: Exception) { input.close(); Log.e("PhoneMicAudio", "Microphone routing failed", error); throw error }
        }
    }
}
