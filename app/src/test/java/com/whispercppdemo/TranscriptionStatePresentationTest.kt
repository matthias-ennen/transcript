package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.transcription.TranscriptionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionStatePresentationTest {
    @Test
    fun `starting state keeps the service file name in the activity detail`() {
        val state = TranscriptUiState().presentStartingTranscription(
            TranscriptionState.Starting("interview.m4a")
        )

        assertTrue(state.isTranscribing)
        assertEquals("interview.m4a", state.activityDetail)
        assertEquals(CannaBotMode.WAITING, state.cannaBotMode)
    }

    @Test
    fun `starting state always begins with a fresh runtime basis`() {
        val state = TranscriptUiState(
            elapsedSeconds = 77L,
            pipelineTiming = TranscriptionPipelineTiming(
                whisperSeconds = 77L,
                totalSeconds = 77L,
                lastElapsedSeconds = 77L
            )
        ).presentStartingTranscription(TranscriptionState.Starting("interview.m4a"))

        assertEquals(0L, state.elapsedSeconds)
        assertEquals(0L, state.pipelineTiming.totalSeconds)
        assertEquals(0L, state.pipelineTiming.whisperSeconds)
        assertEquals(0L, state.pipelineTiming.lastElapsedSeconds)
    }

    @Test
    fun `cancelled state clears elapsed and pipeline timing`() {
        val state = TranscriptUiState(
            isTranscribing = true,
            elapsedSeconds = 42L,
            pipelineTiming = TranscriptionPipelineTiming(
                audioPreparationSeconds = 3L,
                whisperSeconds = 39L,
                totalSeconds = 42L,
                lastElapsedSeconds = 42L,
                activePhase = TranscriptionPipelinePhase.WHISPER
            )
        ).presentCancelledTranscription()

        assertFalse(state.isTranscribing)
        assertEquals(0L, state.elapsedSeconds)
        assertEquals(TranscriptionPipelineTiming(), state.pipelineTiming)
    }

    @Test
    fun `completed state captures the Whisper section length for visible grouping`() {
        val completed = TranscriptionState.Completed(
            fileName = "interview.m4a",
            model = WhisperModel.BASE,
            segments = listOf(WhisperSegment(0L, 1_000L, "Text")),
            detectedLanguage = "de",
            transcriptionDurationSeconds = 2L
        )

        val presentation = TranscriptUiState(
            whisperSettings = WhisperSettings(sectionMinutes = 2)
        ).presentCompletedTranscription(completed)

        assertEquals(2, presentation.state.transcriptSectionMinutes)
        assertEquals(2, presentation.state.effectiveTranscriptSectionMinutes())
    }

    @Test
    fun `completed state never starts legacy automatic AI correction`() {
        val completed = TranscriptionState.Completed(
            fileName = "interview.m4a",
            model = WhisperModel.BASE,
            segments = listOf(WhisperSegment(0L, 1_000L, "Text")),
            detectedLanguage = "de",
            transcriptionDurationSeconds = 2L
        )

        val presentation = TranscriptUiState(
            aiPostProcessingEnabled = true,
            automaticAiPostProcessingEnabled = true
        ).presentCompletedTranscription(completed)

        assertFalse(presentation.startsAutomaticAi)
        assertFalse(presentation.automaticAiModelMissing)
        assertFalse(presentation.state.isBusy)
        assertEquals(
            "Fertig: 1 Textabschnitte erkannt.",
            presentation.state.status
        )
    }

    @Test
    fun `failed state keeps committed segments and reports resumable interruption`() {
        val segment = WhisperSegment(0L, 1_000L, "Zwischenstand")
        val state = TranscriptUiState().presentFailedTranscription(
            TranscriptionState.Failed(
                fileName = "interview.m4a",
                message = "Worker gestoppt",
                canResume = true,
                committedSegments = listOf(segment)
            )
        )

        assertEquals(listOf(segment), state.segments)
        assertEquals("Transkription unterbrochen · Beim nächsten Start wird sie fortgesetzt.", state.status)
    }
}
