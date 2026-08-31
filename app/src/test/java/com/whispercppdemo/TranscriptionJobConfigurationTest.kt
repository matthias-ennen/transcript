package de.matthiasennen.transcript.transcription

import de.matthiasennen.transcript.song.SongSeparationModel
import de.matthiasennen.transcript.song.TranscriptionMode
import de.matthiasennen.transcript.ui.main.WhisperComputeBackend
import de.matthiasennen.transcript.ui.main.WhisperDecoding
import de.matthiasennen.transcript.ui.main.WhisperSettings
import de.matthiasennen.transcript.ui.main.WhisperTimestampMode
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TranscriptionJobConfigurationTest {
    @Test
    fun `snapshot preserves every worker relevant setting`() {
        val settings = WhisperSettings(
            initialPrompt = "Kontext äöü",
            threads = 3,
            backend = WhisperComputeBackend.VULKAN,
            decoding = WhisperDecoding.BEAM_SEARCH,
            beamSize = 7,
            bestOf = 4,
            temperaturePercent = 15,
            carryContext = false,
            maximumSegmentCharacters = 123,
            splitOnWord = false,
            timestampMode = WhisperTimestampMode.WORDS,
            suppressBlank = false,
            suppressNonSpeechTokens = true,
            logProbabilityThresholdPercent = -75,
            noSpeechThresholdPercent = 55,
            entropyThresholdPercent = 210,
            sectionMinutes = 5,
            vadMode = WhisperVadMode.ON,
            vadThresholdPercent = 61,
            vadMinSpeechDurationMs = 420,
            vadMinSilenceDurationMs = 230,
            vadMaxSpeechDurationSeconds = 240,
            vadSpeechPadMs = 180,
            vadOverlapMs = 320
        )
        val configuration = TranscriptionJobConfiguration("large-v3-q5", "de", settings)

        assertEquals(configuration.normalized(), TranscriptionJobConfiguration.decode(configuration.encode()))
    }

    @Test
    fun `song snapshot survives worker and process restart`() {
        val configuration = TranscriptionJobConfiguration(
            modelId = "base",
            language = "auto",
            whisperSettings = WhisperSettings(vadMode = WhisperVadMode.ON),
            transcriptionMode = TranscriptionMode.SONG,
            songSeparationModelId = SongSeparationModel.HIGH_QUALITY.id
        )

        val restored = TranscriptionJobConfiguration.decode(configuration.encode())

        assertEquals(TranscriptionMode.SONG, restored.transcriptionMode)
        assertEquals(SongSeparationModel.HIGH_QUALITY.id, restored.songSeparationModelId)
    }

    @Test
    fun `changed section length creates a different immutable snapshot`() {
        val oneMinute = TranscriptionJobConfiguration(
            modelId = "base",
            language = "auto",
            whisperSettings = WhisperSettings(sectionMinutes = 1)
        )
        val twoMinutes = oneMinute.copy(
            whisperSettings = oneMinute.whisperSettings.copy(sectionMinutes = 2)
        )

        assertNotEquals(oneMinute.encode(), twoMinutes.encode())
        assertEquals(1, TranscriptionJobConfiguration.decode(oneMinute.encode()).whisperSettings.sectionMinutes)
        assertEquals(2, TranscriptionJobConfiguration.decode(twoMinutes.encode()).whisperSettings.sectionMinutes)
    }

    @Test
    fun `invalid stored values are normalized before worker use`() {
        val configuration = TranscriptionJobConfiguration(
            modelId = " base ",
            language = "",
            whisperSettings = WhisperSettings(sectionMinutes = 99, beamSize = 99)
        )

        val restored = TranscriptionJobConfiguration.decode(configuration.encode())

        assertEquals("base", restored.modelId)
        assertEquals("auto", restored.language)
        assertEquals(5, restored.whisperSettings.sectionMinutes)
        assertEquals(20, restored.whisperSettings.beamSize)
    }
}
