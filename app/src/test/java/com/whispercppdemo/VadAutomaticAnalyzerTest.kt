package de.matthiasennen.transcript.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadAutomaticAnalyzerTest {
    @Test
    fun `continuous audio stays with whisper`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(FloatArray(16_000 * 20) { 0.1f })
        assertFalse(analyzer.decide().useVad)
    }

    @Test
    fun `few long clear pauses enable vad`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(FloatArray(16_000 * 10) { 0.1f })
        analyzer.add(FloatArray(16_000 * 4))
        analyzer.add(FloatArray(16_000 * 10) { 0.1f })
        assertTrue(analyzer.decide().useVad)
    }

    @Test
    fun `mostly silent uncertain audio stays with whisper`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(FloatArray(16_000 * 19))
        analyzer.add(FloatArray(16_000) { 0.1f })
        assertFalse(analyzer.decide().useVad)
    }
}
