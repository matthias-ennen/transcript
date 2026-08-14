package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment

internal const val VISIBLE_TRANSCRIPT_GAP_MS = 1_000L

/**
 * Builds the visible, continuous timeline once. Raw Whisper timestamps remain separately stored
 * in TranscriptUiState.rawWhisperSegments and are therefore never overwritten by display filling.
 */
internal fun buildTranscriptTimeline(
    whisperSegments: List<WhisperSegment>,
    audioDurationMs: Long,
    visibleGapMs: Long = VISIBLE_TRANSCRIPT_GAP_MS
): List<WhisperSegment> {
    val duration = audioDurationMs.coerceAtLeast(0L)
    if (duration == 0L) return whisperSegments
    val source = whisperSegments
        .filter { it.endMs > it.startMs && it.endMs > 0L && it.startMs < duration }
        .sortedWith(compareBy<WhisperSegment> { it.startMs }.thenBy { it.endMs })
        .map { it.copy(startMs = it.startMs.coerceAtLeast(0L), endMs = it.endMs.coerceAtMost(duration)) }
    if (source.isEmpty()) return listOf(WhisperSegment(0L, duration, ""))

    val timeline = mutableListOf<WhisperSegment>()
    source.forEach { segment ->
        val cursor = timeline.lastOrNull()?.endMs ?: 0L
        val gap = segment.startMs - cursor
        when {
            gap >= visibleGapMs -> timeline += WhisperSegment(cursor, segment.startMs, "")
            gap > 0L && timeline.isNotEmpty() -> {
                timeline[timeline.lastIndex] = timeline.last().copy(endMs = segment.startMs)
            }
        }
        val displayStart = if (timeline.isEmpty() && segment.startMs in 1 until visibleGapMs) {
            0L
        } else {
            segment.startMs
        }
        timeline += segment.copy(startMs = displayStart)
    }

    val cursor = timeline.last().endMs.coerceAtMost(duration)
    val trailingGap = duration - cursor
    when {
        trailingGap >= visibleGapMs -> timeline += WhisperSegment(cursor, duration, "")
        trailingGap > 0L -> timeline[timeline.lastIndex] = timeline.last().copy(endMs = duration)
    }
    return timeline
}

internal fun isVirtualTimelineSegment(
    segment: WhisperSegment,
    rawWhisperSegments: List<WhisperSegment>
): Boolean = rawWhisperSegments.none { source ->
    source.endMs > segment.startMs && source.startMs < segment.endMs
}

internal fun transcriptNumbers(
    timeline: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>
): List<Int?> {
    val raw = rawWhisperSegments.withIndex().sortedBy { it.value.startMs }
    var firstCandidate = 0
    return timeline.map { segment ->
        while (firstCandidate < raw.size && raw[firstCandidate].value.endMs <= segment.startMs) {
            firstCandidate++
        }
        var candidate = firstCandidate
        var number: Int? = null
        while (candidate < raw.size && raw[candidate].value.startMs < segment.endMs) {
            if (raw[candidate].value.endMs > segment.startMs) {
                number = raw[candidate].index + 1
                break
            }
            candidate++
        }
        number
    }
}

internal fun restoreManualTimelineText(
    timeline: List<WhisperSegment>,
    previouslyDisplayed: List<WhisperSegment>,
    rawWhisperSegments: List<WhisperSegment>
): List<WhisperSegment> {
    val previousNumbers = transcriptNumbers(previouslyDisplayed, rawWhisperSegments)
    val manualTextByRange = previouslyDisplayed.mapIndexedNotNull { index, previous ->
        if (previousNumbers[index] == null && previous.text.isNotBlank()) {
            (previous.startMs to previous.endMs) to previous.text
        } else {
            null
        }
    }.toMap()
    val timelineNumbers = transcriptNumbers(timeline, rawWhisperSegments)
    return timeline.mapIndexed { index, segment ->
        if (timelineNumbers[index] != null) segment else {
            manualTextByRange[segment.startMs to segment.endMs]
                ?.let { segment.copy(text = it) }
                ?: segment
        }
    }
}
