package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal const val TRANSCRIPT_GROUP_DURATION_MS = 5L * 60L * 1_000L

internal data class IndexedTranscriptSegment(
    val originalIndex: Int,
    val segment: WhisperSegment
)

internal data class TranscriptGroup(
    val startMs: Long,
    val endMs: Long,
    val segments: List<IndexedTranscriptSegment>
)

internal fun transcriptGroupStartMs(timestampMs: Long): Long {
    val safeTimestamp = timestampMs.coerceAtLeast(0L)
    return safeTimestamp / TRANSCRIPT_GROUP_DURATION_MS * TRANSCRIPT_GROUP_DURATION_MS
}

internal fun groupTranscriptSegments(segments: List<WhisperSegment>): List<TranscriptGroup> =
    segments.mapIndexed { index, segment ->
        transcriptGroupStartMs(segment.startMs) to IndexedTranscriptSegment(index, segment)
    }.groupBy(
        keySelector = { it.first },
        valueTransform = { it.second }
    ).toSortedMap().map { (startMs, indexedSegments) ->
        TranscriptGroup(
            startMs = startMs,
            endMs = startMs + TRANSCRIPT_GROUP_DURATION_MS,
            segments = indexedSegments
        )
    }

internal fun applyTranscriptGroupEdits(
    original: List<WhisperSegment>,
    draft: List<WhisperSegment>,
    groupStartMs: Long
): List<WhisperSegment> {
    if (original.size != draft.size) return original
    return original.mapIndexed { index, segment ->
        if (transcriptGroupStartMs(segment.startMs) == groupStartMs) draft[index] else segment
    }
}
