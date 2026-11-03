package com.phonemic

/** A capture-thread-only PCM window; VAD framing continues across committed segments. */
class SpeechCaptureWindow(val capacity: Int = 320000) {
    companion object {
        private const val VAD_FRAME_SAMPLES = 512
        private const val MIN_VOICE_SAMPLES = 1600
        private const val END_SILENCE_SAMPLES = 11200
        private const val PRE_ROLL_SAMPLES = 4000
        private const val MAX_APPEND_SAMPLES = 1600
    }

    init { require(capacity > 0) { "Capture capacity must be positive" } }

    private val buffer = FloatArray(capacity)
    private val vadFrame = FloatArray(VAD_FRAME_SAMPLES)
    private var vadUsed = 0
    private var voiced = 0

    var used: Int = 0
        private set
    var voiceEnd: Int = 0
        private set
    val hasSpeech: Boolean get() = voiced >= MIN_VOICE_SAMPLES
    // Require stronger evidence to start than to continue already-confirmed speech.
    val voiceThreshold: Float get() = if (hasSpeech) 0.20f else 0.35f
    val shouldCommit: Boolean
        get() = hasSpeech && (used == capacity || used - voiceEnd >= END_SILENCE_SAMPLES)

    /** The caller commits/resets a full window before appending another read. */
    fun append(samples: ShortArray, count: Int, isVoice: (FloatArray) -> Boolean) {
        require(count in 0..minOf(samples.size, MAX_APPEND_SAMPLES)) { "Invalid capture read length: $count" }
        require(count <= capacity - used) { "Capture window must be committed before it overflows" }
        for (i in 0 until count) {
            val value = samples[i] / 32768f
            buffer[used++] = value
            vadFrame[vadUsed++] = value
            if (vadUsed == VAD_FRAME_SAMPLES) {
                if (isVoice(vadFrame)) {
                    // A quiet interval and a new voice frame can share the same AudioRecord read.
                    if (voiced in 1 until MIN_VOICE_SAMPLES &&
                        used - VAD_FRAME_SAMPLES - voiceEnd >= END_SILENCE_SAMPLES
                    ) trimIdle()
                    // A frame spanning reset() only contributes samples in the current segment.
                    voiced += minOf(VAD_FRAME_SAMPLES, used)
                    voiceEnd = used
                }
                vadUsed = 0
            }
        }
    }

    fun snapshot(): FloatArray = buffer.copyOf(used)

    /** Retain an unfinished VAD frame, but never duplicate it in the next audio snapshot. */
    fun reset() {
        used = 0
        voiced = 0
        voiceEnd = 0
    }

    fun trimIdle() {
        if (hasSpeech || (voiced > 0 && used - voiceEnd < END_SILENCE_SAMPLES)) return
        val keep = minOf(used, PRE_ROLL_SAMPLES)
        if (keep < used) buffer.copyInto(buffer, 0, used - keep, used)
        used = keep
        voiced = 0
        voiceEnd = 0
    }
}
