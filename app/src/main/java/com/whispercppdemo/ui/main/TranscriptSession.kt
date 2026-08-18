package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.transcription.StoredTranscriptResult
import de.matthiasennen.transcript.transcription.TranscriptResultPersistence

/** Owns edit transitions and persistence for the single transcript shown by the app. */
internal class TranscriptSession(
    private val persistence: TranscriptResultPersistence
) {
    fun beginEditing(state: TranscriptUiState, groupStartMs: Long): TranscriptUiState? {
        if (state.isBusy || state.segments.isEmpty()) return null
        if (state.segments.none { transcriptGroupStartMs(it.startMs) == groupStartMs }) return null
        return state.copy(
            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = state.segments
        )
    }

    fun updateDraft(state: TranscriptUiState, index: Int, text: String): TranscriptUiState? {
        if (!state.isEditingTranscript || state.isAiPostProcessing) return null
        val groupStartMs = state.editingTranscriptGroupStartMs ?: return null
        val segment = state.segments.getOrNull(index) ?: return null
        if (transcriptGroupStartMs(segment.startMs) != groupStartMs) return null
        return state.copy(draftSegments = state.draftSegments.withUpdatedTranscriptText(index, text))
    }

    fun cancelEditing(state: TranscriptUiState): TranscriptUiState? {
        if (!state.isEditingTranscript || state.isAiPostProcessing) return null
        return state.copy(
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList()
        )
    }

    fun applyEdits(state: TranscriptUiState): List<WhisperSegment>? {
        val groupStartMs = state.editingTranscriptGroupStartMs
        if (
            !state.isEditingTranscript || state.isAiPostProcessing || groupStartMs == null ||
            state.draftSegments.size != state.segments.size
        ) return null
        return applyTranscriptGroupEdits(
            original = state.segments,
            draft = state.draftSegments,
            groupStartMs = groupStartMs
        )
    }

    fun persist(state: TranscriptUiState, displayedSegments: List<WhisperSegment>) {
        if (displayedSegments.isEmpty()) return
        val model = state.completedModel ?: return
        persistence.save(
            StoredTranscriptResult(
                sourceUri = state.selectedAudio?.toString().orEmpty(),
                fileName = state.selectedFileName ?: "Transkript",
                modelId = model.id,
                detectedLanguage = state.detectedLanguage.orEmpty(),
                transcriptionDurationSeconds = state.transcriptionDurationSeconds ?: 0L,
                savedAtEpochMs = System.currentTimeMillis(),
                rawWhisperSegments = state.rawWhisperSegments,
                displayedSegments = displayedSegments,
                vadSummary = state.vadProcessingSummary
            )
        )
    }

    fun restoreWithoutSource(state: TranscriptUiState, stored: StoredTranscriptResult): TranscriptUiState =
        state.copy(
            selectedAudio = null,
            selectedFileName = stored.fileName,
            rawWhisperSegments = stored.rawWhisperSegments,
            segments = stored.displayedSegments,
            detectedLanguage = stored.detectedLanguage,
            completedModel = WhisperModel.fromId(stored.modelId),
            transcriptionDurationSeconds = stored.transcriptionDurationSeconds,
            vadProcessingSummary = stored.vadSummary,
            status = "Gespeichertes Transkript wiederhergestellt: " +
                "${stored.displayedSegments.count { it.text.isNotBlank() }} Textabschnitte.",
            cannaBotMode = CannaBotMode.IDLE
        )
}
