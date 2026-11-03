package com.phonemic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.facebook.react.bridge.ReactApplicationContext
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.Vad
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

/** Public fixtures are diagnostic; the user's saved profile is never modified or printed. */
@RunWith(AndroidJUnit4::class)
class VoiceprintRobustnessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private data class Span(val start: Int, val end: Int)
    private data class Match(val accepted: Boolean, val details: JSONObject)

    private fun report(key: String, data: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply { putString(key, data.toString()) })
        System.out.println("$key=$data")
    }

    private fun pcm(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
        }
    }

    private fun voiceSpans(samples: FloatArray, detector: Vad): List<Span> {
        detector.reset()
        val spans = ArrayList<Span>()
        fun drain() {
            while (!detector.empty()) {
                val speech = detector.front()
                val start = speech.start.toInt().coerceAtLeast(0)
                val end = (start + speech.samples.size).coerceAtMost(samples.size)
                if (end > start) spans.add(Span(start, end))
                detector.pop()
            }
        }
        var offset = 0
        while (offset < samples.size) {
            val frame = FloatArray(512)
            val count = minOf(frame.size, samples.size - offset)
            samples.copyInto(frame, 0, offset, offset + count)
            detector.acceptWaveform(frame)
            drain()
            offset += count
        }
        detector.flush()
        drain()
        return spans
    }

    private fun rawEmbedding(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally { stream.release() }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val length = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        check(length.isFinite() && length > 0.00001f) { "Invalid public-fixture embedding" }
        return FloatArray(vector.size) { vector[it] / length }
    }

    private fun legacyProfile(extractor: SpeakerEmbeddingExtractor, speech: List<FloatArray>): SpeakerVoiceprint.Profile {
        // Freeze the previous enrollment algorithm so both APKs match the same saved-style profile.
        check(speech.sumOf { it.size } >= 96000) { "Public enrollment is too short" }
        val vectors = ArrayList<FloatArray>()
        for (samples in speech) {
            var offset = 0
            while (samples.size - offset >= 24000) {
                val end = if (samples.size - offset <= 72000) samples.size else offset + 48000
                vectors.add(normalize(checkNotNull(rawEmbedding(extractor, samples.copyOfRange(offset, end)))))
                offset = end
            }
        }
        check(vectors.size >= 3) { "Public enrollment has too few legacy templates" }
        check(vectors.indices.all { i -> (i + 1 until vectors.size).all { j ->
            SpeakerVoiceprint.similarity(vectors[i], vectors[j]) >= 0.55f
        } }) { "Public legacy templates are inconsistent" }
        val average = normalize(FloatArray(extractor.dim()) { i -> vectors.sumOf { it[i].toDouble() }.toFloat() })
        check(vectors.all { SpeakerVoiceprint.similarity(it, average) >= 0.65f }) {
            "Public legacy center is inconsistent"
        }
        return SpeakerVoiceprint.Profile(average, vectors.take(8))
    }

    private fun match(
        extractor: SpeakerEmbeddingExtractor,
        samples: FloatArray,
        profile: SpeakerVoiceprint.Profile,
        boundary: Boolean = false,
    ): Match {
        // Use production preprocessing whenever matching is allowed. Below its 600 ms
        // boundary, inspect the extractor directly without changing the hard rejection.
        val candidate = if (samples.size >= 9600) SpeakerVoiceprint.embedding(extractor, samples)
            else rawEmbedding(extractor, samples)
        val center = candidate?.let { SpeakerVoiceprint.similarity(it, profile.embedding) }
        val templateScores = candidate?.let { embedding ->
            profile.templates.map { SpeakerVoiceprint.similarity(embedding, it) }
        } ?: emptyList()
        val started = System.nanoTime()
        val accepted = if (boundary) SpeakerVoiceprint.matchesBoundary(extractor, samples, profile)
            else SpeakerVoiceprint.matches(extractor, samples, profile)
        val elapsed = (System.nanoTime() - started) / 1000000.0
        val details = JSONObject()
            .put("samples", samples.size)
            .put("durationMs", samples.size / 16.0)
            .put("score", center ?: JSONObject.NULL)
            .put("scoreUsesProductionEmbedding", samples.size >= 9600)
            .put("templateScores", JSONArray(templateScores))
            .put("decisionMode", if (boundary) "authenticated_parent_boundary" else "primary")
            .put("matches", accepted)
            .put("matchesMs", elapsed)
            .put("belowApplicationMinimum", samples.size < 9600)
        if (accepted && samples.size >= 24000) {
            val localStarted = System.nanoTime()
            val localSize = minOf(16000, samples.size / 2)
            val head = SpeakerVoiceprint.embedding(extractor, samples.copyOfRange(0, localSize))
            val tail = SpeakerVoiceprint.embedding(extractor, samples.copyOfRange(samples.size - localSize, samples.size))
            details.put("localWindowSamples", localSize)
                .put("headCenterScore", SpeakerVoiceprint.similarity(head, profile.embedding))
                .put("tailCenterScore", SpeakerVoiceprint.similarity(tail, profile.embedding))
                .put("headTemplateScores", JSONArray(profile.templates.map { SpeakerVoiceprint.similarity(head, it) }))
                .put("tailTemplateScores", JSONArray(profile.templates.map { SpeakerVoiceprint.similarity(tail, it) }))
                .put("headTailSimilarity", SpeakerVoiceprint.similarity(head, tail))
                .put("localDiagnosticMs", (System.nanoTime() - localStarted) / 1000000.0)
        }
        return Match(accepted, details)
    }

    private fun locateGateInput(source: FloatArray, input: FloatArray, from: Int): Span {
        check(input.isNotEmpty()) { "Speaker gate received an empty verification window" }
        val middle = input.size / 2
        for (start in from..(source.size - input.size)) {
            if (source[start].toRawBits() != input[0].toRawBits() ||
                source[start + middle].toRawBits() != input[middle].toRawBits() ||
                source[start + input.lastIndex].toRawBits() != input.last().toRawBits()
            ) continue
            var equal = true
            for (i in input.indices) {
                if (source[start + i].toRawBits() != input[i].toRawBits()) {
                    equal = false
                    break
                }
            }
            if (equal) return Span(start, start + input.size)
        }
        throw AssertionError("Speaker audio could not be located exactly in the source PCM")
    }

    private fun measure(
        label: String,
        samples: FloatArray,
        extractor: SpeakerEmbeddingExtractor,
        detector: Vad,
        profile: SpeakerVoiceprint.Profile,
        metadata: JSONObject = JSONObject(),
        ownerSpan: Span? = null,
    ): Match {
        val direct = match(extractor, samples, profile)
        val spans = voiceSpans(samples, detector)
        val primarySpans = ArrayList<Span>()
        var gateInputSamples = 0
        var acceptedInputSamples = 0
        var boundaryProbeCount = 0
        var boundaryProbeSamples = 0
        var retainedOwnerSamples = 0
        var retainedOtherSamples = 0
        val gates = JSONArray()
        val started = System.nanoTime()
        fun inspect(input: FloatArray, boundaryProbe: Boolean): Boolean {
            // Boundary probes revisit a previously checked window; their source positions
            // need not be monotonic and must not count as another retained audio interval.
            val span = locateGateInput(samples, input, 0)
            if (boundaryProbe) {
                check(primarySpans.any { span.start >= it.start && span.end <= it.end }) {
                    "Boundary verification must revisit a previously inspected parent window"
                }
                boundaryProbeCount++
                boundaryProbeSamples += input.size
            } else {
                primarySpans.add(span)
                gateInputSamples += input.size
            }
            val decision = match(extractor, input, profile, boundary = boundaryProbe)
            if (!boundaryProbe && decision.accepted) acceptedInputSamples += input.size
            decision.details.put("startSample", span.start).put("endSampleExclusive", span.end)
                .put("boundaryProbe", boundaryProbe)
            if (!boundaryProbe && ownerSpan != null && decision.accepted) {
                val ownerSamples = (minOf(span.end, ownerSpan.end) - maxOf(span.start, ownerSpan.start)).coerceAtLeast(0)
                retainedOwnerSamples += ownerSamples
                retainedOtherSamples += input.size - ownerSamples
            }
            gates.put(decision.details)
            return decision.accepted
        }
        val kept = SpeechAudio.chunks(samples, detector,
            acceptBoundary = { inspect(it, true) },
        ) { inspect(it, false) }
        val filterElapsed = (System.nanoTime() - started) / 1000000.0
        val result = JSONObject()
            .put("case", label)
            .put("metadata", metadata)
            .put("raw", direct.details)
            .put("peak", samples.maxOfOrNull { abs(it) } ?: 0f)
            .put("inputSamples", samples.size)
            .put("vadCoreSamples", spans.sumOf { it.end - it.start })
            .put("vadSegments", spans.size)
            .put("gateInputSamples", gateInputSamples)
            .put("acceptedGateInputSamples", acceptedInputSamples)
            .put("boundaryProbeCount", boundaryProbeCount)
            .put("boundaryProbeSamples", boundaryProbeSamples)
            .put("outputSamplesIncludingPadding", kept.sumOf { it.size })
            .put("outputChunks", kept.size)
            .put("filterDiagnosticMs", filterElapsed)
            .put("gates", gates)
            .put("scope", "Diagnostic scoring adds an extra embedding per gate; timings are not production latency")
        var validateMixedOutput = false
        var finalOwnerSamples = 0
        var finalOtherSamples = 0
        if (ownerSpan != null) {
            val expectsOriginalAudio = try {
                Class.forName("com.phonemic.SpeakerAudioRanges", false, context.classLoader)
                true
            } catch (_: ClassNotFoundException) { false }
            var outputOwnerSamples = 0
            var outputOtherSamples = 0
            var previousOutputEnd = 0
            var legacyZeroPadding = false
            for (output in kept) {
                val outputSpan = try {
                    locateGateInput(samples, output, previousOutputEnd)
                } catch (error: AssertionError) {
                    if (expectsOriginalAudio) throw AssertionError("ASR output must match original PCM in the new speaker-range implementation", error)
                    // Old APKs surrounded accepted audio with exactly 250 ms of zeros.
                    val hasLegacyPadding = output.size > 8000 &&
                        (0 until 4000).all { output[it] == 0f } &&
                        (output.size - 4000 until output.size).all { output[it] == 0f }
                    if (!hasLegacyPadding) throw AssertionError("ASR output is neither original PCM nor recognized legacy zero padding", error)
                    legacyZeroPadding = true
                    null
                } ?: continue
                previousOutputEnd = outputSpan.end
                val ownerSamples = (minOf(outputSpan.end, ownerSpan.end) - maxOf(outputSpan.start, ownerSpan.start)).coerceAtLeast(0)
                outputOwnerSamples += ownerSamples
                outputOtherSamples += outputSpan.end - outputSpan.start - ownerSamples
            }
            validateMixedOutput = expectsOriginalAudio
            finalOwnerSamples = outputOwnerSamples
            finalOtherSamples = outputOtherSamples
            result.put("ownerSourceSpan", JSONArray(listOf(ownerSpan.start, ownerSpan.end)))
                .put("retainedOwnerSourceSamples", retainedOwnerSamples)
                .put("retainedOtherSourceSamples", retainedOtherSamples)
                .put("sourceRetentionScope", "Initially accepted primary windows before boundary probes; output retention is authoritative")
                .put("outputOwnerSourceSamples", if (legacyZeroPadding) JSONObject.NULL else outputOwnerSamples)
                .put("outputOtherSourceSamples", if (legacyZeroPadding) JSONObject.NULL else outputOtherSamples)
                .put("legacyZeroPadding", legacyZeroPadding)
                .put("outputRetentionScope", "Actual ASR input mapped to original PCM, including restored context")
        }
        report("voiceprint_robustness", result)
        if (validateMixedOutput) {
            assertEquals("Known mixed fixture leaked other-speaker audio to ASR: $label", 0, finalOtherSamples)
            assertTrue("Known mixed fixture discarded all owner audio: $label", finalOwnerSamples > 0)
        }
        return direct
    }

    @Test fun publicFixtureGainDurationAndMixedSpeakerMatrix() {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ResidentQwenModel.worker.submit {
            val extractor = ResidentQwenModel.speakerExtractor(context)
            val detector = ResidentQwenModel.voiceDetector(context)
            // Keep enrollment identical to the existing device regression, including its 10 s cap.
            val enrollment = (pcm("fangjun-sr-1") + FloatArray(3200) + pcm("fangjun-sr-2") +
                FloatArray(3200) + pcm("fangjun-sr-3")).copyOf(160000)
            val profile = legacyProfile(extractor,
                SpeechAudio.chunks(enrollment, detector, keepWholeShort = false))
            val sources = linkedMapOf("owner" to pcm("fangjun-test-sr-1"), "other" to pcm("leijun-test-sr-1"))
            val speechStarts = sources.mapValues { (_, samples) -> voiceSpans(samples, detector).firstOrNull()?.start ?: 0 }
            for ((speaker, original) in sources) {
                for (gain in listOf(1f, 0.1f, 0.03f, 0.01f)) {
                    val samples = FloatArray(original.size) { original[it] * gain }
                    val peak = samples.maxOfOrNull { abs(it) } ?: 0f
                    val recovery = if (peak > 0.0005f && peak < 0.1f) minOf(8f, 0.1f / peak) else 1f
                    val direct = measure("${speaker}_gain", samples, extractor, detector, profile,
                        JSONObject().put("speaker", speaker).put("gain", gain.toDouble()).put("recoveryGain", 1))
                    if (gain == 1f) {
                        if (speaker == "owner") assertTrue("Clean held-out owner must match", direct.accepted)
                        else assertFalse("Clean held-out impostor must be rejected", direct.accepted)
                    }
                    measure("${speaker}_gain_recovered", FloatArray(samples.size) { samples[it] * recovery },
                        extractor, detector, profile, JSONObject().put("speaker", speaker)
                            .put("gain", gain.toDouble()).put("rawPeak", peak.toDouble())
                            .put("recoveryGain", recovery.toDouble()).put("simulatesWhisper", false))
                }
                val start = speechStarts.getValue(speaker)
                for (durationMs in listOf(590, 610, 1490, 1510, 3000)) {
                    val end = minOf(original.size, start + durationMs * 16)
                    measure("${speaker}_duration", original.copyOfRange(start, end), extractor, detector, profile,
                        JSONObject().put("speaker", speaker).put("requestedMs", durationMs)
                            .put("sourceStartSample", start).put("sourceEndSampleExclusive", end)
                            .put("cropOrigin", "First clean VAD segment start"))
                }
            }
            for (seconds in listOf(2, 3)) {
                val parts = sources.mapValues { (speaker, samples) ->
                    val start = speechStarts.getValue(speaker)
                    samples.copyOfRange(start, minOf(samples.size, start + seconds * 16000))
                }
                val owner = parts.getValue("owner")
                val other = parts.getValue("other")
                measure("owner_then_other", owner + other, extractor, detector, profile,
                    JSONObject().put("secondsPerSpeaker", seconds).put("turnBoundarySample", owner.size),
                    Span(0, owner.size))
                measure("other_then_owner", other + owner, extractor, detector, profile,
                    JSONObject().put("secondsPerSpeaker", seconds).put("turnBoundarySample", other.size),
                    Span(other.size, other.size + owner.size))
            }
        }.get(300, TimeUnit.SECONDS)
    }

    private fun readStore(module: PhoneMicModule, key: String): String? {
        val done = CountDownLatch(1)
        val value = AtomicReference<String?>(null)
        val failed = AtomicBoolean(false)
        module.getStore(key, PromiseImpl(
            Callback { args -> value.set(args.firstOrNull() as? String); done.countDown() },
            Callback { failed.set(true); done.countDown() },
        ))
        check(done.await(10, TimeUnit.SECONDS)) { "Read-only profile query timed out" }
        check(!failed.get()) { "Read-only profile query failed" }
        return value.get()
    }

    @Test fun readExistingVoiceProfileSummaryOnly() {
        val live = (context.applicationContext as MainApplication).reactHost.currentReactContext as? ReactApplicationContext
        assumeTrue("An existing React context is required for the read-only profile query", live != null)
        val module = live!!.getNativeModule("PhoneMic") as? PhoneMicModule
        assumeTrue("The existing PhoneMic module is unavailable", module != null)
        val prefs = context.getSharedPreferences("phonemic.v1", Context.MODE_PRIVATE)
        val hasStoredValues = prefs.contains("voiceProfile") || prefs.contains("settings")
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // getStore creates a missing key when encrypted data exists; do not allow that side effect.
        assumeTrue("Existing encrypted storage has no key; skipping without creating one",
            !hasStoredValues || keys.containsAlias("phonemic.storage"))
        fun parse(raw: String?): JSONObject? {
            if (raw == null || raw == "null") return null
            return try { JSONObject(raw) } catch (_: Exception) {
                throw AssertionError("Stored diagnostic metadata is not valid JSON")
            }
        }
        val profile = parse(readStore(module!!, "voiceProfile"))
        val settings = parse(readStore(module, "settings"))
        val templates = profile?.optJSONArray("templates")
        val vectors = ArrayList<FloatArray>()
        if (templates != null) for (i in 0 until templates.length()) {
            val vector = templates.optJSONArray(i) ?: continue
            vectors.add(FloatArray(vector.length()) { j -> vector.optDouble(j, Double.NaN).toFloat() })
        }
        val pairwise = ArrayList<Float>()
        for (i in vectors.indices) for (j in i + 1 until vectors.size) {
            val score = try { SpeakerVoiceprint.similarity(vectors[i], vectors[j]) } catch (_: Exception) { null }
            if (score != null && score.isFinite()) pairwise.add(score)
        }
        val duration = profile?.optDouble("durationMs", Double.NaN)
        report("voiceprint_profile_summary", JSONObject()
            .put("model", profile?.optString("model") ?: JSONObject.NULL)
            .put("templatesCount", templates?.length() ?: 0)
            .put("durationMs", if (duration != null && duration.isFinite()) duration else JSONObject.NULL)
            .put("filterEnabled", profile != null && settings?.optBoolean("voiceFilter", false) == true)
            .put("templatePairwiseMin", pairwise.minOrNull() ?: JSONObject.NULL)
            .put("templatePairwiseMean", if (pairwise.isEmpty()) JSONObject.NULL else pairwise.map { it.toDouble() }.average()))
        // Deliberately do not invalidate the live module or call any storage mutation API.
    }
}
