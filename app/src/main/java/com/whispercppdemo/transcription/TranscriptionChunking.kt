package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import kotlin.math.max
import kotlin.math.min

const val STANDARD_SECTION_DURATION_MS = 5 * 60 * 1_000L
const val FALLBACK_SECTION_DURATION_MS = 150 * 1_000L
const val SECTION_OVERLAP_MS = 2_000L

data class TranscriptionSection(
    val mainStartMs: Long,
    val mainEndMs: Long,
    val decodeStartMs: Long,
    val decodeEndMs: Long,
    val usedFallbackSize: Boolean = false
) {
    val mainDurationMs: Long get() = mainEndMs - mainStartMs
}

fun planTranscriptionSections(
    durationMs: Long,
    startAtMs: Long = 0L,
    sectionDurationMs: Long = STANDARD_SECTION_DURATION_MS,
    overlapMs: Long = SECTION_OVERLAP_MS,
    usedFallbackSize: Boolean = false
): List<TranscriptionSection> {
    require(durationMs > 0L) { "Die Audiodauer muss größer als null sein." }
    require(sectionDurationMs > 0L) { "Die Abschnittsdauer muss größer als null sein." }
    require(overlapMs >= 0L) { "Die Überlappung darf nicht negativ sein." }

    val safeStart = startAtMs.coerceIn(0L, durationMs)
    if (safeStart >= durationMs) return emptyList()

    val sections = mutableListOf<TranscriptionSection>()
    var mainStart = safeStart
    while (mainStart < durationMs) {
        val mainEnd = min(durationMs, mainStart + sectionDurationMs)
        sections += TranscriptionSection(
            mainStartMs = mainStart,
            mainEndMs = mainEnd,
            decodeStartMs = max(0L, mainStart - overlapMs),
            decodeEndMs = min(durationMs, mainEnd + overlapMs),
            usedFallbackSize = usedFallbackSize
        )
        mainStart = mainEnd
    }
    return sections
}

fun splitIntoFallbackSections(
    section: TranscriptionSection,
    totalDurationMs: Long,
    overlapMs: Long = SECTION_OVERLAP_MS
): List<TranscriptionSection> = planTranscriptionSections(
    durationMs = totalDurationMs,
    startAtMs = section.mainStartMs,
    sectionDurationMs = FALLBACK_SECTION_DURATION_MS,
    overlapMs = overlapMs,
    usedFallbackSize = true
).takeWhile { it.mainStartMs < section.mainEndMs }
    .map { fallback ->
        val clippedMainEnd = min(fallback.mainEndMs, section.mainEndMs)
        fallback.copy(
            mainEndMs = clippedMainEnd,
            decodeEndMs = min(totalDurationMs, clippedMainEnd + overlapMs)
        )
    }

/**
 * Moves Whisper's chunk-local timestamps onto the complete recording and keeps
 * each overlap result in exactly one main section. The midpoint rule preserves
 * segments that cross a boundary while preventing duplicate overlap output.
 */
fun selectAbsoluteSegments(
    localSegments: List<WhisperSegment>,
    section: TranscriptionSection,
    totalDurationMs: Long
): List<WhisperSegment> = localSegments.mapNotNull { segment ->
    val absoluteStart = (section.decodeStartMs + segment.startMs)
        .coerceIn(0L, totalDurationMs)
    val absoluteEnd = (section.decodeStartMs + segment.endMs)
        .coerceIn(absoluteStart, totalDurationMs)
    val midpoint = absoluteStart + (absoluteEnd - absoluteStart) / 2L
    val belongsToSection = midpoint >= section.mainStartMs &&
        (midpoint < section.mainEndMs || section.mainEndMs == totalDurationMs)
    segment.text.trim().takeIf { it.isNotEmpty() && belongsToSection }?.let { text ->
        WhisperSegment(absoluteStart, absoluteEnd, text)
    }
}.sortedWith(compareBy(WhisperSegment::startMs, WhisperSegment::endMs))

fun mergeCommittedSegments(
    committed: List<WhisperSegment>,
    next: List<WhisperSegment>
): List<WhisperSegment> = (committed + next)
    .filter { it.text.isNotBlank() }
    .sortedWith(compareBy(WhisperSegment::startMs, WhisperSegment::endMs))
