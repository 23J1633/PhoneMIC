package com.phonemic

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import kotlin.math.sqrt
import kotlin.math.abs

object SpeakerVoiceprint {
    data class Profile(val embedding: FloatArray, val templates: List<FloatArray> = emptyList())
    const val MODEL = "campplus-zh-en-v1"
    private const val THRESHOLD = 0.55f

    private fun normalize(vector: FloatArray): FloatArray {
        val length = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        require(length.isFinite() && length > 0.00001f) { "Invalid speaker embedding" }
        return FloatArray(vector.size) { vector[it] / length }
    }

    fun embedding(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): FloatArray {
        require(samples.size >= 9600) { "Not enough speech for speaker matching" }
        val stream = extractor.createStream()
        try {
            // Keep very quiet speech away from the embedding frontend's numeric floor.
            // Apply this only after speech detection; ASR and capture retain their original PCM.
            val peak = samples.maxOf { abs(it) }
            val gain = if (peak > 0.0005f) (0.1f / peak).coerceIn(1f, 8f) else 1f
            stream.acceptWaveform(if (gain > 1f) FloatArray(samples.size) { samples[it] * gain } else samples, 16000)
            stream.inputFinished()
            check(extractor.isReady(stream)) { "Not enough speech for speaker matching" }
            return normalize(extractor.compute(stream))
        } finally { stream.release() }
    }

    fun similarity(first: FloatArray, second: FloatArray): Float {
        if (first.size != second.size || first.isEmpty()) return -1f
        val a = normalize(first)
        val b = normalize(second)
        return a.indices.sumOf { a[it].toDouble() * b[it] }.toFloat()
    }

    fun matches(extractor: SpeakerEmbeddingExtractor, samples: FloatArray, profile: Profile): Boolean =
        match(extractor, samples, profile, if (samples.size < 24000) 0.68f else 0.58f, 2)

    // This is a consistency check inside an already-verified parent window, never an
    // independent speaker admission. The parent has already matched two references.
    fun matchesBoundary(extractor: SpeakerEmbeddingExtractor, samples: FloatArray, profile: Profile): Boolean =
        match(extractor, samples, profile, 0.68f, 1)

    private fun match(extractor: SpeakerEmbeddingExtractor, samples: FloatArray, profile: Profile, threshold: Float, references: Int): Boolean {
        if (samples.size < 9600) return false
        require(profile.embedding.size == extractor.dim()) { "Voiceprint model changed; enroll again" }
        val candidate = embedding(extractor, samples)
        if (similarity(candidate, profile.embedding) < threshold) return false
        if (profile.templates.size < 2) return true
        // Full-window admission needs two references; a boundary only confirms that
        // decision. A third delivery must not veto the matching references.
        return profile.templates.count { similarity(candidate, it) >= threshold - 0.03f } >= references
    }

    fun enroll(extractor: SpeakerEmbeddingExtractor, speech: List<FloatArray>): Profile = enrollReferences(extractor, speech, 6, 3)

    fun enrollPhases(extractor: SpeakerEmbeddingExtractor, phases: List<List<FloatArray>>): Profile {
        require(phases.size == 2) { "Expected normal and quiet enrollment phases" }
        val groups = phases.map { enrollReferences(extractor, it, 4, 2) }
        // Each delivery must be internally consistent and both must represent the same person.
        // Avoid requiring every quiet reference to resemble every normal reference equally.
        require(similarity(groups[0].embedding, groups[1].embedding) >= THRESHOLD) { "VOICEPRINT_INCONSISTENT" }
        val vectors = groups.flatMap { it.templates.take(4) }
        val average = normalize(FloatArray(extractor.dim()) { i -> vectors.sumOf { it[i].toDouble() }.toFloat() })
        return Profile(average, vectors)
    }

    private fun enrollReferences(extractor: SpeakerEmbeddingExtractor, speech: List<FloatArray>, minimumSeconds: Int, minimumReferences: Int): Profile {
        require(speech.sumOf { it.size } >= 16000 * minimumSeconds) { "VOICEPRINT_TOO_SHORT" }
        val vectors = ArrayList<FloatArray>()
        for (samples in speech) {
            if (samples.size < 24000) continue
            // Six continuous seconds must produce three references, not a 3 + 3 split
            // that subsequently fails the minimum-template check.
            val count = maxOf(1, samples.size / 32000)
            for (part in 0 until count) {
                vectors.add(embedding(extractor, samples.copyOfRange(samples.size * part / count, samples.size * (part + 1) / count)))
            }
        }
        require(vectors.size >= minimumReferences) { "VOICEPRINT_TOO_SHORT" }
        require(vectors.indices.all { i -> (i + 1 until vectors.size).all { j -> similarity(vectors[i], vectors[j]) >= THRESHOLD } }) { "VOICEPRINT_INCONSISTENT" }
        val average = normalize(FloatArray(extractor.dim()) { i -> vectors.sumOf { it[i].toDouble() }.toFloat() })
        require(vectors.all { similarity(it, average) >= 0.65f }) { "VOICEPRINT_INCONSISTENT" }
        return Profile(average, vectors.take(8))
    }
}
