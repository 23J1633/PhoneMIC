package com.phonemic

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioDeviceCallback
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

class HeadsetButtons(private val context: Context, private val emit: (String) -> Unit) {
    @Volatile private var enabled = false
    @Volatile private var foreground = false
    @Volatile private var pressed = false
    @Volatile private var recording = false
    private var lastEvent = ""
    private var pressedDownTime = -1L
    private var pressedKey = -1
    private var commandKey = -1
    @Volatile private var closed = false
    private val cueHandler = Handler(Looper.getMainLooper())
    private var readyCue: AudioTrack? = null
    private var armedOutput = -1
    private val recentEvents = java.util.ArrayDeque<String>()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { armHeadset() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == armedOutput }) armedOutput = -1
            armHeadset()
        }
    }
    private val session = MediaSession(context, "PhoneMIC-headset").apply {
        setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val event = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent)
                return event?.let { handle(it) } ?: false
            }
            override fun onPlay() { if (available()) send("start") }
            override fun onPause() { if (available()) send("stop") }
            override fun onStop() { if (available()) send("stop") }
        }, Handler(Looper.getMainLooper()))
    }
    init { audio.registerAudioDeviceCallback(devices, cueHandler) }
    private fun available() = !closed && enabled && (foreground || ModelKeepAliveService.microphoneReady)
    private fun trace(value: String) = synchronized(recentEvents) {
        if (recentEvents.size >= 24) recentEvents.removeFirst()
        recentEvents.addLast("${SystemClock.elapsedRealtime()} $value")
    }
    private fun send(action: String) { trace("emit=$action"); emit(action) }
    fun diagnostics(): String = "enabled=$enabled foreground=$foreground recording=$recording available=${available()} armedOutput=$armedOutput\n" +
        synchronized(recentEvents) { recentEvents.joinToString("\n") }
    private fun refresh() {
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (recording) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 0f).build())
        session.isActive = available()
    }
    fun configure(value: Boolean) {
        val activated = value && !enabled
        enabled = value
        if (!value) { pressed = false; commandKey = -1; armedOutput = -1 }
        refresh()
        if (activated) armHeadset()
    }
    fun foreground(value: Boolean) {
        val resumed = value && !foreground
        foreground = value
        if (!value && !ModelKeepAliveService.microphoneReady) pressed = false
        refresh()
        if (resumed) armHeadset(force = true)
    }
    fun recording(value: Boolean) {
        val started = value && !recording
        recording = value; refresh()
        if (started && enabled) playReadyCue(recordingCue = true)
    }
    private fun armHeadset(force: Boolean = false) {
        cueHandler.post {
            if (!available() || recording) return@post
            val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return@post
            if (!force && armedOutput == output.id) return@post
            armedOutput = output.id
            playReadyCue(recordingCue = false)
        }
    }
    private fun releaseReadyCue() {
        val track = readyCue ?: return
        readyCue = null
        runCatching { track.stop() }
        runCatching { track.release() }
    }
    private fun playReadyCue(recordingCue: Boolean) {
        cueHandler.post {
            if (!available() || (recordingCue && !recording) || (!recordingCue && recording)) return@post
            val output = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type ==
                    (if (recordingCue) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            } ?: return@post
            releaseReadyCue()
            runCatching {
                // A real, brief ready cue associates media buttons with this app's playback UID.
                val signal = ShortArray(560) { index ->
                    val envelope = kotlin.math.sin(Math.PI * index / 559).let { it * it }
                    (9000 * envelope * kotlin.math.sin(2 * Math.PI * 880 * index / 16000)).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(if (recordingCue) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(signal.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                readyCue = track
                track.setPreferredDevice(output)
                track.setVolume(0.18f)
                check(track.write(signal, 0, signal.size) == signal.size)
                track.play()
                trace("cue=${if (recordingCue) "recording" else "ready"} outputType=${output.type} duration=35ms")
                cueHandler.postDelayed({ if (readyCue === track) releaseReadyCue() }, 180)
            }.onFailure { releaseReadyCue() }
        }
    }
    fun handle(event: KeyEvent): Boolean {
        if (event.keyCode !in setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) return false
        trace("key=${event.keyCode} action=${event.action} repeat=${event.repeatCount} available=${available()}")
        if (!available()) return false
        val identity = "${event.eventTime}:${event.action}:${event.keyCode}"
        if (identity == lastEvent) return true
        lastEvent = identity
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            val action = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) "start" else "stop"
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { commandKey = event.keyCode; send(action) }
            if (event.action == KeyEvent.ACTION_UP) { if (commandKey != event.keyCode) send(action); commandKey = -1 }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && (!pressed || pressedDownTime != event.downTime || pressedKey != event.keyCode)) {
            pressed = true; pressedDownTime = event.downTime; pressedKey = event.keyCode; send("down")
        }
        if (event.action == KeyEvent.ACTION_UP) { if (!pressed) send("toggle") else { pressed = false; send("up") } }
        return true
    }
    fun close() {
        closed = true; enabled = false; recording = false
        audio.unregisterAudioDeviceCallback(devices)
        cueHandler.post { releaseReadyCue() }
        session.isActive = false; session.release()
    }
}
