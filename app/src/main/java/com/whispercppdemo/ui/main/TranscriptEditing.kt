package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal val TranscriptUiState.hasUnsavedTranscriptChanges: Boolean
    get() = isEditingTranscript && draftSegments != segments

internal fun List<WhisperSegment>.withUpdatedTranscriptText(
    index: Int,
    text: String
): List<WhisperSegment> {
    if (index !in indices) return this
    return mapIndexed { currentIndex, segment ->
        if (currentIndex == index) segment.copy(text = text) else segment
    }
}
