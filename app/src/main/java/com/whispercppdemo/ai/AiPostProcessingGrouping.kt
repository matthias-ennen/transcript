package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment

private const val MIN_AI_SECTION_MINUTES = 1
private const val MAX_AI_SECTION_MINUTES = 5

internal fun aiPostProcessingGroups(
    segments: List<WhisperSegment>,
    mode: AiPostProcessingMode,
    groupStartMs: Long?,
    sectionMinutes: Int
): List<List<Int>> {
    val durationMs = sectionMinutes
        .coerceIn(MIN_AI_SECTION_MINUTES, MAX_AI_SECTION_MINUTES)
        .toLong() * 60L * 1_000L
    val all = segments.indices
        .filter { segments[it].text.isNotBlank() }
        .groupBy { segments[it].startMs / durationMs }
        .toSortedMap()
        .values
        .map(List<Int>::toList)

    return when (mode) {
        AiPostProcessingMode.AUTOMATIC -> all
        AiPostProcessingMode.MANUAL_GROUP -> {
            val expected = requireNotNull(groupStartMs) / durationMs
            all.filter { indexes ->
                indexes.isNotEmpty() && segments[indexes.first()].startMs / durationMs == expected
            }
        }
    }
}
