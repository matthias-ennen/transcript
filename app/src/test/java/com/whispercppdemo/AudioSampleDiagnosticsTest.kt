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

    @Test
    fun `valid Opus preroll packet is not treated as end of stream`() {
        assertEquals(false, isExtractorEndOfStream(sampleSize = 291))
        assertEquals(true, isExtractorEndOfStream(sampleSize = -1))
    }

    @Test
    fun `negative Opus preroll timestamps are normalized monotonically`() {
        val offsetUs = decoderTimestampOffsetUs(firstSampleTimeUs = -14_500L)

        assertEquals(14_500L, offsetUs)
        assertEquals(0L, normalizedDecoderTimestampUs(-14_500L, offsetUs))
        assertEquals(20_000L, normalizedDecoderTimestampUs(5_500L, offsetUs))
    }

    @Test
    fun `non negative timestamps keep their original time axis`() {
        val offsetUs = decoderTimestampOffsetUs(firstSampleTimeUs = 60_000_000L)

        assertEquals(0L, offsetUs)
        assertEquals(
            60_000_000L,
            normalizedDecoderTimestampUs(60_000_000L, offsetUs)
        )
    }

    @Test(expected = UnusableAudioSamplesException::class)
    fun `non finite decoder output is rejected`() {
        analyzeAudioSamples(floatArrayOf(0f, Float.NaN, 0.2f))
    }
}
