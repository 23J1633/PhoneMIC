package com.phonemic

import org.junit.Assert.*
import org.junit.Test

class SpeechDecodeQueueTest {
    private fun commit(segment: Int, final: Boolean = false) =
        SpeechDecodeJob(floatArrayOf(segment.toFloat()), segment, true, final, 1)

    @Test fun busyWorkerDoesNotAllocateAudioSnapshots() {
        val queue = SpeechDecodeQueue()
        var snapshots = 0
        repeat(100) { i ->
            assertFalse(queue.preview(i * 1000L, 0, i, true) { snapshots++; FloatArray(320000) })
        }
        assertEquals(0, snapshots)
        assertTrue(queue.preview(100000, 0, 999, false) { snapshots++; FloatArray(16000) })
        assertEquals(1, snapshots)
    }

    @Test fun finalDropsQueuedPreviewButPreservesCommittedClauses() {
        val queue = SpeechDecodeQueue()
        assertTrue(queue.preview(0, 0, 16000, false) { FloatArray(16000) })
        assertFalse(queue.commit(commit(0)))
        assertFalse(queue.commit(commit(1)))
        assertFalse(queue.commit(commit(2, final = true)))
        assertEquals(0, queue.next()!!.segment)
        assertEquals(1, queue.next()!!.segment)
        assertTrue(queue.next()!!.final)
        assertNull(queue.next())
    }

    @Test fun inFlightPreviewCannotBuildAnUnboundedQueue() {
        val queue = SpeechDecodeQueue()
        queue.preview(0, 0, 16000, false) { FloatArray(16000) }
        val running = queue.next()!!
        repeat(100) { assertFalse(queue.preview(10000, 0, 32000, false) { error("Unexpected snapshot") }) }
        queue.commit(commit(0, final = true))
        queue.decoded(running, 10000, 10000)
        assertTrue(queue.next()!!.final)
        assertNull(queue.next())
    }

    @Test fun slowPreviewBacksOffButFinalNeverWaits() {
        val queue = SpeechDecodeQueue()
        queue.preview(1000, 0, 16000, false) { FloatArray(16000) }
        queue.decoded(queue.next()!!, 4000, 3000)
        assertNull(queue.next())
        assertFalse(queue.preview(5000, 0, 32000, false) { error("Busy preview budget") })
        assertTrue(queue.commit(commit(0, final = true)))
        assertTrue(queue.next()!!.final)
    }

    @Test fun sameVoicePrefixDoesNotRunAgainDuringSilence() {
        val queue = SpeechDecodeQueue()
        queue.preview(0, 0, 16000, false) { FloatArray(16000) }
        queue.next()
        queue.next()
        assertFalse(queue.preview(10000, 0, 16000, false) { error("Duplicate prefix") })
        assertTrue(queue.preview(10000, 0, 16512, false) { FloatArray(18000) })
    }

    @Test fun cancellationDiscardsOldFinalAndDoesNotAffectNewQueue() {
        val old = SpeechDecodeQueue()
        old.commit(commit(0, true))
        old.cancel()
        assertNull(old.next())
        assertFalse(old.commit(commit(1, true)))
        val fresh = SpeechDecodeQueue()
        assertTrue(fresh.commit(commit(0, true)))
        assertTrue(fresh.next()!!.final)
    }

    @Test fun cacheRequiresEnoughTailToKeepAnUnfinishedPhoneme() {
        val final = SpeechDecodeJob(FloatArray(16600), 0, true, true, 16000)
        assertFalse(canReuseSpeechPreview(0, 16000, 16400, final))
        assertTrue(canReuseSpeechPreview(0, 16000, 16600, final))
        val silentTail = final.copy(samples = FloatArray(22000))
        assertTrue(canReuseSpeechPreview(0, 16000, 19200, silentTail))
        assertFalse(canReuseSpeechPreview(1, 16000, 22000, silentTail))
        assertFalse(canReuseSpeechPreview(0, 15500, 22000, silentTail))
    }
}
