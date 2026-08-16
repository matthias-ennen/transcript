package de.matthiasennen.transcript.transcription

import de.matthiasennen.transcript.ui.main.WhisperSettings

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.TranscriptExportMetadata
import de.matthiasennen.transcript.export.exportTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FoundationPersistenceIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `checkpoint can become durable edited result and export after restart`() {
        val rawSegments = listOf(
            WhisperSegment(0L, 1_000L, "Das ist der Rohtext"),
            WhisperSegment(1_000L, 2_000L, "mit ein Fehler")
        )
        val request = TranscriptionRequest(
            uri = "content://recording/42",
            fileName = "Aufnahme.m4a",
            configuration = TranscriptionJobConfiguration(
                modelId = "base",
                language = "auto",
                whisperSettings = WhisperSettings()
            )
        )
        val checkpointFile = temporaryFolder.newFile("active.bin").also { it.delete() }
        val checkpointStore = TranscriptionCheckpointStore(checkpointFile)
        checkpointStore.write(
            TranscriptionCheckpoint(
                request = request,
                durationMs = 2_000L,
                nextStartMs = 2_000L,
                detectedLanguage = "de",
                startedAtEpochMs = 1L,
                segments = rawSegments
            )
        )
        val completed = checkNotNull(checkpointStore.read())
        val editedSegments = completed.segments.toMutableList().apply {
            this[1] = this[1].copy(text = "mit einem Fehler")
        }
        val resultFile = temporaryFolder.newFile("current.bin").also { it.delete() }
        TranscriptResultStore(resultFile).write(
            StoredTranscriptResult(
                sourceUri = request.uri,
                fileName = request.fileName,
                modelId = request.modelId,
                detectedLanguage = checkNotNull(completed.detectedLanguage),
                transcriptionDurationSeconds = 12L,
                savedAtEpochMs = 42L,
                rawWhisperSegments = completed.segments,
                displayedSegments = editedSegments
            )
        )
        checkpointStore.clear()

        val restored = checkNotNull(TranscriptResultStore(resultFile).read())
        val exported = exportTranscript(
            segments = restored.displayedSegments,
            format = ExportFormat.TEXT,
            metadata = TranscriptExportMetadata(
                whisperModel = restored.modelId,
                detectedLanguage = restored.detectedLanguage,
                transcriptionDurationSeconds = restored.transcriptionDurationSeconds,
                createdAt = "2026-08-11T00:00:00Z"
            )
        )

        assertEquals(rawSegments, restored.rawWhisperSegments)
        assertTrue(exported.contains("mit einem Fehler"))
        assertTrue(!exported.contains("mit ein Fehler"))
        assertTrue(!checkpointFile.exists())
    }
}
