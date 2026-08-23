package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.transcription.TranscriptResultPersistence
import de.matthiasennen.transcript.transcription.TranscriptResultStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptSessionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `editing only changes the selected transcript group`() {
        val session = session()
        val state = TranscriptUiState(
            transcriptSectionMinutes = 1,
            segments = listOf(
                WhisperSegment(0L, 1_000L, "Erster Satz"),
                WhisperSegment(1_000L, 2_000L, "Zweiter Satz"),
                WhisperSegment(60_000L, 61_000L, "Nächste Gruppe")
            )
        )

        val editing = session.beginEditing(state, groupStartMs = 0L)!!
        val changed = session.updateDraft(editing, index = 1, text = "Korrigierter Satz")!!
        assertNull(session.updateDraft(changed, index = 2, text = "Falsche Gruppe"))
        val applied = session.applyEdits(changed)!!

        assertEquals("Korrigierter Satz", applied[1].text)
        assertEquals("Nächste Gruppe", applied[2].text)
    }

    @Test
    fun `editing cannot start for an unknown group and cancellation clears its draft`() {
        val session = session()
        val state = TranscriptUiState(
            transcriptSectionMinutes = 1,
            segments = listOf(WhisperSegment(0L, 1_000L, "Text"))
        )

        assertNull(session.beginEditing(state, groupStartMs = 60_000L))

        val editing = session.beginEditing(state, groupStartMs = 0L)!!
        val cancelled = session.cancelEditing(editing)!!
        assertFalse(cancelled.isEditingTranscript)
        assertTrue(cancelled.draftSegments.isEmpty())
    }

    private fun session(): TranscriptSession = TranscriptSession(
        TranscriptResultPersistence(
            TranscriptResultStore(temporaryFolder.newFile("transcript.bin"))
        )
    )
}
