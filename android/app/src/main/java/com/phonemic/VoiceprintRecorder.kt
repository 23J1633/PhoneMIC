package com.phonemic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Process
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

class VoiceprintRecorder(private val context: Context, private val emit: (WritableMap) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private class Enrollment {
        @Volatile var cancelled = false
        @Volatile var inference: Future<SpeakerVoiceprint.Profile>? = null
    }
    private val active = AtomicReference<Enrollment?>(null)
    fun isActive() = active.get() != null
    fun start(promise: Promise) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            promise.reject("MIC_PERMISSION", "Microphone permission required"); return
        }
        val enrollment = Enrollment()
        if (!active.compareAndSet(null, enrollment)) { promise.reject("BUSY", "Enrollment in progress"); return }
        executor.execute {
            var recorder: AudioRecord? = null
            var input: MicrophoneInput? = null
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                input = MicrophoneInput.open(context) { enrollment.cancelled = true }
                recorder = input.recorder
                if (enrollment.cancelled) throw CancellationException("Enrollment cancelled")
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
                recorder.startRecording()
                val samples = FloatArray(192000)
                val buffer = ShortArray(1600)
                var used = 0
                while (used < samples.size && !enrollment.cancelled) {
                    val count = recorder.read(buffer, 0, minOf(buffer.size, samples.size - used), AudioRecord.READ_BLOCKING)
                    check(count > 0) { "Microphone read failed" }
                    var power = 0.0
                    for (i in 0 until count) { samples[used + i] = buffer[i] / 32768f; power += samples[used + i] * samples[used + i] }
                    used += count
                    val db = 20 * log10(maxOf(sqrt(power / count), 0.00001))
                    emit(Arguments.createMap().apply {
                        putDouble("progress", used.toDouble() / samples.size)
                        putDouble("level", ((db + 60) / 54).coerceIn(0.0, 1.0))
                    })
                }
                input.close(); input = null; recorder = null
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                if (enrollment.cancelled) throw CancellationException("Enrollment cancelled")
                val future = ResidentQwenModel.worker.submit<SpeakerVoiceprint.Profile> {
                    if (enrollment.cancelled) throw CancellationException("Enrollment cancelled")
                    val detector = ResidentQwenModel.voiceDetector(context)
                    val phases = listOf(samples.copyOfRange(0, 96000), samples.copyOfRange(96000, samples.size))
                        .map { SpeechAudio.chunks(it, detector, keepWholeShort = false) }
                    SpeakerVoiceprint.enrollPhases(ResidentQwenModel.speakerExtractor(context), phases)
                }
                enrollment.inference = future
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                var result: SpeakerVoiceprint.Profile? = null
                while (result == null) {
                    if (enrollment.cancelled) { future.cancel(false); throw CancellationException("Enrollment cancelled") }
                    if (System.nanoTime() >= deadline) { future.cancel(false); throw TimeoutException("Enrollment timed out") }
                    try { result = future.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* Check cancellation before waiting again. */ }
                }
                val enrolled = result
                if (enrollment.cancelled) throw CancellationException("Enrollment cancelled")
                val profile = Arguments.createMap().apply {
                    putArray("embedding", Arguments.createArray().apply { enrolled.embedding.forEach { pushDouble(it.toDouble()) } })
                    putArray("templates", Arguments.createArray().apply {
                        enrolled.templates.forEach { vector -> pushArray(Arguments.createArray().apply { vector.forEach { pushDouble(it.toDouble()) } }) }
                    })
                    putString("model", SpeakerVoiceprint.MODEL)
                    putDouble("createdAt", System.currentTimeMillis().toDouble())
                    putDouble("durationMs", used / 16.0)
                }
                promise.resolve(profile)
            } catch (error: Exception) {
                val cause = error.cause ?: error
                promise.reject(if (enrollment.cancelled || cause is CancellationException) "CANCELLED" else "VOICEPRINT", cause.message, error)
            } finally {
                try { input?.close() } finally {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                    active.compareAndSet(enrollment, null)
                }
            }
        }
    }
    fun cancel() { active.get()?.let { it.cancelled = true; it.inference?.cancel(false) } }
    fun close() { cancel(); executor.shutdown() }
}
