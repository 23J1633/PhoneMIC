package com.phonemic

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.PromiseImpl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VoiceprintLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before fun foreground() {
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun pcm(name: String): FloatArray {
        val bytes = instrumentation.context.assets.open("$name.pcm").use { it.readBytes() }
        return FloatArray(bytes.size / 2) { i ->
            (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f
        }
    }

    private fun profile(): SpeakerVoiceprint.Profile = ResidentQwenModel.worker.submit<SpeakerVoiceprint.Profile> {
        val enrollment = (pcm("fangjun-sr-1") + FloatArray(3200) + pcm("fangjun-sr-2") +
            FloatArray(3200) + pcm("fangjun-sr-3")).copyOf(160000)
        SpeakerVoiceprint.enroll(
            ResidentQwenModel.speakerExtractor(context),
            SpeechAudio.chunks(enrollment, ResidentQwenModel.voiceDetector(context), keepWholeShort = false),
        )
    }.get(90, TimeUnit.SECONDS)

    private data class Event(val type: String, val text: String, val final: Boolean, val state: String)

    /** Exercise the real inference queue without capturing a microphone or creating a transport. */
    private fun decodeCommittedWithEmptyTail(parts: List<FloatArray>): List<Event> {
        val profile = profile()
        val events = CopyOnWriteArrayList<Event>()
        val stopped = CountDownLatch(1)
        val speech = OfflineSpeech(context) { event ->
            val type = event.getString("type") ?: ""
            val state = if (event.hasKey("state")) event.getString("state") ?: "" else ""
            events.add(Event(
                type,
                if (event.hasKey("text")) event.getString("text") ?: "" else "",
                event.hasKey("final") && event.getBoolean("final"),
                state,
            ))
            if (state == "stopped") stopped.countDown()
        }
        try {
            // Session creation is injected at the capture/decode boundary, not by setting rejection flags.
            val sessionClass = OfflineSpeech::class.java.declaredClasses.single { it.simpleName == "Session" }
            val constructor = sessionClass.getDeclaredConstructor(
                String::class.java, Boolean::class.javaPrimitiveType, String::class.java,
                SpeakerVoiceprint.Profile::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val session = constructor.newInstance("voiceprint-lifecycle-fixture", true, "", profile, false)
            val activeField = OfflineSpeech::class.java.getDeclaredField("active").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val active = activeField.get(speech) as AtomicReference<Any?>
            active.set(session)
            val enqueue = OfflineSpeech::class.java.getDeclaredMethod("enqueue", sessionClass, SpeechDecodeJob::class.java)
                .apply { isAccessible = true }
            parts.forEachIndexed { index, audio ->
                enqueue.invoke(speech, session, SpeechDecodeJob(audio, index, true, false, audio.size))
            }
            enqueue.invoke(speech, session, SpeechDecodeJob(FloatArray(0), parts.size, true, true, 0))
            assertTrue("Filtered session did not finish", stopped.await(120, TimeUnit.SECONDS))
            assertFalse("Inference raised an error instead of completing", events.any { it.state == "error" })
            assertFalse("Finished session remained active", speech.isActive())
            return events.toList()
        } finally {
            speech.cancel()
            speech.close().get(10, TimeUnit.SECONDS)
            ResidentQwenModel.worker.submit { }.get(20, TimeUnit.SECONDS)
        }
    }

    @Test fun rejectedCommittedSpeechIsReportedBeforeEmptyFinalTail() {
        val events = decodeCommittedWithEmptyTail(listOf(pcm("leijun-test-sr-1")))
        val finalIndex = events.indexOfFirst { it.type == "transcript" && it.final }
        val filteredIndex = events.indexOfFirst { it.type == "filtered" }
        assertTrue("Missing final transcript", finalIndex >= 0)
        assertEquals("Other speaker reached the final transcript", "", events[finalIndex].text)
        assertTrue("An earlier rejected clause was silently lost at the empty final tail", filteredIndex in 0 until finalIndex)
        assertEquals("Duplicate rejection notices", 1, events.count { it.type == "filtered" })
        report("voiceprint_rejection_lifecycle", JSONObject()
            .put("finalEmpty", true).put("filteredBeforeFinal", true).put("microphoneOpened", false))
    }

    @Test fun acceptedEarlierSpeechDoesNotReceiveAnAllFilteredNotice() {
        val events = decodeCommittedWithEmptyTail(listOf(pcm("fangjun-test-sr-1"), pcm("leijun-test-sr-1")))
        val final = events.single { it.type == "transcript" && it.final }
        assertTrue("The owner's earlier clause was lost", final.text.isNotBlank())
        assertFalse("The app said nothing was typed despite accepted owner text", events.any { it.type == "filtered" })
        report("voiceprint_partial_accept_lifecycle", JSONObject()
            .put("ownerTextRetained", true).put("allFilteredNotice", false).put("microphoneOpened", false))
    }

    private class Completion {
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val resolved = AtomicReference(false)
        val promise: Promise = PromiseImpl(
            Callback { resolved.set(true); done.countDown() },
            Callback { args ->
                // Keep only an error code/message; never stringify an enrollment profile.
                val map = args.firstOrNull() as? com.facebook.react.bridge.ReadableMap
                error.set(if (map != null && map.hasKey("code")) map.getString("code") else "REJECTED")
                done.countDown()
            },
        )
    }

    /** Captured samples stay in memory and are cancelled before any enrollment inference runs. */
    @Test fun cancelledQueuedEnrollmentReleasesRecorderBeforeInferenceUnblocks() {
        val blocked = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val captureFinished = CountDownLatch(1)
        val progressEvents = AtomicInteger(0)
        val recorder = VoiceprintRecorder(context) { event ->
            progressEvents.incrementAndGet()
            if (event.getDouble("progress") >= 1.0) captureFinished.countDown()
        }
        var blocker: Future<*>? = null
        try {
            blocker = ResidentQwenModel.worker.submit {
                blocked.countDown()
                check(unblock.await(60, TimeUnit.SECONDS)) { "Test inference blocker timed out" }
            }
            assertTrue("Inference worker did not reach the blocker", blocked.await(90, TimeUnit.SECONDS))
            val first = Completion()
            recorder.start(first.promise)
            assertTrue("Enrollment did not complete its capture phase", captureFinished.await(25, TimeUnit.SECONDS))
            // Wait for submission so cancellation really exercises a queued inference future.
            val activeField = VoiceprintRecorder::class.java.getDeclaredField("active").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val active = activeField.get(recorder) as AtomicReference<Any?>
            val submissionDeadline = SystemClock.elapsedRealtime() + 3000
            var inference: Future<*>? = null
            while (inference == null && SystemClock.elapsedRealtime() < submissionDeadline) {
                val token = active.get()
                if (token != null) inference = token.javaClass.getDeclaredField("inference")
                    .apply { isAccessible = true }.get(token) as? Future<*>
                if (inference == null) Thread.sleep(20)
            }
            assertNotNull("Enrollment did not queue its inference task", inference)
            val started = SystemClock.elapsedRealtime()
            recorder.cancel()
            assertTrue("Cancellation waited for blocked inference", first.done.await(2500, TimeUnit.MILLISECONDS))
            waitUntilInactive(recorder)
            val cancelledMs = SystemClock.elapsedRealtime() - started
            assertFalse("Cancelled enrollment returned a voiceprint", first.resolved.get())
            assertEquals("CANCELLED", first.error.get())
            assertTrue("Inference blocker was unexpectedly released", unblock.count == 1L)
            assertTrue("Cancelled enrollment remained queued", inference!!.isCancelled)

            val previousEvents = progressEvents.get()
            val second = Completion()
            recorder.start(second.promise)
            val restartDeadline = SystemClock.elapsedRealtime() + 3000
            while (progressEvents.get() == previousEvents && second.done.count > 0 &&
                SystemClock.elapsedRealtime() < restartDeadline) Thread.sleep(20)
            assertTrue("Restart did not capture audio, or was rejected as busy", progressEvents.get() > previousEvents)
            assertNull("Restart was rejected", second.error.get())
            recorder.cancel()
            assertTrue("Second cancellation did not complete", second.done.await(2500, TimeUnit.MILLISECONDS))
            waitUntilInactive(recorder)
            assertFalse("Second cancelled enrollment returned a voiceprint", second.resolved.get())
            assertEquals("CANCELLED", second.error.get())
            report("voiceprint_cancel_lifecycle", JSONObject()
                .put("cancelMs", cancelledMs).put("restartedBeforeInferenceUnblocked", true)
                .put("profileReturned", false).put("profileStored", false))
        } finally {
            recorder.cancel()
            recorder.close()
            unblock.countDown()
            blocker?.get(10, TimeUnit.SECONDS)
            ResidentQwenModel.worker.submit { }.get(20, TimeUnit.SECONDS)
        }
    }

    private fun waitUntilInactive(recorder: VoiceprintRecorder) {
        val deadline = SystemClock.elapsedRealtime() + 1000
        while (recorder.isActive() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        assertFalse("Cancelled enrollment kept the active slot", recorder.isActive())
    }

    private fun report(name: String, data: JSONObject) {
        instrumentation.sendStatus(0, Bundle().apply { putString(name, data.toString()) })
    }
}
