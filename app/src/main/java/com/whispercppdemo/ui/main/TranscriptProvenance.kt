package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

enum class TranscriptSegmentOrigin { ORIGINAL, MANUAL, AI }

enum class TranscriptViewMode { ORIGINAL, EDITED }

internal fun defaultTranscriptOrigins(
    segments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>
): Map<Long, TranscriptSegmentOrigin> = buildMap {
    segments.forEachIndexed { index, segment ->
        put(
            stableTranscriptSegmentId(index, segment),
            if (
                rawWhisperSegments.isEmpty() ||
                segment.text == originalTranscriptText(segment, rawWhisperSegments)
            ) {
                TranscriptSegmentOrigin.ORIGINAL
            } else {
                TranscriptSegmentOrigin.MANUAL
            }
        )
    }
}

internal fun acceptedTranscriptOrigin(
    index: Int,
    segment: WhisperSegment,
    rawWhisperSegments: List<WhisperSegment>,
    origins: Map<Long, TranscriptSegmentOrigin>
): TranscriptSegmentOrigin {
    if (rawWhisperSegments.isEmpty()) return TranscriptSegmentOrigin.ORIGINAL
    val originalText = originalTranscriptText(segment, rawWhisperSegments)
    if (segment.text == originalText) return TranscriptSegmentOrigin.ORIGINAL
    return origins[stableTranscriptSegmentId(index, segment)]
        ?.takeUnless { it == TranscriptSegmentOrigin.ORIGINAL }
        ?: TranscriptSegmentOrigin.MANUAL
}

internal fun TranscriptUiState.acceptedTranscriptOrigin(index: Int): TranscriptSegmentOrigin =
    segments.getOrNull(index)?.let { segment ->
        acceptedTranscriptOrigin(index, segment, rawWhisperSegments, segmentOrigins)
    } ?: TranscriptSegmentOrigin.ORIGINAL

internal fun updateTranscriptOrigins(
    previousSegments: List<WhisperSegment>,
    updatedSegments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>,
    existingOrigins: Map<Long, TranscriptSegmentOrigin>,
    changedOrigin: TranscriptSegmentOrigin
): Map<Long, TranscriptSegmentOrigin> = buildMap {
    updatedSegments.forEachIndexed { index, segment ->
        val id = stableTranscriptSegmentId(index, segment)
        val originalText = originalTranscriptText(segment, rawWhisperSegments)
        val origin = when {
            rawWhisperSegments.isEmpty() || segment.text == originalText ->
                TranscriptSegmentOrigin.ORIGINAL
            previousSegments.getOrNull(index)?.text != segment.text -> changedOrigin
            else -> existingOrigins[id]
                ?.takeUnless { it == TranscriptSegmentOrigin.ORIGINAL }
                ?: TranscriptSegmentOrigin.MANUAL
        }
        put(id, origin)
    }
}

internal fun reconcileTranscriptOrigins(
    segments: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>,
    existingOrigins: Map<Long, TranscriptSegmentOrigin>
): Map<Long, TranscriptSegmentOrigin> = buildMap {
    segments.forEachIndexed { index, segment ->
        val id = stableTranscriptSegmentId(index, segment)
        put(
            id,
            acceptedTranscriptOrigin(index, segment, rawWhisperSegments, existingOrigins)
        )
    }
}

internal fun TranscriptUiState.originalTranscriptSegments(): List<WhisperSegment> {
    if (rawWhisperSegments.isEmpty()) return segments
    return segments.map { segment ->
        segment.copy(text = originalTranscriptText(segment, rawWhisperSegments))
    }
}

internal fun TranscriptUiState.transcriptSegmentsForSelectedView(): List<WhisperSegment> = when {
    isEditingTranscript && draftSegments.size == segments.size -> draftSegments
    transcriptView == TranscriptViewMode.ORIGINAL && rawWhisperSegments.isNotEmpty() ->
        originalTranscriptSegments()
    else -> segments
}

internal fun TranscriptUiState.exportSegmentsForSelectedView(): List<WhisperSegment> =
    if (transcriptView == TranscriptViewMode.ORIGINAL && rawWhisperSegments.isNotEmpty()) {
        originalTranscriptSegments()
    } else {
        segments
    }

internal val TranscriptViewMode.displayLabel: String
    get() = when (this) {
        TranscriptViewMode.ORIGINAL -> "Whisper-Original"
        TranscriptViewMode.EDITED -> "Nachbearbeitet"
    }
