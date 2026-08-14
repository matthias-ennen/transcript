package de.matthiasennen.transcript

import de.matthiasennen.transcript.transcription.VadAutomaticDecision
import de.matthiasennen.transcript.transcription.analyzedVadSummary
import de.matthiasennen.transcript.transcription.fullAudioVadSummary
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadProcessingSummaryTest {
    private val decision = VadAutomaticDecision(
        useVad = true,
        reason = "klare längere Pausen",
        silencePercent = 40,
        speechPercent = 60,
        longestSilenceMs = 4_000L,
        speechSegmentCount = 8,
        analyzedSampleCount = 16_000L * 100L,
        detectedSpeechSampleCount = 16_000L * 60L
    )

    @Test
    fun `used vad reports actual processed and skipped duration`() {
        val summary = analyzedVadSummary(
            mode = WhisperVadMode.AUTOMATIC,
            useVad = true,
            durationMs = 100_000L,
            decision = decision
        )

        assertTrue(summary.usedVad)
        assertEquals(60_000L, summary.processedDurationMs)
        assertEquals(40_000L, summary.skippedDurationMs)
        assertEquals(8, summary.speechRegionCount)
    }

    @Test
    fun `rejected automatic vad reports full audio as processed`() {
        val summary = analyzedVadSummary(
            mode = WhisperVadMode.AUTOMATIC,
            useVad = false,
            durationMs = 100_000L,
            decision = decision.copy(useVad = false)
        )

        assertFalse(summary.usedVad)
        assertEquals(100_000L, summary.processedDurationMs)
        assertEquals(0L, summary.skippedDurationMs)
    }

    @Test
    fun `disabled vad reports the complete original duration`() {
        val summary = fullAudioVadSummary(
            WhisperVadMode.OFF,
            42_000L,
            "ausgeschaltet"
        )

        assertFalse(summary.usedVad)
        assertEquals(42_000L, summary.originalDurationMs)
        assertEquals(42_000L, summary.processedDurationMs)
        assertEquals(0L, summary.skippedDurationMs)
    }
}
