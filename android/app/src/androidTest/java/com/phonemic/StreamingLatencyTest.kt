package com.phonemic

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.PromiseImpl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class StreamingLatencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Before fun foreground() {
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun promise(latch: CountDownLatch, error: AtomicReference<String?>): Promise = PromiseImpl(
        Callback { latch.countDown() }, Callback { args -> error.set(args.joinToString()); latch.countDown() },
    )

    @Test fun captureWindowKeepsChineseAndEnglishAcrossPauses() {
        ResidentQwenModel.worker.submit {
            fun pcm(name: String): ShortArray {
                val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
                return ShortArray(bytes.size / 2) { i ->
                    ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
            }
            val audio = pcm("zh") + ShortArray(11200) + pcm("en") + ShortArray(16000)
            val window = SpeechCaptureWindow()
            val detector = ResidentQwenModel.voiceDetector(context).also { it.reset() }
            val recognizer = ResidentQwenModel.load(context)
            val chunks = ArrayList<FloatArray>()
            var offset = 0
            while (offset < audio.size) {
                val count = minOf(1600, audio.size - offset)
                window.append(audio.copyOfRange(offset, offset + count), count) { detector.compute(it) >= window.voiceThreshold }
                offset += count
                if (window.shouldCommit) { chunks.add(window.snapshot()); window.reset() }
                window.trimIdle()
            }
            if (window.hasSpeech) chunks.add(window.snapshot())
            val started = SystemClock.elapsedRealtime()
            var text = ""
            for (chunk in chunks) text = BilingualSpeechModel.combine(text, BilingualSpeechModel.decode(recognizer, chunk))
            assertTrue("Capture lost Chinese: $text", text.contains("今天") && text.contains("中文"))
            assertTrue("Capture lost English: $text", text.contains("world", true) && text.contains("microphone", true))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("capture_clauses", JSONObject().put("text", text).put("chunks", chunks.size)
                    .put("decodeMs", SystemClock.elapsedRealtime() - started).put("audioMs", audio.size / 16).toString())
            })
        }.get(120, TimeUnit.SECONDS)
    }

    @Test fun warmMicrophoneStartsBeforeBlockedInferenceCompletes() {
        val speech = OfflineSpeech(context) { }
        val unblock = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val inferenceCompleted = AtomicBoolean(false)
        val error = AtomicReference<String?>(null)
        try {
            speech.prepare("auto", promise(prepared, error))
            assertTrue("Model preparation timed out", prepared.await(90, TimeUnit.SECONDS))
            assertNull(error.get())
            ResidentQwenModel.worker.execute {
                blocked.countDown()
                unblock.await(30, TimeUnit.SECONDS)
                inferenceCompleted.set(true)
            }
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            val opened = CountDownLatch(1)
            val started = SystemClock.elapsedRealtime()
            speech.start("auto", "warm-start-probe", promise(opened, error), streaming = true)
            val openedBeforeInference = opened.await(2500, TimeUnit.MILLISECONDS)
            val elapsed = SystemClock.elapsedRealtime() - started
            instrumentation.sendStatus(0, Bundle().apply {
                putString("warm_start", JSONObject().put("openedBeforeInferenceCompleted", openedBeforeInference)
                    .put("microphoneOpenMs", elapsed).put("probeTimeoutMs", 2500).toString())
            })
            assertNull(error.get())
            assertTrue("Microphone still waits for the old inference job", openedBeforeInference)
            assertFalse("Inference finished before the microphone opened", inferenceCompleted.get())
        } finally {
            speech.cancel()
            unblock.countDown()
            speech.close().get(10, TimeUnit.SECONDS)
            ResidentQwenModel.worker.submit { }.get(20, TimeUnit.SECONDS)
        }
    }
}
