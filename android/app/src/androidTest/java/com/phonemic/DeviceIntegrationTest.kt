package com.phonemic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.PromiseImpl
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun liveContext(): ReactApplicationContext {
        val application = context.applicationContext as MainApplication
        if (application.reactHost.currentReactContext == null) {
            instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val deadline = System.currentTimeMillis() + 15000
        while (application.reactHost.currentReactContext == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return application.reactHost.currentReactContext as? ReactApplicationContext ?: throw AssertionError("React context did not start")
    }
    private fun report(key: String, value: String) { instrumentation.sendStatus(0, Bundle().apply { putString(key, value) }) }
    private fun pcm(name: String): ByteArray = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
    private fun decode(label: String, bytes: ByteArray): String {
        val started = System.nanoTime()
        val recognizer = BilingualSpeechModel.create(context)
        val loaded = System.nanoTime()
        try {
            val samples = FloatArray(bytes.size / 2) { i ->
                val sample = (bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)
                sample.toShort() / 32768f
            }
            val text = BilingualSpeechModel.decode(recognizer, samples)
            report("local_qwen_$label", JSONObject().put("model", "Qwen3-ASR-0.6B-int8").put("text", text).put("audioMs", bytes.size / 32)
                .put("loadMs", (loaded - started) / 1000000)
                .put("decodeMs", (System.nanoTime() - loaded) / 1000000)
                .put("pssKiB", android.os.Debug.getPss()).toString())
            return text
        } finally {
            recognizer.release()
        }
    }
    private fun invoke(call: (Promise) -> Unit): Any? {
        val latch = CountDownLatch(1)
        val value = AtomicReference<Any?>()
        val error = AtomicReference<String?>()
        call(PromiseImpl(Callback { args -> value.set(args.firstOrNull()); latch.countDown() }, Callback { args -> error.set(args.joinToString()); latch.countDown() }))
        assertTrue("Native call timed out", latch.await(20, TimeUnit.SECONDS))
        assertNull("Native operation failed: ${error.get()}", error.get())
        return value.get()
    }
    @Test fun aEnglishOfflineRecognition() {
        val text = decode("en", pcm("en"))
        assertTrue("Automatic English recognition failed: $text", text.contains("world", true) && text.contains("test", true))
    }
    @Test fun bChineseOfflineRecognition() {
        val text = decode("zh", pcm("zh"))
        assertTrue("Automatic Chinese recognition failed: $text", text.contains("今天") && text.contains("中文"))
    }
    @Test fun yPhysicalMicrophoneCapture() {
        liveContext()
        assertEquals("Microphone permission must be granted before the device check", android.content.pm.PackageManager.PERMISSION_GRANTED,
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val size = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, recorder.state)
            recorder.startRecording()
            val buffer = ShortArray(1600)
            var total = 0
            var peak = 0
            repeat(15) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                assertTrue("Physical microphone returned no PCM", count > 0)
                total += count
                for (i in 0 until count) peak = maxOf(peak, kotlin.math.abs(buffer[i].toInt()))
            }
            assertTrue("Microphone returned only zero samples; check microphone privacy or another recorder", peak > 0)
            report("microphone", "16000 Hz mono PCM captured: $total samples, peak=$peak")
        } finally { try { recorder.stop() } catch (_: Exception) {}; recorder.release() }
    }
    @Test fun zEncryptedLocalStorage() {
        val module = PhoneMicModule(liveContext())
        try {
            val text = "Private transcript / 仅保存在手机"
            invoke { module.setStore("integration-test", text, it) }
            assertEquals(text, invoke { module.getStore("integration-test", it) })
            val raw = context.getSharedPreferences("phonemic.v1", Context.MODE_PRIVATE).getString("integration-test", "") ?: ""
            assertFalse("Transcript stored as plaintext", raw.contains("Private"))
            invoke { module.setStore("integration-test", null, it) }
        } finally { module.invalidate() }
    }
    @Test fun eChineseEnglishSameRecording() {
        val text = decode("zh_en", pcm("zh") + ByteArray(9600) + pcm("en"))
        assertTrue("Chinese-to-English switching failed: $text", text.contains("中文") && text.contains("world", true))
    }
    @Test fun fEnglishChineseSameRecording() {
        val text = decode("en_zh", pcm("en") + ByteArray(9600) + pcm("zh"))
        assertTrue("English-to-Chinese switching failed: $text", text.contains("world", true) && text.contains("中文"))
    }
    @Test fun gChineseSentenceWithEnglishWords() {
        val text = decode("zh_sentence_en_words", pcm("mixed-zh-words"))
        assertTrue("Chinese sentence with English words failed: $text", text.contains("打开") && text.contains("Chrome", true) && text.contains("React", true))
    }
    @Test fun hEnglishSentenceWithChineseWords() {
        val text = decode("en_sentence_zh_words", pcm("mixed-en-words"))
        assertTrue("English sentence with Chinese words failed: $text", text.contains("open", true) && text.contains("设置") && text.contains("中文"))
    }
    @Test fun xNaturalMixedBenchmark() {
        val text = decode("natural_mixed", pcm("mixed-natural"))
        report("natural_mixed_word_comparison", JSONObject().put("previousModelWord", "ALWAYS")
            .put("currentHasAlways", text.contains("always", true)).put("humanVerifiedReference", false).toString())
        assertTrue("Natural mixed speech did not contain both scripts: $text", text.contains("什么") && Regex("[A-Za-z]+").containsMatchIn(text))
    }
    @Test fun zzEncryptedComputerInput() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("host") ?: ""
        assumeTrue("Explicit keyboard fixture not provided", host.isNotEmpty() && args.getString("allowKeyboardInput") == "true")
        val queue = LinkedBlockingQueue<WritableMap>()
        (liveContext().getNativeModule("PhoneMic") as? PhoneMicModule)?.disconnect()
        Thread.sleep(250)
        val lan = LanTransport(context) { queue.offer(it) }
        try {
            val target = invoke { lan.pair(host, args.getString("fingerprint") ?: "", args.getString("code") ?: "", it) } as WritableMap
            val data = JSONObject()
            for (key in listOf("host", "name", "id", "fingerprint", "token")) data.put(key, target.getString(key))
            val storage = PhoneMicModule(liveContext())
            try { invoke { storage.setStore("computer", data.toString(), it) } } finally { storage.invalidate() }
            (liveContext().getNativeModule("PhoneMic") as? PhoneMicModule)?.disconnect()
            Thread.sleep(250)
            invoke { lan.connect(data.toString(), it) }
            val deadline = System.currentTimeMillis() + 10000
            var ready = false
            while (System.currentTimeMillis() < deadline && !ready) {
                val event = queue.poll(1, TimeUnit.SECONDS) ?: continue
                if (event.getString("type") == "status" && event.getString("state") == "connected") ready = true
            }
            assertTrue("Pinned TLS socket did not connect", ready)
            val id = "instrumentation-${System.currentTimeMillis()}"
            fun send(packet: JSONObject) {
                val started = System.nanoTime()
                invoke { lan.send(packet.toString(), it) }
                val until = System.currentTimeMillis() + 8000
                var acknowledged = false
                while (System.currentTimeMillis() < until) {
                    val event = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    if (event.getString("type") != "message") continue
                    val reply = JSONObject(event.getString("data") ?: "{}")
                    if (reply.optString("session") != id || reply.optInt("seq") != packet.getInt("seq")) continue
                    assertEquals("Receiver rejected keyboard input: ${reply.optString("message")}", "ack", reply.optString("type"))
                    acknowledged = true; break
                }
                assertTrue("No input acknowledgement", acknowledged)
                report("lan_packet_${packet.getInt("seq")}_rtt_ms", ((System.nanoTime() - started) / 1000000.0).toString())
            }
            send(JSONObject().put("type", "begin").put("session", id).put("seq", 1))
            send(JSONObject().put("type", "text").put("session", id).put("seq", 2).put("text", "你好，PhoneMIC 4099."))
            send(JSONObject().put("type", "text").put("session", id).put("seq", 3).put("text", "你好，PhoneMIC 4090.\nEnglish input works.").put("final", true))
        } finally { lan.close() }
    }
}
