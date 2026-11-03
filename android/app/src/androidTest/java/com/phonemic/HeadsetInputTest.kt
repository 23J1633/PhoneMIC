package com.phonemic

import android.os.SystemClock
import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadsetInputTest {
    @Test fun connectedBluetoothMicrophoneRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val connected = manager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        assumeTrue("No Bluetooth call microphone is connected", connected)
        val input = MicrophoneInput.open(context)
        try {
            input.recorder.startRecording()
            val samples = ShortArray(3200)
            assertTrue(input.recorder.read(samples, 0, samples.size) > 0)
            val routed = input.isHeadsetRouted()
            instrumentation.sendStatus(0, Bundle().apply { putString("bluetooth_microphone", "requested=${input.headsetRequested}, routedType=${input.recorder.routedDevice?.type}, actualHeadset=$routed") })
            assertTrue("Bluetooth was connected but the microphone used another input", routed)
        } finally { input.close() }
    }
    @Test fun pressReleaseRepeatAndDisabledHandling() {
        val events = ArrayList<String>()
        val buttons = HeadsetButtons(InstrumentationRegistry.getInstrumentation().targetContext) { events.add(it) }
        try {
            buttons.configure(true); buttons.foreground(true)
            val time = SystemClock.uptimeMillis()
            fun event(action: Int, repeat: Int = 0) = KeyEvent(time, time + action + repeat * 10, action, KeyEvent.KEYCODE_HEADSETHOOK, repeat)
            assertTrue(buttons.handle(event(KeyEvent.ACTION_DOWN)))
            assertTrue(buttons.handle(event(KeyEvent.ACTION_DOWN, 1)))
            assertTrue(buttons.handle(event(KeyEvent.ACTION_UP)))
            assertTrue(buttons.handle(event(KeyEvent.ACTION_UP)))
            assertEquals(listOf("down", "up"), events)
            assertFalse(buttons.handle(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
            buttons.configure(false)
            assertFalse(buttons.handle(event(KeyEvent.ACTION_DOWN)))
        } finally { buttons.close() }
    }
    @Test fun missingReleaseDoesNotSwallowTheNextPress() {
        val events = ArrayList<String>()
        val buttons = HeadsetButtons(InstrumentationRegistry.getInstrumentation().targetContext) { events.add(it) }
        try {
            buttons.configure(true); buttons.foreground(true)
            val time = SystemClock.uptimeMillis()
            assertTrue(buttons.handle(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)))
            assertTrue(buttons.handle(KeyEvent(time + 1000, time + 1000, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)))
            assertTrue(buttons.handle(KeyEvent(time + 1000, time + 1050, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0)))
            assertEquals(listOf("down", "down", "up"), events)
        } finally { buttons.close() }
    }
    @Test fun explicitPlayPauseAndStopAreNotToggles() {
        val events = ArrayList<String>()
        val buttons = HeadsetButtons(InstrumentationRegistry.getInstrumentation().targetContext) { events.add(it) }
        try {
            buttons.configure(true); buttons.foreground(true)
            val time = SystemClock.uptimeMillis()
            for ((index, key) in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP).withIndex()) {
                val down = time + index * 100L
                buttons.handle(KeyEvent(down, down, KeyEvent.ACTION_DOWN, key, 0))
                buttons.handle(KeyEvent(down, down + 20, KeyEvent.ACTION_UP, key, 0))
            }
            assertEquals(listOf("start", "stop", "stop"), events)
        } finally { buttons.close() }
    }
}
