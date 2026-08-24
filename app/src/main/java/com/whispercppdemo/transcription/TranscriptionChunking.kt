package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import kotlin.math.max
import kotlin.math.min

const val STANDARD_SECTION_DURATION_MS = 5 * 60 * 1_000L
const val FALLBACK_SECTION_DURATION_MS = 150 * 1_000L
const val SECTION_OVERLAP_MS = 2_000L
private const val SEAM_CONTAINMENT_RATIO = 0.80

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
 * Moves Whisper's chunk-local timestamps onto the complete recording. Whisper may return
 * timestamp endpoints slightly outside the decoded PCM window, so both endpoints are clamped
 * to the actual decode window before the midpoint ownership rule is applied.
 *
 * The midpoint rule decides which neighboring chunk owns a boundary segment. Because Whisper
 * can segment the same overlap differently in both chunks, mergeCommittedSegments() performs
 * the second stitching step across the two result sets.
 */
fun selectAbsoluteSegments(
    localSegments: List<WhisperSegment>,
    section: TranscriptionSection,
    totalDurationMs: Long
): List<WhisperSegment> {
    val decodeStart = section.decodeStartMs.coerceIn(0L, totalDurationMs)
    val decodeEnd = section.decodeEndMs.coerceIn(decodeStart, totalDurationMs)
    return localSegments.mapNotNull { segment ->
        val absoluteStart = (section.decodeStartMs + segment.startMs)
            .coerceIn(decodeStart, decodeEnd)
        val absoluteEnd = (section.decodeStartMs + segment.endMs)
            .coerceIn(absoluteStart, decodeEnd)
        if (absoluteEnd <= absoluteStart) return@mapNotNull null

        val midpoint = absoluteStart + (absoluteEnd - absoluteStart) / 2L
        val belongsToSection = midpoint >= section.mainStartMs &&
            (midpoint < section.mainEndMs || section.mainEndMs == totalDurationMs)
        segment.text.trim().takeIf { it.isNotEmpty() && belongsToSection }?.let { text ->
            WhisperSegment(absoluteStart, absoluteEnd, text)
        }
    }.sortedWith(compareBy(WhisperSegment::startMs, WhisperSegment::endMs))
}

private data class MergeCandidate(
    val segment: WhisperSegment,
    val incoming: Boolean
)

private fun overlapDurationMs(first: WhisperSegment, second: WhisperSegment): Long =
    (min(first.endMs, second.endMs) - max(first.startMs, second.startMs)).coerceAtLeast(0L)

private fun durationMs(segment: WhisperSegment): Long =
    (segment.endMs - segment.startMs).coerceAtLeast(0L)

private fun coverageRatio(segment: WhisperSegment, overlapMs: Long): Double {
    val duration = durationMs(segment)
    return if (duration <= 0L) 0.0 else overlapMs.toDouble() / duration.toDouble()
}

/**
 * Stitches the already committed chunk with the next chunk.
 *
 * A neighboring decode overlap may contain two different Whisper segmentations of the same
 * audio. If one cross-chunk segment is at least 80% covered by the other, the contained
 * alternative is discarded; near-identical alternatives keep the already committed version.
 * Remaining partial cross-chunk overlaps are split at the temporal midpoint so the final
 * timeline stays monotonic without deleting either distinct text segment.
 */
fun mergeCommittedSegments(
    committed: List<WhisperSegment>,
    next: List<WhisperSegment>
): List<WhisperSegment> {
    val previous = committed
        .filter { it.text.isNotBlank() && it.endMs > it.startMs }
        .sortedWith(compareBy(WhisperSegment::startMs, WhisperSegment::endMs))
        .toMutableList()
    val incoming = next
        .filter { it.text.isNotBlank() && it.endMs > it.startMs }
        .sortedWith(compareBy(WhisperSegment::startMs, WhisperSegment::endMs))
        .toMutableList()

    var previousIndex = 0
    while (previousIndex < previous.size) {
        var incomingIndex = 0
        var removePrevious = false
        while (incomingIndex < incoming.size) {
            val previousSegment = previous[previousIndex]
            val incomingSegment = incoming[incomingIndex]
            val overlap = overlapDurationMs(previousSegment, incomingSegment)
            if (overlap <= 0L) {
                incomingIndex++
                continue
            }

            val previousCoverage = coverageRatio(previousSegment, overlap)
            val incomingCoverage = coverageRatio(incomingSegment, overlap)
            when {
                incomingCoverage >= SEAM_CONTAINMENT_RATIO &&
                    previousCoverage >= SEAM_CONTAINMENT_RATIO -> {
                    // Same overlap represented twice: keep the stable, already committed segment.
                    incoming.removeAt(incomingIndex)
                }
                incomingCoverage >= SEAM_CONTAINMENT_RATIO -> {
                    // The incoming alternative is almost completely represented already.
                    incoming.removeAt(incomingIndex)
                }
                previousCoverage >= SEAM_CONTAINMENT_RATIO -> {
                    // The previous alternative is almost completely represented by the new chunk.
                    removePrevious = true
                    break
                }
                else -> incomingIndex++
            }
        }

        if (removePrevious) {
            previous.removeAt(previousIndex)
        } else {
            previousIndex++
        }
    }

    val stitched = (previous.map { MergeCandidate(it, incoming = false) } +
        incoming.map { MergeCandidate(it, incoming = true) })
        .sortedWith(
            compareBy<MergeCandidate> { it.segment.startMs }
                .thenBy { it.segment.endMs }
        )
        .toMutableList()

    var index = 1
    while (index < stitched.size) {
        val before = stitched[index - 1]
        val current = stitched[index]
        val crossChunkOverlap = before.incoming != current.incoming &&
            before.segment.endMs > current.segment.startMs
        if (!crossChunkOverlap) {
            index++
            continue
        }

        val overlapEnd = min(before.segment.endMs, current.segment.endMs)
        val overlapStart = current.segment.startMs
        if (overlapEnd <= overlapStart) {
            index++
            continue
        }

        val split = overlapStart + (overlapEnd - overlapStart) / 2L
        val adjustedBefore = before.segment.copy(endMs = split)
        val adjustedCurrent = current.segment.copy(startMs = split)
        if (adjustedBefore.endMs > adjustedBefore.startMs &&
            adjustedCurrent.endMs > adjustedCurrent.startMs
        ) {
            stitched[index - 1] = before.copy(segment = adjustedBefore)
            stitched[index] = current.copy(segment = adjustedCurrent)
        }
        index++
    }

    return stitched.map { it.segment }
        .filter { it.text.isNotBlank() && it.endMs > it.startMs }
        .sortedWith(compareBy(WhisperSegment::startMs, WhisperSegment::endMs))
}
