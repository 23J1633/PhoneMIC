package com.phonemic

/** Keeps ASR context separate from the audio used to verify a speaker. */
object SpeakerAudioRanges {
    private const val RATE = 16000

    fun select(samples: FloatArray, speech: List<IntRange>, boundaryAccept: ((FloatArray) -> Boolean)? = null, accept: (FloatArray) -> Boolean): List<IntRange> {
        val selected = ArrayList<IntRange>()
        for ((index, range) in speech.withIndex()) {
            val size = range.last - range.first + 1
            // Balanced windows avoid a tiny trailing fragment with unreliable speaker evidence.
            val count = maxOf(1, size / (RATE * 3 / 2))
            val windows = (0 until count).map { part ->
                (range.first + size * part / count) until (range.first + size * (part + 1) / count)
            }
            val decisions = windows.map { accept(samples.copyOfRange(it.first, it.last + 1)) }
            for ((part, window) in windows.withIndex()) {
                if (!decisions[part]) continue
                // A neighboring rejection is evidence of a speaker transition. Check the
                // accepted side of that boundary instead of trusting its whole-window average.
                if (boundaryAccept != null && window.last - window.first + 1 >= RATE * 3 / 2) {
                    if (part > 0 && !decisions[part - 1] &&
                        !boundaryAccept(samples.copyOfRange(window.first, window.first + RATE))) continue
                    if (part < windows.lastIndex && !decisions[part + 1] &&
                        !boundaryAccept(samples.copyOfRange(window.last + 1 - RATE, window.last + 1))) continue
                }
                // Restore real leading/trailing audio only outside this VAD region. Do not
                // pad across a rejected neighboring window or another detected speaker.
                val leftLimit = if (index == 0) 0 else (speech[index - 1].last + 1 + range.first) / 2
                val rightLimit = if (index == speech.lastIndex) samples.size else (range.last + 1 + speech[index + 1].first) / 2
                val start = if (part == 0) (window.first - RATE / 4).coerceAtLeast(leftLimit) else window.first
                val end = if (part == windows.lastIndex) (window.last + 1 + RATE / 3).coerceAtMost(rightLimit) else window.last + 1
                selected.add(start until end)
            }
        }
        return selected
    }
}
