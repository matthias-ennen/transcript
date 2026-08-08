package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.estimateTranscriptionDurationSeconds
import de.matthiasennen.transcript.ui.main.formatTranscriptionEstimate
import de.matthiasennen.transcript.ui.main.transcriptionEstimateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionTimeEstimateTest {
    @Test
    fun `estimate requires a known media duration`() {
        assertNull(estimateTranscriptionDurationSeconds(0L, WhisperModel.BASE))
    }

    @Test
    fun `larger models produce longer provisional estimates`() {
        val durationMs = 10L * 60L * 1_000L
        val tiny = estimateTranscriptionDurationSeconds(durationMs, WhisperModel.TINY)!!
        val base = estimateTranscriptionDurationSeconds(durationMs, WhisperModel.BASE)!!
        val small = estimateTranscriptionDurationSeconds(durationMs, WhisperModel.SMALL_Q5_1)!!
        val turbo = estimateTranscriptionDurationSeconds(
            durationMs,
            WhisperModel.LARGE_V3_TURBO_Q5_0
        )!!
        val large = estimateTranscriptionDurationSeconds(durationMs, WhisperModel.LARGE_V3_Q5_0)!!

        assertTrue(tiny < base)
        assertTrue(base < small)
        assertTrue(small < turbo)
        assertTrue(turbo < large)
    }

    @Test
    fun `duration is rounded and formatted for minutes and hours`() {
        assertEquals("ca. 1 Minute", formatTranscriptionEstimate(60L))
        assertEquals("ca. 12 Minuten", formatTranscriptionEstimate(12L * 60L))
        assertEquals("ca. 1 Std. 25 Min.", formatTranscriptionEstimate(85L * 60L))
        assertEquals("ca. 2 Std.", formatTranscriptionEstimate(120L * 60L))
    }

    @Test
    fun `ready status contains the selected model estimate`() {
        assertEquals(
            "Voraussichtliche Transkriptionsdauer: ca. 5 Minuten",
            transcriptionEstimateStatus(3L * 60L * 1_000L, WhisperModel.BASE)
        )
    }

    @Test
    fun `changing the selected model immediately changes the ready estimate`() {
        val durationMs = 10L * 60L * 1_000L

        val tiny = transcriptionEstimateStatus(durationMs, WhisperModel.TINY)
        val base = transcriptionEstimateStatus(durationMs, WhisperModel.BASE)

        assertEquals("Voraussichtliche Transkriptionsdauer: ca. 9 Minuten", tiny)
        assertEquals("Voraussichtliche Transkriptionsdauer: ca. 16 Minuten", base)
    }
}
