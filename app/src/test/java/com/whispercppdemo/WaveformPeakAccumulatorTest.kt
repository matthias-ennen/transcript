package de.matthiasennen.transcript.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformPeakAccumulatorTest {
    @Test
    fun waveformTimeoutIsSixtySeconds() {
        assertEquals(60_000L, WAVEFORM_GENERATION_TIMEOUT_MS)
    }

    @Test
    fun mapsTimestampedSamplesToFixedBars() {
        val accumulator = WaveformPeakAccumulator(barCount = 4, durationUs = 4_000_000L)

        accumulator.add(0.25f, 100_000L)
        accumulator.add(0.5f, 1_100_000L)
        accumulator.add(1f, 2_100_000L)
        accumulator.add(0.75f, 3_100_000L)

        assertEquals(listOf(0.25f, 0.5f, 1f, 0.75f), accumulator.normalizedPeaks())
    }

    @Test
    fun keepsOnlyFixedNumberOfAdaptiveBarsWhenDurationIsUnknown() {
        val accumulator = WaveformPeakAccumulator(barCount = 4, durationUs = 0L)

        repeat(64) { index -> accumulator.add((index + 1) / 64f, index.toLong()) }

        val peaks = accumulator.normalizedPeaks()
        assertEquals(4, peaks.size)
        assertTrue(peaks.all { it in 0.04f..1f })
        assertEquals(1f, peaks.last(), 0.0001f)
    }

    @Test
    fun returnsNoBarsWithoutSamples() {
        val unknownDuration = WaveformPeakAccumulator(barCount = 180, durationUs = 0L)
        val knownDuration = WaveformPeakAccumulator(barCount = 180, durationUs = 10_000_000L)

        assertTrue(unknownDuration.normalizedPeaks().isEmpty())
        assertTrue(knownDuration.normalizedPeaks().isEmpty())
    }
}
