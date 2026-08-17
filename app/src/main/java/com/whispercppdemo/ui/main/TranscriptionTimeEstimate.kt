package de.matthiasennen.transcript.ui.main

import kotlin.math.ceil

internal fun WhisperModel.transcriptionRealtimeFactor(): Double = when (this) {
    WhisperModel.TINY -> 0.75
    WhisperModel.BASE -> 1.0
    WhisperModel.SMALL_Q5_1 -> 1.6
    WhisperModel.LARGE_V3_TURBO_Q5_0 -> 5.0
    WhisperModel.LARGE_V3_Q5_0 -> 6.0
}

/**
 * A deliberately simple preview: media duration multiplied by the selected model factor.
 * The displayed result is always rounded up to the next full minute.
 */
internal fun estimateTranscriptionDurationSeconds(
    audioDurationMs: Long,
    model: WhisperModel
): Long? {
    if (audioDurationMs <= 0L) return null
    val audioSeconds = audioDurationMs / 1_000.0
    val estimateSeconds = audioSeconds * model.transcriptionRealtimeFactor()
    return ceil(estimateSeconds / 60.0).toLong().coerceAtLeast(1L) * 60L
}

internal fun TranscriptUiState.withRecalculatedTranscriptionEstimate(): TranscriptUiState = copy(
    transcriptionEstimateSeconds = estimateTranscriptionDurationSeconds(
        audioDurationMs = audioDurationMs,
        model = selectedModel
    )
)

internal fun formatTranscriptionEstimate(seconds: Long): String {
    val totalMinutes = ceil(seconds.coerceAtLeast(1L) / 60.0).toLong().coerceAtLeast(1L)
    if (totalMinutes < 60L) {
        return if (totalMinutes == 1L) "ca. 1 Minute" else "ca. $totalMinutes Minuten"
    }

    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    val hourLabel = if (hours == 1L) "1 Std." else "$hours Std."
    return if (minutes == 0L) "ca. $hourLabel" else "ca. $hourLabel $minutes Min."
}

internal fun transcriptionEstimateStatus(
    estimateSeconds: Long?
): String? = estimateSeconds?.let { seconds ->
    "Voraussichtliche Transkriptionsdauer: ${formatTranscriptionEstimate(seconds)}"
}

internal fun transcriptionRuntimeDisplay(
    elapsedSeconds: Long,
    estimateSeconds: Long?
): String {
    val elapsed = formatClock(elapsedSeconds)
    val estimate = estimateSeconds?.let(::formatClock)
    return if (estimate == null) "Laufzeit: $elapsed" else "Laufzeit: $elapsed (≈ $estimate)"
}
