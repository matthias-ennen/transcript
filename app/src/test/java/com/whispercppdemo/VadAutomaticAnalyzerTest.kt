package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperVadSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun `high silence ratio with stable speech enables vad`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(
            120_000L,
            listOf(
                WhisperVadSegment(5_000L, 9_000L),
                WhisperVadSegment(70_000L, 75_000L)
            )
        )

        val decision = analyzer.decide()

        assertTrue(decision.useVad)
        assertTrue(decision.silencePercent > 80)
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

    @Test
    fun `invalid and zero length segments are not counted`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(
            10_000L,
            listOf(
                WhisperVadSegment(-10L, 500L),
                WhisperVadSegment(2_000L, 2_000L),
                WhisperVadSegment(12_000L, 13_000L),
                WhisperVadSegment(3_000L, 5_000L)
            )
        )

        assertEquals(1, analyzer.decide().speechSegmentCount)
    }

    @Test
    fun `overlapping segments are merged without double counting`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(
            10_000L,
            listOf(
                WhisperVadSegment(1_000L, 4_000L),
                WhisperVadSegment(3_000L, 5_000L)
            )
        )

        val decision = analyzer.decide()

        assertEquals(1, decision.speechSegmentCount)
        assertEquals(64_000L, decision.detectedSpeechSampleCount)
    }

    @Test
    fun `positive speech never displays zero percent`() {
        val analyzer = VadAutomaticAnalyzer()
        analyzer.add(1_000_000L, listOf(WhisperVadSegment(100L, 200L)))

        assertEquals(1, analyzer.decide().speechPercent)
    }
}
