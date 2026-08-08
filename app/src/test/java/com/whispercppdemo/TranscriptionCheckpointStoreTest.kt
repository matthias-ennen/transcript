package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
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
            modelId = "base",
            language = "auto"
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
        assertFalse(checkpoint.isCompatibleWith(request.copy(language = "en"), 4_145_000L))
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

