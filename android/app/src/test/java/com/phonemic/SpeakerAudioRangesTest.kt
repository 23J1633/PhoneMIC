package com.phonemic

import org.junit.Assert.*
import org.junit.Test

class SpeakerAudioRangesTest {
    @Test fun restoresRealContextWithoutChangingVerificationSamples() {
        val samples = FloatArray(64000) { it.toFloat() }
        val ranges = SpeakerAudioRanges.select(samples, listOf(8000 until 40000)) {
            assertEquals(8000f, it.first(), 0f)
            assertEquals(39999f, it.last(), 0f)
            true
        }
        assertEquals(listOf(4000 until 45333), ranges)
        assertEquals(4000f, samples[ranges.single().first], 0f)
    }
    @Test fun doesNotCopyRejectedNeighborIntoAcceptedSpeech() {
        val samples = FloatArray(64000) { if (it < 32000) 1f else -1f }
        val ranges = SpeakerAudioRanges.select(samples, listOf(0 until samples.size)) { it.all { sample -> sample > 0 } }
        assertEquals(listOf(0 until 32000), ranges)
        assertTrue(ranges.all { r -> r.all { samples[it] > 0 } })
    }
    @Test fun balancesLongSegmentsWithoutAnUnverifiableRemainder() {
        val lengths = ArrayList<Int>()
        val samples = FloatArray(97600)
        val ranges = SpeakerAudioRanges.select(samples, listOf(samples.indices)) { lengths.add(it.size); true }
        assertEquals(4, lengths.size)
        assertTrue(lengths.all { it in 24000..48000 })
        assertEquals(samples.size, lengths.sum())
        assertEquals(samples.indices.toList(), ranges.flatMap { it.toList() })
    }
    @Test fun paddingCannotCrossADifferentVadRegion() {
        val samples = FloatArray(96000) { if (it < 48000) 1f else -1f }
        val ranges = SpeakerAudioRanges.select(samples, listOf(8000 until 46000, 48000 until 88000)) { it.first() > 0 }
        assertEquals(listOf(4000 until 47000), ranges)
        assertTrue(ranges.single().all { samples[it] > 0 })
    }

    @Test fun rejectsAnAcceptedAverageWhenItsBoundaryContainsAnotherSpeaker() {
        val samples = FloatArray(64000) { if (it < 16000) 1f else -1f }
        var calls = 0
        val ranges = SpeakerAudioRanges.select(samples, listOf(samples.indices),
            boundaryAccept = { it.all { sample -> sample > 0 } },
        ) { calls++ == 0 }
        assertTrue(ranges.isEmpty())
    }

    @Test fun checksOnlyTheSideBorderingARejectedWindow() {
        val samples = FloatArray(64000) { it.toFloat() }
        var calls = 0
        val probed = ArrayList<Float>()
        val ranges = SpeakerAudioRanges.select(samples, listOf(samples.indices),
            boundaryAccept = { probed.add(it.first()); true },
        ) { calls++ == 1 }
        assertEquals(listOf(32000f), probed)
        assertEquals(listOf(32000 until 64000), ranges)
    }

    @Test fun boundaryConfirmationCannotAdmitARejectedSpeaker() {
        var boundaryCalls = 0
        val samples = FloatArray(64000)
        val ranges = SpeakerAudioRanges.select(samples, listOf(samples.indices),
            boundaryAccept = { boundaryCalls++; true },
        ) { false }
        assertTrue(ranges.isEmpty())
        assertEquals(0, boundaryCalls)
    }
}
