package com.phonemic

import java.util.ArrayDeque

internal data class SpeechDecodeJob(
    val samples: FloatArray,
    val segment: Int,
    val commit: Boolean,
    val final: Boolean,
    val voiceEnd: Int,
)

/** Keeps committed clauses in order while making previews expendable. */
internal class SpeechDecodeQueue {
    private val jobs = ArrayDeque<SpeechDecodeJob>()
    private var scheduled = false
    private var cancelled = false
    private var nextPreviewAt = 0L
    private var previewSegment = -1
    private var previewVoiceEnd = -1

    @Synchronized fun preview(
        nowMs: Long, segment: Int, voiceEnd: Int, workerBusy: Boolean,
        samples: () -> FloatArray,
    ): Boolean {
        if (cancelled || scheduled || workerBusy || nowMs < nextPreviewAt ||
            (previewSegment == segment && previewVoiceEnd == voiceEnd)) return false
        jobs.addLast(SpeechDecodeJob(samples(), segment, false, false, voiceEnd))
        previewSegment = segment
        previewVoiceEnd = voiceEnd
        nextPreviewAt = nowMs + 1600
        scheduled = true
        return true
    }

    @Synchronized fun commit(job: SpeechDecodeJob): Boolean {
        if (cancelled) return false
        require(job.commit)
        val pending = jobs.iterator()
        while (pending.hasNext()) if (!pending.next().commit) pending.remove()
        jobs.addLast(job)
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized fun next(): SpeechDecodeJob? {
        if (cancelled || jobs.isEmpty()) {
            jobs.clear()
            scheduled = false
            return null
        }
        return jobs.removeFirst()
    }

    @Synchronized fun decoded(job: SpeechDecodeJob, finishedMs: Long, durationMs: Long) {
        // Give the phone breathing room after slow speculative work. Final jobs never wait here.
        if (!job.commit) nextPreviewAt = maxOf(nextPreviewAt, finishedMs + durationMs)
        else nextPreviewAt = 0
    }

    @Synchronized fun cancel() { cancelled = true; jobs.clear() }
}

internal fun canReuseSpeechPreview(
    previousSegment: Int, previousVoiceEnd: Int, previousSamples: Int, next: SpeechDecodeJob,
): Boolean = previousSegment == next.segment && previousVoiceEnd == next.voiceEnd &&
    (previousSamples == next.samples.size ||
        minOf(previousSamples, next.samples.size) - next.voiceEnd >= 3200)
