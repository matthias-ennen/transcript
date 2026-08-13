package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal const val SEGMENT_START_TOLERANCE_MS = 500L
internal const val FLOATING_TRANSCRIPT_CONTROL_ALPHA = 0.62f

internal data class ActiveTranscriptSegment(
    val index: Int,
    val progress: Float
)

internal fun activeTranscriptSegment(
    segments: List<WhisperSegment>,
    positionMs: Long
): ActiveTranscriptSegment? {
    val index = segments.lastStartAtOrBefore(positionMs)
    if (index < 0) return null
    val segment = segments[index]
    val isAtFinalEnd = index == segments.lastIndex && positionMs == segment.endMs
    if (positionMs >= segment.endMs && !isAtFinalEnd) return null
    val durationMs = segment.endMs - segment.startMs
    val progress = if (durationMs > 0L) {
        ((positionMs - segment.startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    return ActiveTranscriptSegment(index = index, progress = progress)
}

internal fun previousTranscriptSegmentPositionMs(
    segments: List<WhisperSegment>,
    positionMs: Long,
    startToleranceMs: Long = SEGMENT_START_TOLERANCE_MS
): Long? {
    if (segments.isEmpty()) return null
    val currentIndex = segments.lastStartAtOrBefore(positionMs)
    if (currentIndex < 0) return segments.first().startMs
    val current = segments[currentIndex]
    return if (positionMs > current.startMs + startToleranceMs) {
        current.startMs
    } else {
        segments.getOrNull(currentIndex - 1)?.startMs ?: current.startMs
    }
}

internal fun nextTranscriptSegmentPositionMs(
    segments: List<WhisperSegment>,
    positionMs: Long
): Long? {
    var low = 0
    var high = segments.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (segments[middle].startMs <= positionMs) low = middle + 1 else high = middle
    }
    return segments.getOrNull(low)?.startMs
}

private fun List<WhisperSegment>.lastStartAtOrBefore(positionMs: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].startMs <= positionMs) low = middle + 1 else high = middle
    }
    return low - 1
}

internal fun shouldShowFloatingTranscriptControls(
    hasCompletedTranscript: Boolean,
    transcriptHeadingBottomPx: Float?,
    exportActionsTopPx: Float?,
    exportActionsBottomPx: Float?,
    viewportTopPx: Float?,
    viewportBottomPx: Float?
): Boolean {
    if (!hasCompletedTranscript) return false
    val headingBottom = transcriptHeadingBottomPx ?: return false
    val actionsTop = exportActionsTopPx ?: return false
    val actionsBottom = exportActionsBottomPx ?: return false
    val viewportTop = viewportTopPx ?: return false
    val viewportBottom = viewportBottomPx ?: return false
    val transcriptHeadingHasLeftViewport = headingBottom <= viewportTop
    val exportActionsIntersectViewport = actionsTop < viewportBottom && actionsBottom > viewportTop
    return transcriptHeadingHasLeftViewport && !exportActionsIntersectViewport
}
