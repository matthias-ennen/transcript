package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal val TranscriptUiState.hasUnsavedTranscriptChanges: Boolean
    get() = isEditingTranscript && draftSegments != segments

internal val TranscriptUiState.hasNewlyBlankTranscriptDraft: Boolean
    get() {
        if (!isEditingTranscript || draftSegments.size != segments.size) return false
        return segments.indices.any { index ->
            segments[index].text.isNotBlank() && draftSegments[index].text.isBlank()
        }
    }

internal fun TranscriptUiState.hasUnsavedChangesInGroup(groupStartMs: Long): Boolean {
    if (!isEditingTranscript || editingTranscriptGroupStartMs != groupStartMs) return false
    if (draftSegments.size != segments.size) return false
    val sectionMinutes = effectiveTranscriptSectionMinutes()
    return segments.indices.any { index ->
        transcriptGroupStartMs(segments[index].startMs, sectionMinutes) == groupStartMs &&
            segments[index] != draftSegments[index]
    }
}

internal fun TranscriptUiState.effectiveTranscriptSectionMinutes(): Int =
    transcriptSectionMinutes?.coerceIn(1, 5)
        ?: TranscriptGroupingRuntime.currentSectionMinutes.coerceIn(1, 5)

internal fun List<WhisperSegment>.withUpdatedTranscriptText(
    index: Int,
    text: String
): List<WhisperSegment> {
    if (index !in indices) return this
    return mapIndexed { currentIndex, segment ->
        if (currentIndex == index) segment.copy(text = text) else segment
    }
}
