package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperVadSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadAutomaticAnalyzerTest {
    @Test
    fun `continuous Silero speech stays with whisper`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(20_000L, listOf(WhisperVadSegment(0L, 19_000L)))
        assertFalse(analyzer.decide().useVad)
    }

    @Test
    fun `few stable speech areas with long pauses enable vad`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(
            30_000L,
            listOf(
                WhisperVadSegment(0L, 10_000L),
                WhisperVadSegment(14_000L, 24_000L)
            )
        )
        assertTrue(analyzer.decide().useVad)
    }

    @Test
    fun `mostly removed uncertain audio stays with whisper`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(20_000L, listOf(WhisperVadSegment(9_000L, 10_000L)))
        assertFalse(analyzer.decide().useVad)
    }

    @Test
    fun `many short Silero fragments stay with whisper`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(
            60_000L,
            (0 until 20).map { index ->
                val start = index * 2_500L
                WhisperVadSegment(start, start + 400L)
            }
        )
        assertFalse(analyzer.decide().useVad)
    }

    @Test
    fun `silence across chunk boundary is counted as one pause`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(10_000L, listOf(WhisperVadSegment(0L, 8_000L)))
        analyzer.add(10_000L, listOf(WhisperVadSegment(4_000L, 10_000L)))

        val decision = analyzer.decide()

        assertTrue(decision.useVad)
        assertTrue(decision.longestSilenceMs >= 6_000L)
    }
}
