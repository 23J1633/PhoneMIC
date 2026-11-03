package com.phonemic

import android.os.Bundle
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RecognitionOptimizationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Before fun keepDeviceTestInForeground() {
        instrumentation.runOnMainSync { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun pcm(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i -> (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f }
    }
    private fun report(name: String, data: JSONObject) = instrumentation.sendStatus(0, Bundle().apply { putString(name, data.toString()) })
    private fun runOnWorker(work: () -> Unit) { ResidentQwenModel.worker.submit { work() }.get(120, TimeUnit.SECONDS) }

    @Test fun streamedClausesChineseAndEnglish() = runOnWorker {
        val samples = pcm("zh") + FloatArray(9600) + pcm("en")
        val detector = ResidentQwenModel.voiceDetector(context)
        val chunks = SpeechAudio.chunks(samples, detector, keepWholeShort = false)
        assertTrue("Voice segmentation discarded the recording", chunks.isNotEmpty())
        val recognizer = ResidentQwenModel.load(context)
        val started = System.nanoTime()
        var text = ""
        for (chunk in chunks) text = BilingualSpeechModel.combine(text, BilingualSpeechModel.decode(recognizer, chunk))
        assertTrue("Missing Chinese: $text", text.contains("中文"))
        assertTrue("Missing English: $text", text.contains("world", true))
        report("streamed_clauses", JSONObject().put("text", text).put("chunks", chunks.size).put("audioMs", samples.size / 16).put("decodeMs", (System.nanoTime() - started) / 1000000))
    }

    @Test fun silenceAndNonSpeechDoNotBecomeHotwords() = runOnWorker {
        val detector = ResidentQwenModel.voiceDetector(context)
        val silence = FloatArray(32000)
        val tone = FloatArray(32000) { i -> (0.01 * kotlin.math.sin(i * 2.0 * Math.PI * 1000 / 16000)).toFloat() }
        assertTrue(SpeechAudio.chunks(silence, detector).isEmpty())
        assertTrue(SpeechAudio.chunks(tone, detector).isEmpty())
        val text = BilingualSpeechModel.transcribe(ResidentQwenModel.load(context), silence, detector, "PageDown, React Native")
        assertEquals("Silence generated a hotword", "", text)
        report("non_speech", JSONObject().put("silenceRejected", true).put("toneRejected", true).put("emptyWithHotwords", true))
    }

    @Test fun softSpeechKeepsBeginningAndEnd() = runOnWorker {
        val samples = pcm("zh").map { it * 0.25f }.toFloatArray()
        val text = BilingualSpeechModel.transcribe(ResidentQwenModel.load(context), samples, ResidentQwenModel.voiceDetector(context))
        assertTrue("Soft speech was discarded: $text", text.contains("今天") && text.contains("中文"))
        report("soft_speech", JSONObject().put("text", text).put("amplitudeScale", 0.25))
    }

    @Test fun customVocabularyUsesQwenContextAndDoesNotLeakToNextUtterance() = runOnWorker {
        val recognizer = ResidentQwenModel.load(context)
        val text = BilingualSpeechModel.decode(recognizer, pcm("mixed-zh-words"), "Chrome, React, Native")
        assertTrue("Vocabulary lost Chinese: $text", text.contains("打开"))
        assertTrue("Vocabulary lost technical terms: $text", text.contains("Chrome", true) && text.contains("React", true))
        val next = BilingualSpeechModel.decode(recognizer, pcm("en"))
        assertTrue("Vocabulary leaked into the next utterance: $next", next.contains("world", true) && next.contains("microphone", true))
        assertFalse("Old vocabulary was inserted into unrelated speech: $next", next.contains("Chrome", true))
        report("qwen_vocabulary", JSONObject().put("text", text).put("nextUtterance", next).put("perStreamContext", true))
    }

    @Test fun enrolledSpeakerMatchesAndOtherSpeakerIsRejected() = runOnWorker {
        val extractor = ResidentQwenModel.speakerExtractor(context)
        val enrollment = (pcm("fangjun-sr-1") + FloatArray(3200) + pcm("fangjun-sr-2") + FloatArray(3200) + pcm("fangjun-sr-3")).copyOf(160000)
        val profile = SpeakerVoiceprint.enroll(extractor, SpeechAudio.chunks(enrollment, ResidentQwenModel.voiceDetector(context), keepWholeShort = false))
        val same = pcm("fangjun-test-sr-1")
        val other = pcm("leijun-test-sr-1")
        val sameScore = SpeakerVoiceprint.similarity(SpeakerVoiceprint.embedding(extractor, same), profile.embedding)
        val otherScore = SpeakerVoiceprint.similarity(SpeakerVoiceprint.embedding(extractor, other), profile.embedding)
        assertTrue("Enrolled speaker rejected: $sameScore", SpeakerVoiceprint.matches(extractor, same, profile))
        assertFalse("Other speaker accepted: $otherScore", SpeakerVoiceprint.matches(extractor, other, profile))
        val detector = ResidentQwenModel.voiceDetector(context)
        val started = System.nanoTime()
        val kept = SpeechAudio.chunks(same, detector) { SpeakerVoiceprint.matches(extractor, it, profile) }
        val rejected = SpeechAudio.chunks(other, detector) { SpeakerVoiceprint.matches(extractor, it, profile) }
        assertTrue("Owner speech was fully filtered", kept.isNotEmpty())
        assertTrue("Other speech reached ASR", rejected.isEmpty())
        report("speaker_filter", JSONObject().put("sameSpeakerScore", sameScore).put("otherSpeakerScore", otherScore)
            .put("filterMs", (System.nanoTime() - started) / 1000000).put("otherSpeakerFullyRejected", true).put("profileStored", false))
    }
}
