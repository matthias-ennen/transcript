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
            sectionMinutes = 0,
            vadThresholdPercent = 100,
            vadMinSpeechDurationMs = 1,
            vadOverlapMs = 2_000
        ).normalized(processors = 8)

        assertEquals(8, value.threads)
        assertEquals(1, value.beamSize)
        assertEquals(100, value.temperaturePercent)
        assertEquals(1, value.sectionMinutes)
        assertEquals(90, value.vadThresholdPercent)
        assertEquals(50, value.vadMinSpeechDurationMs)
        assertEquals(1_000, value.vadOverlapMs)
    }

    @Test
    fun `vad reset preserves other groups`() {
        val value = WhisperSettings(
            initialPrompt = "ENERCON",
            vadMode = WhisperVadMode.ON,
            vadThresholdPercent = 80
        ).reset(WhisperSettingsGroup.VAD)

        assertEquals("ENERCON", value.initialPrompt)
        assertEquals(WhisperSettings().vadMode, value.vadMode)
        assertEquals(WhisperSettings().vadThresholdPercent, value.vadThresholdPercent)
    }

    @Test
    fun `native configuration enables vad only with model path`() {
        val withoutModel = WhisperSettings(vadMode = WhisperVadMode.ON).toNativeConfiguration()
        val withModel = WhisperSettings(
            vadMode = WhisperVadMode.ON,
            vadThresholdPercent = 55,
            vadSpeechPadMs = 120
        ).toNativeConfiguration("/data/vad.bin")

        assertEquals(null, withoutModel.vadModelPath)
        assertEquals("/data/vad.bin", withModel.vadModelPath)
        assertEquals(0.55f, withModel.vadThreshold)
        assertEquals(120, withModel.vadSpeechPadMs)
    }

    @Test
    fun `off mode never passes a vad model to whisper`() {
        val configuration = WhisperSettings(vadMode = WhisperVadMode.OFF)
            .toNativeConfiguration("/data/vad.bin")

        assertEquals(null, configuration.vadModelPath)
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
