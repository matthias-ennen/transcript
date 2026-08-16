package de.matthiasennen.transcript.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSampleDiagnosticsTest {
    @Test
    fun `spoken signal produces plausible diagnostics`() {
        val samples = FloatArray(16_000) { index ->
            kotlin.math.sin(2.0 * Math.PI * 440.0 * index / 16_000.0).toFloat() * 0.25f
        }

        val result = analyzeAudioSamples(samples)

        assertEquals(16_000, result.sampleCount)
        assertEquals(1_000L, result.durationMs)
        assertTrue(result.peak in 0.24f..0.26f)
        assertTrue(result.rms > 0.1f)
        assertTrue(result.nearSilentSampleRatio < 0.01f)
    }

    @Test
    fun `silence is measured without inventing signal`() {
        val result = analyzeAudioSamples(FloatArray(8_000))

        assertEquals(8_000, result.sampleCount)
        assertEquals(500L, result.durationMs)
        assertEquals(0f, result.peak)
        assertEquals(0f, result.rms)
        assertEquals(1f, result.nearSilentSampleRatio)
    }

    @Test(expected = UnusableAudioSamplesException::class)
    fun `non finite decoder output is rejected`() {
        analyzeAudioSamples(floatArrayOf(0f, Float.NaN, 0.2f))
    }
}
