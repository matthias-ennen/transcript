package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.TranscriptExportMetadata
import de.matthiasennen.transcript.export.exportTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEditingTest {
    private val original = listOf(
        WhisperSegment(1_000, 2_000, "First line"),
        WhisperSegment(2_000, 3_000, "Second line")
    )

    @Test
    fun updatingTextPreservesTimestampsAndOtherSegments() {
        val updated = original.withUpdatedTranscriptText(1, "Corrected line")

        assertEquals(original[0], updated[0])
        assertEquals(2_000L, updated[1].startMs)
        assertEquals(3_000L, updated[1].endMs)
        assertEquals("Corrected line", updated[1].text)
    }

    @Test
    fun unsavedChangesOnlyExistForDifferentEditingDraft() {
        assertFalse(
            TranscriptUiState(
                segments = original,
                isEditingTranscript = true,
                draftSegments = original
            ).hasUnsavedTranscriptChanges
        )
        assertTrue(
            TranscriptUiState(
                segments = original,
                isEditingTranscript = true,
                draftSegments = original.withUpdatedTranscriptText(0, "Changed")
            ).hasUnsavedTranscriptChanges
        )
        assertFalse(
            TranscriptUiState(
                segments = original,
                draftSegments = original.withUpdatedTranscriptText(0, "Changed")
            ).hasUnsavedTranscriptChanges
        )
    }

    @Test
    fun groupChangesAreReportedOnlyForTheActiveGroup() {
        val state = TranscriptUiState(
            segments = original,
            isEditingTranscript = true,
            editingTranscriptGroupStartMs = 0L,
            draftSegments = original.withUpdatedTranscriptText(0, "Changed")
        )

        assertTrue(state.hasUnsavedChangesInGroup(0L))
        assertFalse(state.hasUnsavedChangesInGroup(TRANSCRIPT_GROUP_DURATION_MS))
    }

    @Test
    fun correctedTextIsUsedByAllExportFormats() {
        val corrected = original.withUpdatedTranscriptText(1, "Corrected line")
        val metadata = TranscriptExportMetadata(
            whisperModel = "Whisper Base",
            detectedLanguage = "en",
            transcriptionDurationSeconds = 10L,
            createdAt = "2026-08-07T12:00:00Z"
        )

        ExportFormat.entries.forEach { format ->
            val exported = exportTranscript(corrected, format, metadata)
            assertTrue(exported.contains("Corrected line"))
            assertFalse(exported.contains("Second line"))
        }
    }
}
