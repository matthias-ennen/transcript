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
        val sectionMinutes = state.effectiveTranscriptSectionMinutes()
        TranscriptGroupingRuntime.use(sectionMinutes)
        if (state.segments.none {
                transcriptGroupStartMs(it.startMs, sectionMinutes) == groupStartMs
            }
        ) return null
        val editSource = if (state.transcriptView == TranscriptViewMode.ORIGINAL) {
            state.originalTranscriptSegments()
        } else {
            state.segments
        }
        return state.copy(
            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = editSource,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL
        )
    }

    fun updateDraft(state: TranscriptUiState, index: Int, text: String): TranscriptUiState? {
        if (!state.isEditingTranscript || state.isAiPostProcessing) return null
        val groupStartMs = state.editingTranscriptGroupStartMs ?: return null
        val segment = state.segments.getOrNull(index) ?: return null
        val sectionMinutes = state.effectiveTranscriptSectionMinutes()
        if (transcriptGroupStartMs(segment.startMs, sectionMinutes) != groupStartMs) return null
        return state.copy(draftSegments = state.draftSegments.withUpdatedTranscriptText(index, text))
    }

    fun cancelEditing(state: TranscriptUiState): TranscriptUiState? {
        if (!state.isEditingTranscript || state.isAiPostProcessing) return null
        return state.copy(
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL
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
            groupStartMs = groupStartMs,
            sectionMinutes = state.effectiveTranscriptSectionMinutes()
        )
    }

    fun persist(state: TranscriptUiState, displayedSegments: List<WhisperSegment>) {
        if (displayedSegments.isEmpty()) return
        val model = state.completedModel ?: return
        val sectionMinutes = state.effectiveTranscriptSectionMinutes()
        TranscriptGroupingRuntime.use(sectionMinutes)
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
                vadSummary = state.vadProcessingSummary,
                sectionMinutes = sectionMinutes,
                segmentOrigins = state.segmentOrigins,
                transcriptView = state.transcriptView
            )
        )
    }

    fun restoreWithoutSource(state: TranscriptUiState, stored: StoredTranscriptResult): TranscriptUiState {
        val sectionMinutes = TranscriptGroupingRuntime.use(stored.sectionMinutes)
        val origins = stored.segmentOrigins.takeIf { it.isNotEmpty() }
            ?: defaultTranscriptOrigins(stored.displayedSegments, stored.rawWhisperSegments)
        return state.copy(
            selectedAudio = null,
            selectedFileName = stored.fileName,
            rawWhisperSegments = stored.rawWhisperSegments,
            segments = stored.displayedSegments,
            transcriptSectionMinutes = sectionMinutes,
            segmentOrigins = origins,
            transcriptView = stored.transcriptView,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            aiBaselineSegments = emptyList(),
            aiBaselineOrigins = emptyMap(),
            detectedLanguage = stored.detectedLanguage,
            completedModel = WhisperModel.fromId(stored.modelId),
            transcriptionDurationSeconds = stored.transcriptionDurationSeconds,
            vadProcessingSummary = stored.vadSummary,
            status = "Gespeichertes Transkript wiederhergestellt: " +
                "${stored.displayedSegments.count { it.text.isNotBlank() }} Textabschnitte.",
            cannaBotMode = CannaBotMode.IDLE
        )
    }
}
