package com.phonemic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCaptureWindowTest {
    private val voice: (FloatArray) -> Boolean = { frame -> frame.any { it > 0.5f } }

    private fun append(
        window: SpeechCaptureWindow,
        samples: ShortArray,
        isVoice: (FloatArray) -> Boolean = voice,
        trim: Boolean = false,
        chunkSize: Int = 1600,
    ) {
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(chunkSize, samples.size - offset)
            window.append(samples.copyOfRange(offset, offset + count), count, isVoice)
            if (trim) window.trimIdle()
            offset += count
        }
    }

    @Test fun shortFalsePositiveDoesNotKeepTenSecondsOfSilence() {
        val window = SpeechCaptureWindow()
        append(window, ShortArray(1024) { 24000 }, trim = true)
        assertFalse(window.hasSpeech)
        append(window, ShortArray(160000), trim = true)
        assertEquals(4000, window.used)
        assertEquals(0, window.voiceEnd)
        append(window, ShortArray(16000) { 24000 }, trim = true)
        assertTrue(window.hasSpeech)
        assertEquals(20000, window.used)
        assertArrayEquals(FloatArray(4000), window.snapshot().copyOfRange(0, 4000), 0f)
    }

    @Test fun isolatedNoiseCandidatesNeverAccumulateIntoSpeech() {
        val window = SpeechCaptureWindow()
        repeat(12) {
            append(window, ShortArray(1024) { 24000 }, trim = true)
            assertFalse(window.hasSpeech)
            append(window, ShortArray(16384), trim = true)
            assertFalse(window.hasSpeech)
            assertFalse(window.shouldCommit)
            assertEquals(4000, window.used)
            assertEquals(0, window.voiceEnd)
        }
    }

    @Test fun newVoiceInTheReadThatExpiresACandidateDoesNotReviveIt() {
        val window = SpeechCaptureWindow()
        append(window, ShortArray(1024) { 24000 }, trim = true)
        append(window, ShortArray(10240), trim = true)
        // Two quiet frames cross 700 ms, followed by a new positive frame in the same read.
        window.append(ShortArray(1024) + ShortArray(512) { 24000 }, 1536, voice)
        window.trimIdle()
        window.append(ShortArray(512) { 24000 }, 512, voice)
        assertFalse(window.hasSpeech)
        assertEquals(4512, window.used)
        assertEquals(4512, window.voiceEnd)
    }

    @Test fun silenceCommitUsesExactlySevenHundredMillisecondsOfSamples() {
        val window = SpeechCaptureWindow()
        append(window, ShortArray(2048) { 24000 })
        assertTrue(window.hasSpeech)
        assertEquals(2048, window.voiceEnd)
        append(window, ShortArray(11199))
        assertFalse(window.shouldCommit)
        window.append(shortArrayOf(0), 1, voice)
        assertTrue(window.shouldCommit)
        assertEquals(13248, window.used)
    }

    @Test fun idleWindowRetainsExactlyTheLastQuarterSecond() {
        val window = SpeechCaptureWindow()
        val samples = ShortArray(24000) { (it - 12000).toShort() }
        append(window, samples, { false }, trim = true, chunkSize = 777)
        assertEquals(4000, window.used)
        assertEquals(0, window.voiceEnd)
        val expected = FloatArray(4000) { samples[samples.size - 4000 + it] / 32768f }
        assertArrayEquals(expected, window.snapshot(), 0f)
    }

    @Test fun twentySecondCommitsPreserveEverySampleAndVadFrame() {
        val window = SpeechCaptureWindow()
        val samples = ShortArray(640731) { ((it * 31) % 65536 - 32768).toShort() }
        val expected = FloatArray(samples.size) { samples[it] / 32768f }
        val captured = ArrayList<FloatArray>()
        val frames = ArrayList<FloatArray>()
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(997, samples.size - offset, window.capacity - window.used)
            window.append(samples.copyOfRange(offset, offset + count), count) { frame ->
                frames.add(frame.copyOf())
                true
            }
            offset += count
            if (window.shouldCommit) {
                assertEquals(320000, window.used)
                captured.add(window.snapshot())
                window.reset()
            }
        }
        captured.add(window.snapshot())
        assertEquals(listOf(320000, 320000, 731), captured.map { it.size })
        var resultOffset = 0
        for (part in captured) {
            assertArrayEquals(expected.copyOfRange(resultOffset, resultOffset + part.size), part, 0f)
            resultOffset += part.size
        }
        assertEquals(expected.size, resultOffset)
        assertEquals(samples.size / 512, frames.size)
        frames.forEachIndexed { i, frame ->
            assertArrayEquals(expected.copyOfRange(i * 512, (i + 1) * 512), frame, 0f)
        }
    }

    @Test fun resetKeepsPartialVadFrameWithoutDuplicatingAudio() {
        val window = SpeechCaptureWindow()
        val first = ShortArray(700) { (1000 + it).toShort() }
        val second = ShortArray(900) { (2000 + it).toShort() }
        val frames = ArrayList<FloatArray>()
        val detector: (FloatArray) -> Boolean = { frames.add(it.copyOf()); true }
        window.append(first, first.size, detector)
        val committed = window.snapshot()
        window.reset()
        assertEquals(0, window.used)
        assertEquals(0, window.voiceEnd)
        assertFalse(window.hasSpeech)
        window.append(second, second.size, detector)
        assertArrayEquals(FloatArray(first.size) { first[it] / 32768f }, committed, 0f)
        assertArrayEquals(FloatArray(second.size) { second[it] / 32768f }, window.snapshot(), 0f)
        assertEquals(836, window.voiceEnd)
        val all = first + second
        assertEquals(3, frames.size)
        frames.forEachIndexed { i, frame ->
            assertArrayEquals(FloatArray(512) { all[i * 512 + it] / 32768f }, frame, 0f)
        }
    }

    @Test fun tooLargeReadIsRejectedBeforeChangingTheWindow() {
        val window = SpeechCaptureWindow()
        try {
            window.append(ShortArray(1601), 1601) { false }
            throw AssertionError("Oversized read was accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, window.used)
        }
    }

    @Test fun weakNoiseCannotStartSpeechOrAccumulateCandidates() {
        val window = SpeechCaptureWindow()
        append(window, ShortArray(160000), { 0.25f >= window.voiceThreshold }, trim = true)
        assertFalse(window.hasSpeech)
        assertFalse(window.shouldCommit)
        assertEquals(4000, window.used)
        assertEquals(0.35f, window.voiceThreshold, 0f)
    }

    @Test fun confirmedSpeechKeepsWeakTailUntilActualSilence() {
        val window = SpeechCaptureWindow()
        append(window, ShortArray(2048), { 0.80f >= window.voiceThreshold })
        assertTrue(window.hasSpeech)
        assertEquals(0.20f, window.voiceThreshold, 0f)
        append(window, ShortArray(16384), { 0.25f >= window.voiceThreshold })
        assertFalse(window.shouldCommit)
        assertEquals(18432, window.voiceEnd)
        append(window, ShortArray(11199), { 0.05f >= window.voiceThreshold })
        assertFalse(window.shouldCommit)
        window.append(shortArrayOf(0), 1) { 0.05f >= window.voiceThreshold }
        assertTrue(window.shouldCommit)
        window.reset()
        assertEquals(0.35f, window.voiceThreshold, 0f)
        append(window, ShortArray(16000), { 0.25f >= window.voiceThreshold }, trim = true)
        assertFalse(window.hasSpeech)
    }

    @Test fun overflowIsRejectedBeforeChangingTheWindow() {
        val window = SpeechCaptureWindow(2000)
        window.append(ShortArray(1600), 1600) { false }
        try {
            window.append(ShortArray(401), 401) { false }
            throw AssertionError("Overflowing read was accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(1600, window.used)
            assertArrayEquals(FloatArray(1600), window.snapshot(), 0f)
        }
    }
}
