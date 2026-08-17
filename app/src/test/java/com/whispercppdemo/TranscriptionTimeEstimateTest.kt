package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.TranscriptUiState
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.estimateTranscriptionDurationSeconds
import de.matthiasennen.transcript.ui.main.elapsedSecondsSince
import de.matthiasennen.transcript.ui.main.formatTranscriptionEstimate
import de.matthiasennen.transcript.ui.main.transcriptionRealtimeFactor
import de.matthiasennen.transcript.ui.main.transcriptionEstimateStatus
import de.matthiasennen.transcript.ui.main.transcriptionRuntimeDisplay
import de.matthiasennen.transcript.ui.main.withRecalculatedTranscriptionEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionTimeEstimateTest {
    @Test
    fun `uses the calibrated realtime factors`() {
        assertEquals(0.75, WhisperModel.TINY.transcriptionRealtimeFactor(), 0.0)
        assertEquals(1.0, WhisperModel.BASE.transcriptionRealtimeFactor(), 0.0)
        assertEquals(1.6, WhisperModel.SMALL_Q5_1.transcriptionRealtimeFactor(), 0.0)
        assertEquals(5.0, WhisperModel.LARGE_V3_TURBO_Q5_0.transcriptionRealtimeFactor(), 0.0)
        assertEquals(6.0, WhisperModel.LARGE_V3_Q5_0.transcriptionRealtimeFactor(), 0.0)
    }

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
    fun `ten minutes use only the defined model factors`() {
        val durationMs = 10L * 60L * 1_000L

        assertEquals(
            8L * 60L,
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.TINY)
        )
        assertEquals(
            10L * 60L,
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.BASE)
        )
        assertEquals(
            16L * 60L,
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.SMALL_Q5_1)
        )
        assertEquals(
            50L * 60L,
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.LARGE_V3_TURBO_Q5_0)
        )
        assertEquals(
            60L * 60L,
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.LARGE_V3_Q5_0)
        )
    }

    @Test
    fun `estimate is always rounded up to the next full minute`() {
        assertEquals(
            11L * 60L,
            estimateTranscriptionDurationSeconds(
                10L * 60L * 1_000L + 23L * 1_000L,
                WhisperModel.BASE
            )
        )
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
            "Voraussichtliche Transkriptionsdauer: ca. 3 Minuten",
            transcriptionEstimateStatus(
                estimateTranscriptionDurationSeconds(
                    3L * 60L * 1_000L,
                    WhisperModel.BASE
                )
            )
        )
    }

    @Test
    fun `changing the selected model immediately changes the ready estimate`() {
        val durationMs = 10L * 60L * 1_000L

        val tiny = transcriptionEstimateStatus(
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.TINY)
        )
        val base = transcriptionEstimateStatus(
            estimateTranscriptionDurationSeconds(durationMs, WhisperModel.BASE)
        )

        assertEquals("Voraussichtliche Transkriptionsdauer: ca. 8 Minuten", tiny)
        assertEquals("Voraussichtliche Transkriptionsdauer: ca. 10 Minuten", base)
    }

    @Test
    fun `runtime display combines elapsed and fixed estimate`() {
        assertEquals(
            "Laufzeit: 03:42 (≈ 04:00)",
            transcriptionRuntimeDisplay(
                elapsedSeconds = 3L * 60L + 42L,
                estimateSeconds = estimateTranscriptionDurationSeconds(
                    4L * 60L * 1_000L,
                    WhisperModel.BASE
                )
            )
        )
    }

    @Test
    fun `stored estimate is recalculated from current duration and model`() {
        val loadedFile = TranscriptUiState(
            audioDurationMs = 10L * 60L * 1_000L,
            selectedModel = WhisperModel.TINY
        ).withRecalculatedTranscriptionEstimate()
        assertEquals(8L * 60L, loadedFile.transcriptionEstimateSeconds)

        val changedQuality = loadedFile.copy(selectedModel = WhisperModel.BASE)
            .withRecalculatedTranscriptionEstimate()
        assertEquals(10L * 60L, changedQuality.transcriptionEstimateSeconds)

        val stoppedRecording = changedQuality.copy(audioDurationMs = 3L * 60L * 1_000L)
            .withRecalculatedTranscriptionEstimate()
        assertEquals(3L * 60L, stoppedRecording.transcriptionEstimateSeconds)

        val clearedFile = stoppedRecording.copy(audioDurationMs = 0L)
            .withRecalculatedTranscriptionEstimate()
        assertNull(clearedFile.transcriptionEstimateSeconds)
    }

    @Test
    fun `elapsed time is derived from the authoritative start timestamp`() {
        assertEquals(42L, elapsedSecondsSince(1_000L, 43_999L))
        assertEquals(0L, elapsedSecondsSince(5_000L, 4_000L))
    }
}
