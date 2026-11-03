package com.phonemic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.Vad
import android.util.Log
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

class OfflineSpeech(private val context: Context, private val emit: (WritableMap) -> Unit) {
    private class Session(val id: String, val streaming: Boolean, val hotwords: String, val speakerProfile: SpeakerVoiceprint.Profile?, val background: Boolean) {
        @Volatile var recording = true
        @Volatile var cancelled = false
        @Volatile var lastText = ""
        val queue = SpeechDecodeQueue()
        var committed = ""
        var decodedSegment = -1
        var decodedVoiceEnd = -1
        var decodedSamples = -1
        var decodedText = ""
        var decodedRejected = false
        var committedRejected = false
    }
    private val inference = ResidentQwenModel.worker
    private val capture = Executors.newSingleThreadExecutor()
    private val active = AtomicReference<Session?>(null)
    @Volatile private var closed = false
    // Only the capture executor touches this detector; decoding has its own instance.
    private var captureDetector: Vad? = null
    private var closeTask: Future<*>? = null
    private fun detector(): Vad = captureDetector ?: ResidentQwenModel.newVoiceDetector(context).also { captureDetector = it }

    private fun state(value: String, session: String = "", message: String = "") {
        emit(Arguments.createMap().apply {
            putString("type", "state"); putString("state", value)
            putString("session", session); putString("message", message)
        })
    }
    private fun transcript(session: Session, text: String, final: Boolean) {
        if (session.cancelled || closed || active.get() !== session) return
        session.lastText = text
        emit(Arguments.createMap().apply {
            putString("type", "transcript"); putString("session", session.id)
            putString("text", text); putBoolean("final", final)
        })
    }
    private fun load(language: String) {
        require(language in listOf("auto", "zh-CN", "en-US")) { "Unsupported language" }
        if (closed) return
        if (!ResidentQwenModel.isReady()) state("loading")
        ResidentQwenModel.load(context)
        state("ready")
    }
    fun prepare(language: String, promise: Promise) {
        if (active.get() != null) { promise.reject("BUSY", "Recording in progress"); return }
        inference.execute {
            try {
                check(!closed) { "Speech engine closed" }
                load(language)
                capture.execute {
                    try { check(!closed); detector(); promise.resolve(true) }
                    catch (e: Exception) { promise.reject("MODEL", e.message, e) }
                }
            }
            catch (e: Exception) { state("error", message = e.message ?: "Model unavailable"); promise.reject("MODEL", e.message, e) }
        }
    }
    fun isActive() = active.get() != null
    fun start(language: String, id: String, promise: Promise, streaming: Boolean = false, hotwords: String = "", speakerProfile: SpeakerVoiceprint.Profile? = null, background: Boolean = false) {
        if (language !in listOf("auto", "zh-CN", "en-US")) { promise.reject("SPEECH", "Unsupported language"); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            promise.reject("MIC_PERMISSION", "Microphone permission required"); return
        }
        val words = hotwords.split(Regex("[,，;；\\n]+"))
            .map { it.trim().take(40) }.filter { it.isNotEmpty() }.distinct().take(30).joinToString(", ").take(300)
        val session = Session(id, streaming, words, speakerProfile, background)
        if (closed || !active.compareAndSet(null, session)) { promise.reject("BUSY", "Recording in progress"); return }
        // A cancelled decode can still be inside ONNX. A warm microphone must not wait for it.
        if (ResidentQwenModel.isReady()) {
            try { capture.execute { record(session, promise) } }
            catch (e: Exception) { active.compareAndSet(session, null); promise.reject("SPEECH", e.message, e) }
            return
        }
        inference.execute {
            try {
                load(language)
                if (!session.recording || session.cancelled || closed) {
                    active.compareAndSet(session, null)
                    promise.reject("CANCELLED", "Recording cancelled")
                } else capture.execute { record(session, promise) }
            } catch (e: Exception) {
                active.compareAndSet(session, null)
                promise.reject("SPEECH", e.message, e)
            }
        }
    }
    private fun enqueue(session: Session, job: SpeechDecodeJob) {
        if (session.cancelled || closed) return
        if (session.queue.commit(job)) drain(session)
    }
    private fun drain(session: Session) {
        inference.execute {
            while (true) {
                if (session.cancelled || closed) { session.queue.cancel(); break }
                val next = session.queue.next() ?: break
                try {
                    val started = System.nanoTime()
                    var rejected = false
                    val cacheHit = canReuseSpeechPreview(session.decodedSegment, session.decodedVoiceEnd, session.decodedSamples, next)
                    val result = if (next.samples.isEmpty()) "" else if (cacheHit) {
                        rejected = session.decodedRejected
                        session.decodedText
                    } else {
                        val recognizer = ResidentQwenModel.load(context)
                        var boundary: ((FloatArray) -> Boolean)? = null
                        val gate: ((FloatArray) -> Boolean)? = session.speakerProfile?.let { profile ->
                            val extractor = ResidentQwenModel.speakerExtractor(context)
                            boundary = { audio -> SpeakerVoiceprint.matchesBoundary(extractor, audio, profile).also { if (!it) rejected = true } }
                            val predicate: (FloatArray) -> Boolean = { audio -> SpeakerVoiceprint.matches(extractor, audio, profile).also { if (!it) rejected = true } }
                            predicate
                        }
                        if (gate != null) BilingualSpeechModel.transcribe(
                            recognizer, next.samples, ResidentQwenModel.voiceDetector(context), session.hotwords, acceptBoundary = boundary, accept = gate,
                        ) else BilingualSpeechModel.decode(recognizer, next.samples, session.hotwords)
                    }
                    val durationMs = (System.nanoTime() - started) / 1000000
                    session.queue.decoded(next, SystemClock.elapsedRealtime(), durationMs)
                    if (session.cancelled || closed) continue
                    session.decodedSegment = next.segment; session.decodedVoiceEnd = next.voiceEnd
                    session.decodedSamples = next.samples.size
                    session.decodedText = result; session.decodedRejected = rejected
                    val combined = BilingualSpeechModel.combine(session.committed, result)
                    val text = if (next.final) BilingualSpeechModel.correct(combined, session.hotwords) else combined
                    if (next.commit && !next.final) {
                        session.committed = text
                        session.committedRejected = session.committedRejected || rejected
                    }
                    // The final tail can be empty after an earlier committed segment was rejected.
                    if (next.final && (rejected || session.committedRejected) && text.isBlank()) emit(Arguments.createMap().apply { putString("type", "filtered"); putString("session", session.id) })
                    if (text != session.lastText || next.final) transcript(session, text, next.final)
                    Log.i("PhoneMicSpeech", "Local Qwen decode: audioMs=${next.samples.size / 16}, decodeMs=${(System.nanoTime() - started) / 1000000}, final=${next.final}, cached=$cacheHit")
                    if (next.final) {
                        if (active.compareAndSet(session, null)) state("stopped", session.id)
                    }
                } catch (e: Exception) {
                    if (session.cancelled || closed || active.get() !== session) continue
                    Log.e("PhoneMicSpeech", "Local recognition failed", e)
                    session.recording = false
                    state("error", session.id, e.message ?: "Speech recognition failed")
                    transcript(session, session.lastText, true)
                    session.cancelled = true
                    session.queue.cancel()
                    active.compareAndSet(session, null)
                    state("stopped", session.id)
                }
            }
        }
    }
    private fun record(session: Session, promise: Promise) {
        var audio: AudioRecord? = null
        var input: MicrophoneInput? = null
        var resolved = false
        var total = 0L
        var peak = 0
        val window = SpeechCaptureWindow()
        var segment = 0
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            if (!session.recording || session.cancelled || closed) {
                active.compareAndSet(session, null); promise.reject("CANCELLED", "Recording cancelled"); return
            }
            input = MicrophoneInput.open(context) { session.recording = false }
            audio = input.recorder
            val detector = detector().also { it.reset() }
            if (!session.recording || session.cancelled || closed) { promise.reject("CANCELLED", "Recording cancelled"); return }
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone is in use" }
            state("recording", session.id); promise.resolve(true); resolved = true
            Log.i("PhoneMicSpeech", "Microphone capture started: local Qwen3-ASR-0.6B")
            val samples = ShortArray(1600)
            var previousInput = ""
            while (session.recording && !session.cancelled && !closed) {
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                check(count >= 0) { "Microphone read failed: $count" }
                if (count == 0) continue
                val source = if (input.isHeadsetRouted()) "headset" else "phone"
                if (source != previousInput) {
                    previousInput = source
                    emit(Arguments.createMap().apply {
                        putString("type", "inputSource"); putString("session", session.id); putString("source", source)
                        putBoolean("fallback", input.headsetRequested && source == "phone")
                    })
                    Log.i("PhoneMicAudio", "Recording input: $source")
                }
                total += count
                if (window.used + count > window.capacity) {
                    if (window.hasSpeech) enqueue(session, SpeechDecodeJob(window.snapshot(), segment++, true, false, window.voiceEnd))
                    window.reset()
                }
                var power = 0.0
                val now = SystemClock.elapsedRealtime()
                window.append(samples, count) { detector.compute(it) >= window.voiceThreshold }
                for (i in 0 until count) {
                    val value = samples[i].toDouble()
                    power += value * value
                    peak = maxOf(peak, kotlin.math.abs(samples[i].toInt()))
                }
                val rms = sqrt(power / count)
                val db = 20.0 * log10(maxOf(rms / 32768.0, 0.00001))
                emit(Arguments.createMap().apply {
                    putString("type", "level"); putString("session", session.id)
                    putDouble("value", ((db + 60) / 54).coerceIn(0.0, 1.0))
                })
                if (window.shouldCommit) {
                    enqueue(session, SpeechDecodeJob(window.snapshot(), segment++, true, false, window.voiceEnd))
                    window.reset()
                } else if (session.streaming && window.hasSpeech && window.used >= 16000) {
                    if (session.queue.preview(now, segment, window.voiceEnd, ResidentQwenModel.isBusy()) { window.snapshot() }) drain(session)
                }
                window.trimIdle()
                if (total >= 16000L * 600) session.recording = false
            }
        } catch (e: Exception) {
            Log.e("PhoneMicSpeech", "Microphone capture failed", e)
            if (!resolved) promise.reject("SPEECH", e.message, e)
            if (!session.cancelled) state("error", session.id, e.message ?: "Microphone unavailable")
        } finally {
            session.recording = false
            Log.i("PhoneMicSpeech", "Microphone capture stopped: samples=$total, peak=$peak")
            try {
                if (session.cancelled || closed || !resolved) {
                    active.compareAndSet(session, null)
                    state("stopped", session.id)
                } else {
                    state("processing", session.id)
                    enqueue(session, SpeechDecodeJob(if (window.hasSpeech) window.snapshot() else FloatArray(0), segment, true, true, if (window.hasSpeech) window.voiceEnd else 0))
                }
            } finally {
                try { input?.close() } catch (e: Exception) { Log.w("PhoneMicAudio", "Microphone cleanup failed", e) }
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }
    fun stop() { active.get()?.recording = false }
    fun onBackground() { active.get()?.let { if (!it.background) it.recording = false } }
    fun cancel() {
        active.get()?.let { session -> session.cancelled = true; session.recording = false; session.queue.cancel(); active.compareAndSet(session, null) }
    }
    @Synchronized fun close(): Future<*> {
        closeTask?.let { return it }
        closed = true; cancel()
        val cleanup = capture.submit { captureDetector?.release(); captureDetector = null }
        closeTask = cleanup
        capture.shutdown()
        return cleanup
    }
}
