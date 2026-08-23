package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal const val DEFAULT_TRANSCRIPT_GROUP_MINUTES = 5
private const val MIN_TRANSCRIPT_GROUP_MINUTES = 1
private const val MAX_TRANSCRIPT_GROUP_MINUTES = 5

/**
 * Runtime fallback for the one transcript currently shown by the app.
 *
 * A completed transcript captures its own section length. Persisted transcripts restore that
 * value here as well, so later changes in the Whisper settings never regroup an existing result.
 */
internal object TranscriptGroupingRuntime {
    var currentSectionMinutes: Int = DEFAULT_TRANSCRIPT_GROUP_MINUTES
        private set

    fun use(sectionMinutes: Int): Int {
        currentSectionMinutes = sectionMinutes.coerceIn(
            MIN_TRANSCRIPT_GROUP_MINUTES,
            MAX_TRANSCRIPT_GROUP_MINUTES
        )
        return currentSectionMinutes
    }

    fun reset() {
        currentSectionMinutes = DEFAULT_TRANSCRIPT_GROUP_MINUTES
    }
}

internal fun transcriptGroupDurationMs(
    sectionMinutes: Int = TranscriptGroupingRuntime.currentSectionMinutes
): Long = sectionMinutes.coerceIn(
    MIN_TRANSCRIPT_GROUP_MINUTES,
    MAX_TRANSCRIPT_GROUP_MINUTES
).toLong() * 60L * 1_000L

internal data class IndexedTranscriptSegment(
    val originalIndex: Int,
    val segmentId: Long,
    val segment: WhisperSegment
)

internal data class TranscriptGroup(
    val startMs: Long,
    val endMs: Long,
    val segments: List<IndexedTranscriptSegment>
)

/** Stable for a segment as long as its immutable timeline range/order stays unchanged. */
internal fun stableTranscriptSegmentId(index: Int, segment: WhisperSegment): Long {
    var hash = 1_469_598_103_934_665_603L
    hash = (hash xor index.toLong()) * 1_099_511_628_211L
    hash = (hash xor segment.startMs) * 1_099_511_628_211L
    hash = (hash xor segment.endMs) * 1_099_511_628_211L
    return hash
}

internal fun transcriptGroupStartMs(
    timestampMs: Long,
    sectionMinutes: Int = TranscriptGroupingRuntime.currentSectionMinutes
): Long {
    val safeTimestamp = timestampMs.coerceAtLeast(0L)
    val durationMs = transcriptGroupDurationMs(sectionMinutes)
    return safeTimestamp / durationMs * durationMs
}

internal fun groupTranscriptSegments(
    segments: List<WhisperSegment>,
    sectionMinutes: Int = TranscriptGroupingRuntime.currentSectionMinutes
): List<TranscriptGroup> {
    val normalizedMinutes = TranscriptGroupingRuntime.use(sectionMinutes)
    val durationMs = transcriptGroupDurationMs(normalizedMinutes)
    return segments.mapIndexed { index, segment ->
        transcriptGroupStartMs(segment.startMs, normalizedMinutes) to IndexedTranscriptSegment(
            originalIndex = index,
            segmentId = stableTranscriptSegmentId(index, segment),
            segment = segment
        )
    }.groupBy(
        keySelector = { it.first },
        valueTransform = { it.second }
    ).toSortedMap().map { (startMs, indexedSegments) ->
        TranscriptGroup(
            startMs = startMs,
            endMs = startMs + durationMs,
            segments = indexedSegments
        )
    }
}

internal fun applyTranscriptGroupEdits(
    original: List<WhisperSegment>,
    draft: List<WhisperSegment>,
    groupStartMs: Long,
    sectionMinutes: Int = TranscriptGroupingRuntime.currentSectionMinutes
): List<WhisperSegment> {
    if (original.size != draft.size) return original
    return original.mapIndexed { index, segment ->
        if (transcriptGroupStartMs(segment.startMs, sectionMinutes) == groupStartMs) {
            draft[index]
        } else {
            segment
        }
    }
}
