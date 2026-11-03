package com.phonemic

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Public-fixture amplitude changes exercise enrollment, not real whisper recognition. */
@RunWith(AndroidJUnit4::class)
class VoiceprintEnrollmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun pcm(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i ->
            (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f
        }
    }

    private fun quiet(samples: FloatArray) = FloatArray(samples.size) { samples[it] * 0.03f }

    private fun runOnWorker(work: () -> Unit) {
        ResidentQwenModel.worker.submit { work() }.get(120, TimeUnit.SECONDS)
    }

    private fun expectRejected(code: String, work: () -> Unit) {
        try {
            work()
            fail("Enrollment unexpectedly accepted a $code fixture")
        } catch (error: IllegalArgumentException) {
            assertEquals("Unexpected enrollment rejection", code, error.message)
        }
    }

    @Test fun normalAndQuietPhasesSupportHeldOutOwnerAtBothLevels() = runOnWorker {
        val extractor = ResidentQwenModel.speakerExtractor(context)
        val normal = pcm("fangjun-sr-2")
        val detector = ResidentQwenModel.voiceDetector(context)
        // Match the recorder's per-phase VAD path, rather than treating silence as speech.
        val phases = listOf(normal, quiet(normal)).map {
            SpeechAudio.chunks(it, detector, keepWholeShort = false)
        }
        assertTrue("Normal fixture has insufficient detected speech", phases[0].sumOf { it.size } >= 64000)
        assertTrue("Quiet fixture has insufficient detected speech", phases[1].sumOf { it.size } >= 64000)
        val profile = SpeakerVoiceprint.enrollPhases(extractor, phases)
        assertTrue("Both phases need independent references", profile.templates.size >= 4)
        val heldOut = pcm("fangjun-test-sr-1")
        assertTrue("Normal held-out owner was rejected", SpeakerVoiceprint.matches(extractor, heldOut, profile))
        assertTrue("Attenuated held-out owner was rejected", SpeakerVoiceprint.matches(extractor, quiet(heldOut), profile))
        assertFalse("Other speaker matched the combined profile",
            SpeakerVoiceprint.matches(extractor, pcm("leijun-test-sr-1"), profile))
        report("voiceprint_phase_enrollment", JSONObject()
            .put("normalDetectedMs", phases[0].sumOf { it.size } / 16)
            .put("quietDetectedMs", phases[1].sumOf { it.size } / 16)
            .put("templates", profile.templates.size).put("amplitudeScale", 0.03)
            .put("heldOutNormalAccepted", true).put("heldOutQuietAccepted", true)
            .put("otherRejected", true).put("profileStored", false))
    }

    @Test fun differentSpeakersCannotBeRegisteredAsTwoDeliveryPhases() = runOnWorker {
        val owner = pcm("fangjun-sr-2")
        val other = pcm("leijun-test-sr-1").copyOfRange(0, owner.size)
        expectRejected("VOICEPRINT_INCONSISTENT") {
            SpeakerVoiceprint.enrollPhases(
                ResidentQwenModel.speakerExtractor(context), listOf(listOf(owner), listOf(other)),
            )
        }
        report("voiceprint_cross_phase_rejection", JSONObject().put("otherSpeakerRejected", true))
    }

    @Test fun anotherSpeakerInsideOnePhaseCannotBecomeAReference() = runOnWorker {
        val owner = pcm("fangjun-sr-2")
        val normal = listOf(owner.copyOfRange(0, 48000), pcm("leijun-test-sr-1").copyOfRange(0, 48000))
        expectRejected("VOICEPRINT_INCONSISTENT") {
            SpeakerVoiceprint.enrollPhases(
                ResidentQwenModel.speakerExtractor(context), listOf(normal, listOf(quiet(owner))),
            )
        }
    }

    @Test fun longNormalPhaseCannotCompensateForInsufficientQuietSpeech() = runOnWorker {
        val normal = pcm("fangjun-sr-2")
        val shortQuiet = quiet(normal.copyOfRange(0, 62400))
        expectRejected("VOICEPRINT_TOO_SHORT") {
            SpeakerVoiceprint.enrollPhases(
                ResidentQwenModel.speakerExtractor(context), listOf(listOf(normal), listOf(shortQuiet)),
            )
        }
        report("voiceprint_short_phase_rejection", JSONObject()
            .put("normalMs", normal.size / 16).put("quietMs", shortQuiet.size / 16)
            .put("quietPhaseRequired", true))
    }

    @Test fun sixSecondSingleChunkProducesEnoughReferences() = runOnWorker {
        val normal = pcm("fangjun-sr-2")
        // This is a synthetic length-boundary fixture, not a six-second continuous recording.
        val sixSeconds = normal + normal.copyOfRange(0, 96000 - normal.size)
        assertEquals(96000, sixSeconds.size)
        val profile = SpeakerVoiceprint.enroll(ResidentQwenModel.speakerExtractor(context), listOf(sixSeconds))
        assertTrue("Six seconds met the duration rule but still failed reference coverage", profile.templates.size >= 3)
        report("voiceprint_six_second_boundary", JSONObject()
            .put("samples", sixSeconds.size).put("templates", profile.templates.size)
            .put("syntheticSplice", true).put("profileStored", false))
    }

    private fun report(name: String, data: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply { putString(name, data.toString()) })
    }
}
