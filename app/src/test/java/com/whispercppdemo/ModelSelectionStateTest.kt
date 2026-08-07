package de.matthiasennen.transcript.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionStateTest {
    @Test
    fun completedTranscriptionLeavesModelSelectionEnabled() {
        val completedState = TranscriptUiState(
            isBusy = false,
            isTranscribing = false,
            completedModel = WhisperModel.BASE,
            transcriptionDurationSeconds = 42L
        )

        assertTrue(completedState.isModelSelectionEnabled)
    }

    @Test
    fun activeOperationKeepsModelSelectionDisabled() {
        assertFalse(TranscriptUiState(isBusy = true).isModelSelectionEnabled)
        assertFalse(TranscriptUiState(isRecording = true).isModelSelectionEnabled)
    }
}
