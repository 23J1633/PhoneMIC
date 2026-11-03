package com.phonemic

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LowVolumeRecognitionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun pcm(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i -> (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f }
    }
    private fun normalized(samples: FloatArray): FloatArray {
        val peak = samples.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (peak > 0.0005f) (0.1f / peak).coerceIn(1f, 8f) else 1f
        return FloatArray(samples.size) { samples[it] * gain }
    }
    @Test fun quietAudioBaseline() {
        ResidentQwenModel.worker.submit {
            val recognizer = ResidentQwenModel.load(context)
            val detector = ResidentQwenModel.voiceDetector(context)
            val results = JSONArray()
            for (gain in listOf(1f, 0.1f, 0.03f, 0.01f)) {
                val raw = pcm("zh").map { it * gain }.toFloatArray()
                for (normalize in listOf(false, true)) {
                    val input = if (normalize) normalized(raw) else raw
                    val parts = SpeechAudio.chunks(input, detector, keepWholeShort = false)
                    val start = System.nanoTime()
                    val text = BilingualSpeechModel.decode(recognizer, input)
                    val captureVad = ResidentQwenModel.newVoiceDetector(context)
                    val window = SpeechCaptureWindow()
                    var commits = 0
                    var captured = 0
                    try {
                        var offset = 0
                        val audio = input + FloatArray(16000)
                        while (offset < audio.size) {
                            val count = minOf(1600, audio.size - offset)
                            window.append(ShortArray(count) { (audio[offset + it] * 32768).toInt().coerceIn(-32768, 32767).toShort() }, count) {
                                captureVad.compute(it) >= window.voiceThreshold
                            }
                            if (window.shouldCommit) { commits++; captured += window.used; window.reset() }
                            window.trimIdle()
                            offset += count
                        }
                        if (window.hasSpeech) { commits++; captured += window.used }
                    } finally { captureVad.release() }
                    results.put(JSONObject().put("amplitudeScale", gain).put("normalized", normalize)
                        .put("text", text).put("asrMs", (System.nanoTime() - start) / 1000000)
                        .put("vadMs", parts.sumOf { it.size } / 16).put("captureMs", captured / 16).put("commits", commits))
                    if (gain == 1f) assertTrue(text.contains("今天") && text.contains("中文"))
                }
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("low_volume", results.toString()) })
        }.get(180, TimeUnit.SECONDS)
    }
}
