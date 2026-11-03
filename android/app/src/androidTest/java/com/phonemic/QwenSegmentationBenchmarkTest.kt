package com.phonemic

import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.Vad
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Measures endpoint policies with identical PCM and VAD probabilities; production stays unchanged. */
@RunWith(AndroidJUnit4::class)
class QwenSegmentationBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private data class Fixture(val name: String, val samples: ShortArray, val keywords: List<String>)
    private data class Segment(
        val startSample: Int,
        val endSample: Int,
        val voiceEndSample: Int,
        val samples: FloatArray,
        val reason: String,
    )
    private data class Policy(val name: String, val continuationThreshold: Float)

    @Before fun foreground() {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun pcm(name: String): ShortArray {
        val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
        return ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    private fun probabilities(samples: ShortArray, detector: Vad): FloatArray {
        detector.reset()
        val frame = FloatArray(512)
        // Match capture exactly: an unfinished final frame is not zero-padded or evaluated.
        return FloatArray(samples.size / frame.size) { index ->
            val start = index * frame.size
            for (i in frame.indices) frame[i] = samples[start + i] / 32768f
            detector.compute(frame)
        }
    }

    private fun segment(samples: ShortArray, probabilities: FloatArray, policy: Policy): List<Segment> {
        val window = SpeechCaptureWindow()
        val segments = ArrayList<Segment>()
        var offset = 0
        var frameIndex = 0
        fun commit(reason: String) {
            if (window.hasSpeech) {
                val start = offset - window.used
                segments.add(Segment(start, offset, start + window.voiceEnd, window.snapshot(), reason))
            }
        }
        while (offset < samples.size) {
            val count = minOf(1600, samples.size - offset)
            if (window.used + count > window.capacity) {
                commit("capacity_before_read")
                window.reset()
            }
            val read = samples.copyOfRange(offset, offset + count)
            window.append(read, count) {
                val threshold = if (window.hasSpeech) policy.continuationThreshold else 0.35f
                probabilities[frameIndex++] >= threshold
            }
            offset += count
            if (window.shouldCommit) {
                commit(if (window.used == window.capacity) "capacity" else "silence_700ms")
                window.reset()
            }
            window.trimIdle()
        }
        commit("recording_end")
        check(frameIndex == probabilities.size) { "Policy changed the VAD frame sequence" }
        return segments
    }

    private fun bandFrameCount(probabilities: FloatArray, start: Int, end: Int): Int =
        probabilities.indices.count { index ->
            index * 512 < end && (index + 1) * 512 > start &&
                probabilities[index] >= 0.20f && probabilities[index] < 0.35f
        }

    private fun decodeAndReport(
        recognizer: OfflineRecognizer,
        fixture: Fixture,
        probabilities: FloatArray,
        policy: Policy,
        segments: List<Segment>,
        repeat: Int,
        order: Int,
    ) {
        val details = JSONArray()
        var text = ""
        var decodeNanos = 0L
        for (segment in segments) {
            val started = System.nanoTime()
            val part = BilingualSpeechModel.decode(recognizer, segment.samples)
            val elapsed = System.nanoTime() - started
            decodeNanos += elapsed
            text = BilingualSpeechModel.combine(text, part)
            details.put(JSONObject()
                .put("startSample", segment.startSample)
                .put("endSampleExclusive", segment.endSample)
                .put("voiceEndSample", segment.voiceEndSample)
                .put("voiceEndInSegment", segment.voiceEndSample - segment.startSample)
                .put("audioMs", segment.samples.size / 16.0)
                .put("tailMs", (segment.endSample - segment.voiceEndSample) / 16.0)
                .put("probabilityBandFrames", bandFrameCount(probabilities, segment.startSample, segment.endSample))
                .put("commitReason", segment.reason)
                .put("decodeMs", elapsed / 1000000.0)
                .put("text", part))
        }
        val keywordResults = JSONObject()
        for (keyword in fixture.keywords) keywordResults.put(keyword, text.contains(keyword, true))
        val result = JSONObject()
            .put("fixture", fixture.name)
            .put("policy", policy.name)
            .put("repeat", repeat)
            .put("orderInPair", order)
            .put("numThreads", recognizer.config.modelConfig.numThreads)
            .put("startThreshold", 0.35)
            .put("continuationThreshold", policy.continuationThreshold.toDouble())
            .put("endpointSilenceMs", 700)
            .put("capacityMs", 20000)
            .put("audioMs", fixture.samples.size / 16.0)
            .put("vadFrames", probabilities.size)
            .put("unscoredTailSamples", fixture.samples.size % 512)
            .put("probabilityBand", "[0.20,0.35)")
            .put("probabilityBandFrames", bandFrameCount(probabilities, 0, fixture.samples.size))
            .put("chunks", segments.size)
            .put("decodedAudioMs", segments.sumOf { it.samples.size } / 16.0)
            .put("decodeMs", decodeNanos / 1000000.0)
            .put("text", text)
            .put("expectedKeywords", JSONArray(fixture.keywords))
            .put("keywords", keywordResults)
            .put("expectedKeywordsPresent", fixture.keywords.all { text.contains(it, true) })
            .put("segments", details)
            .put("scope", "Serial segment decode only; not live preview or stop-to-final latency")
        instrumentation.sendStatus(0, Bundle().apply { putString("qwen_segmentation", result.toString()) })
    }

    @Test fun compareFixedThresholdAndSpeechContinuationHysteresis() {
        val repeats = (InstrumentationRegistry.getArguments().getString("segmentationRepeats")?.toIntOrNull() ?: 2)
            .coerceIn(1, 10)
        ResidentQwenModel.worker.submit {
            val chinese = pcm("zh")
            val fixtures = listOf(
                Fixture("zh_pause_en", chinese + ShortArray(11200) + pcm("en") + ShortArray(16000),
                    listOf("今天", "中文", "world", "microphone")),
                Fixture("soft_zh", ShortArray(chinese.size) { (chinese[it] * 0.25f).toInt().toShort() },
                    listOf("今天", "中文")),
                Fixture("mixed-zh-words", pcm("mixed-zh-words"), listOf("打开", "Chrome", "React")),
                Fixture("mixed-en-words", pcm("mixed-en-words"), listOf("open", "设置", "中文")),
            )
            val policies = listOf(Policy("fixed_035", 0.35f), Policy("speech_continuation_020", 0.20f))
            val detector = ResidentQwenModel.voiceDetector(context)
            val recognizer = ResidentQwenModel.load(context)
            // Exclude model loading and the first decoder invocation from both strategies.
            BilingualSpeechModel.decode(recognizer, FloatArray(chinese.size) { chinese[it] / 32768f })
            for ((fixtureIndex, fixture) in fixtures.withIndex()) {
                val probabilities = probabilities(fixture.samples, detector)
                val plans = policies.map { segment(fixture.samples, probabilities, it) }
                repeat(repeats) { trial ->
                    val order = if ((fixtureIndex + trial) % 2 == 0) listOf(0, 1) else listOf(1, 0)
                    for ((position, policyIndex) in order.withIndex()) {
                        decodeAndReport(recognizer, fixture, probabilities, policies[policyIndex],
                            plans[policyIndex], trial + 1, position + 1)
                    }
                }
            }
        }.get(600, TimeUnit.SECONDS)
    }
}
