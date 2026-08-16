package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.WhisperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptionCheckpointStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `checkpoint roundtrip preserves request progress language and segments`() {
        val file = temporaryFolder.newFile("checkpoint.bin")
        assertTrue(file.delete())
        val store = TranscriptionCheckpointStore(file)
        val request = TranscriptionRequest(
            uri = "content://audio/long-recording",
            fileName = "Aufnahme.m4a",
            configuration = TranscriptionJobConfiguration(
                modelId = "base",
                language = "auto",
                whisperSettings = WhisperSettings(sectionMinutes = 5)
            ),
            jobId = "job-42"
        )
        val checkpoint = TranscriptionCheckpoint(
            request = request,
            durationMs = 4_145_000L,
            nextStartMs = 600_000L,
            detectedLanguage = "de",
            startedAtEpochMs = 1_700_000_000_000L,
            segments = listOf(
                WhisperSegment(1_000L, 2_000L, "Erster Satz"),
                WhisperSegment(599_000L, 601_000L, "Grenzsatz mit Umlaut ä")
            )
        )

        store.write(checkpoint)

        assertEquals(checkpoint, store.read())
        assertTrue(checkpoint.isCompatibleWith(request, 4_145_000L))
        assertTrue(checkpoint.isCompatibleWith(request.copy(jobId = "new-process"), 4_145_000L))
        assertFalse(
            checkpoint.isCompatibleWith(
                request.copy(configuration = request.configuration.copy(language = "en")),
                4_145_000L
            )
        )
        assertFalse(
            checkpoint.isCompatibleWith(
                request.copy(
                    configuration = request.configuration.copy(
                        whisperSettings = request.configuration.whisperSettings.copy(sectionMinutes = 2)
                    )
                ),
                4_145_000L
            )
        )
        assertTrue(checkpoint.hasMeaningfulProgress())
    }

    @Test
    fun `checkpoint at zero is not offered as resumable progress`() {
        val checkpoint = TranscriptionCheckpoint(
            request = TranscriptionRequest(
                uri = "content://audio/recording",
                fileName = "Aufnahme.m4a",
                configuration = TranscriptionJobConfiguration(
                    modelId = "tiny",
                    language = "auto",
                    whisperSettings = WhisperSettings()
                )
            ),
            durationMs = 60_000L,
            nextStartMs = 0L,
            detectedLanguage = null,
            startedAtEpochMs = 1_700_000_000_000L,
            segments = emptyList()
        )

        assertFalse(checkpoint.hasMeaningfulProgress())
    }

    @Test
    fun `corrupt checkpoint is rejected and removed`() {
        val file = temporaryFolder.newFile("checkpoint.bin")
        file.writeText("kein gültiger Zwischenstand")
        val store = TranscriptionCheckpointStore(file)

        assertNull(store.read())
        assertFalse(file.exists())
    }
}
