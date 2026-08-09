package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal val TranscriptUiState.hasUnsavedTranscriptChanges: Boolean
    get() = isEditingTranscript && draftSegments != segments

internal val TranscriptUiState.selectedAiModelInstalled: Boolean
    get() = aiModelInstallations.firstOrNull { it.model == selectedAiModel }?.isInstalled == true

internal fun TranscriptUiState.hasUnsavedChangesInGroup(groupStartMs: Long): Boolean {
    if (!isEditingTranscript || editingTranscriptGroupStartMs != groupStartMs) return false
    if (draftSegments.size != segments.size) return false
    return segments.indices.any { index ->
        transcriptGroupStartMs(segments[index].startMs) == groupStartMs &&
            segments[index] != draftSegments[index]
    }
}

internal fun List<WhisperSegment>.withUpdatedTranscriptText(
    index: Int,
    text: String
): List<WhisperSegment> {
    if (index !in indices) return this
    return mapIndexed { currentIndex, segment ->
        if (currentIndex == index) segment.copy(text = text) else segment
    }
}
