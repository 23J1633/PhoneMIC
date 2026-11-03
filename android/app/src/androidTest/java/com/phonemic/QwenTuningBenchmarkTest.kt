package com.phonemic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Measurements, not an assertion that any candidate is faster or more accurate. */
@RunWith(AndroidJUnit4::class)
class QwenTuningBenchmarkTest {
    @Test fun compareCpuThreadsWithRepeatedBilingualFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        ResidentQwenModel.worker.submit {
            val names = listOf("zh", "en", "mixed-zh-words", "mixed-en-words")
            val audio = names.associateWith { name ->
                val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
                FloatArray(bytes.size / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
            }
            val reference = HashMap<String, String>()
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            for ((phase, threads) in listOf(3, 1, 2, 4, 3).withIndex()) {
                val recognizer = OfflineRecognizer(context.assets, OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = "model-qwen/conv_frontend.onnx",
                            encoder = "model-qwen/encoder.int8.onnx",
                            decoder = "model-qwen/decoder.int8.onnx",
                            tokenizer = "model-qwen/tokenizer",
                            maxTotalLen = 1024, maxNewTokens = 256,
                        ), numThreads = threads, provider = "cpu",
                    ), decodingMethod = "greedy_search",
                ))
                try {
                    BilingualSpeechModel.decode(recognizer, audio.getValue("zh"))
                    val rows = JSONArray()
                    val thermalBefore = power.currentThermalStatus
                    repeat(3) { iteration ->
                        for (name in names) {
                            val started = SystemClock.elapsedRealtime()
                            val text = BilingualSpeechModel.decode(recognizer, audio.getValue(name))
                            val elapsed = SystemClock.elapsedRealtime() - started
                            if (phase == 0 && iteration == 0) reference[name] = text
                            val keywords = when (name) {
                                "zh" -> listOf("今天", "中文")
                                "en" -> listOf("world", "microphone")
                                "mixed-zh-words" -> listOf("打开", "Chrome", "React")
                                else -> listOf("open", "设置", "中文")
                            }
                            rows.put(JSONObject().put("fixture", name).put("iteration", iteration)
                                .put("audioMs", audio.getValue(name).size / 16).put("decodeMs", elapsed)
                                .put("text", text).put("sameAsThreeThreads", text == reference[name])
                                .put("expectedKeywordsPresent", keywords.all { text.contains(it, true) }))
                        }
                    }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("thread_benchmark", JSONObject().put("phase", phase).put("threads", threads)
                            .put("thermalBefore", thermalBefore).put("thermalAfter", power.currentThermalStatus)
                            .put("rows", rows).toString())
                    })
                } finally { recognizer.release() }
            }
        }.get(300, TimeUnit.SECONDS)
    }
}
