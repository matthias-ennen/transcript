package de.matthiasennen.transcript.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioDecoderLimitsTest {
    @Test
    fun `requested sample count matches a 16 kHz section`() {
        assertEquals(2_400_000, targetSampleCount(150_000L))
        assertEquals(4_864_000, targetSampleCount(304_000L))
    }

    @Test
    fun `decoder capacity adds a fixed five second margin`() {
        assertEquals(2_480_000, decoderOutputCapacity(2_400_000))
    }

    @Test
    fun `decoder overhang is trimmed to requested section`() {
        val decoded = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        val result = trimDecoderSamples(decoded, requestedSamples = 4)

        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), result.samples, 0f)
        assertEquals(2, result.discardedTrailingSamples)
    }

    @Test
    fun `decoder result without overhang is reused`() {
        val decoded = floatArrayOf(1f, 2f, 3f)

        val result = trimDecoderSamples(decoded, requestedSamples = 4)

        assertSame(decoded, result.samples)
        assertEquals(0, result.discardedTrailingSamples)
    }
}
