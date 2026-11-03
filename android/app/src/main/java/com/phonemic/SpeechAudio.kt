package com.phonemic

import com.k2fsa.sherpa.onnx.Vad

object SpeechAudio {
    private const val RATE = 16000
    private const val MAX_CHUNK = 24 * RATE

    private fun substantialSpeech(samples: FloatArray, detector: Vad): Boolean {
        detector.reset()
        var voiced = 0
        var offset = 0
        while (offset + 512 <= samples.size) {
            if (detector.compute(samples.copyOfRange(offset, offset + 512)) >= 0.35f) voiced += 512
            offset += 512
        }
        // Silence at the end of a phrase is not evidence of a different speaker.
        return voiced >= RATE * 3 / 10
    }

    fun chunks(samples: FloatArray, detector: Vad, keepWholeShort: Boolean = true, acceptBoundary: ((FloatArray) -> Boolean)? = null, accept: ((FloatArray) -> Boolean)? = null): List<FloatArray> {
        if (samples.isEmpty()) return emptyList()
        detector.reset()
        val speech = ArrayList<IntRange>()
        fun drain() {
            while (!detector.empty()) {
                val segment = detector.front()
                val segmentStart = segment.start.toInt().coerceAtLeast(0)
                val segmentEnd = (segmentStart + segment.samples.size).coerceAtMost(samples.size)
                if (segmentEnd > segmentStart) speech.add(segmentStart until segmentEnd)
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
        var allAccepted = true
        val ranges = if (accept != null) SpeakerAudioRanges.select(samples, speech,
            boundaryAccept = { !substantialSpeech(it, detector) || (acceptBoundary ?: accept)(it) },
        ) {
            accept(it).also { accepted -> if (!accepted) allAccepted = false }
        } else speech.map {
            (it.first - RATE / 4).coerceAtLeast(0) until (it.last + 1 + RATE / 3).coerceAtMost(samples.size)
        }
        if (ranges.isEmpty()) return emptyList()
        // If every detected region belongs to the owner, retain the full utterance context
        // and decode it once. A rejected region disables this path for the entire window.
        if (allAccepted && keepWholeShort && samples.size <= MAX_CHUNK) return listOf(samples)
        val merged = ArrayList<IntRange>()
        for (range in ranges) {
            val previous = merged.lastOrNull()
            val allowedGap = if (accept != null) 1 else if (keepWholeShort) 4 * RATE else RATE / 4
            if (previous != null && range.last - previous.first + 1 <= MAX_CHUNK && range.first - previous.last <= allowedGap) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                if (previous != null && previous.last >= range.first) {
                    val boundary = (previous.last + range.first) / 2
                    merged[merged.lastIndex] = previous.first until boundary
                    merged.add(boundary..range.last)
                } else merged.add(range)
            }
        }
        return merged.map { range -> samples.copyOfRange(range.first, range.last + 1) }
    }
}
