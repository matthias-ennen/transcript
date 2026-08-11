package de.matthiasennen.transcript.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperSettingsTest {
    @Test
    fun `unsafe values are normalized`() {
        val value = WhisperSettings(
            threads = 99,
            beamSize = 0,
            temperaturePercent = 500,
            sectionMinutes = 0
        ).normalized(processors = 8)

        assertEquals(8, value.threads)
        assertEquals(1, value.beamSize)
        assertEquals(100, value.temperaturePercent)
        assertEquals(1, value.sectionMinutes)
    }

    @Test
    fun `group reset preserves unrelated settings`() {
        val value = WhisperSettings(initialPrompt = "ENERCON", beamSize = 12, sectionMinutes = 9)
            .reset(WhisperSettingsGroup.DECODING)

        assertEquals("ENERCON", value.initialPrompt)
        assertEquals(WhisperSettings().beamSize, value.beamSize)
        assertEquals(9, value.sectionMinutes)
    }

    @Test
    fun `native configuration reflects decoding and backend`() {
        val configuration = WhisperSettings(
            backend = WhisperComputeBackend.CPU,
            decoding = WhisperDecoding.BEAM_SEARCH,
            timestampMode = WhisperTimestampMode.WORDS
        ).toNativeConfiguration()

        assertFalse(configuration.useGpu)
        assertTrue(configuration.beamSearch)
        assertTrue(configuration.tokenTimestamps)
    }
}
