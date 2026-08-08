package de.matthiasennen.transcript.ui.main

import kotlin.math.ceil

private const val ESTIMATE_STARTUP_SECONDS = 30.0

internal fun WhisperModel.transcriptionRealtimeFactor(): Double = when (this) {
    WhisperModel.TINY -> 0.75
    WhisperModel.BASE -> 1.0
    WhisperModel.SMALL_Q5_1 -> 1.6
    WhisperModel.LARGE_V3_TURBO_Q5_0 -> 6.0
    WhisperModel.LARGE_V3_Q5_0 -> 7.0
}

/**
 * Calibrated estimate for on-device transcription based on measured device runs.
 */
internal fun estimateTranscriptionDurationSeconds(
    audioDurationMs: Long,
    model: WhisperModel
): Long? {
    if (audioDurationMs <= 0L) return null
    val audioSeconds = audioDurationMs / 1_000.0
    val estimateSeconds =
        audioSeconds * model.transcriptionRealtimeFactor() + ESTIMATE_STARTUP_SECONDS
    return ceil(estimateSeconds / 60.0).toLong().coerceAtLeast(1L) * 60L
}

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
    audioDurationMs: Long,
    model: WhisperModel
): String? = estimateTranscriptionDurationSeconds(audioDurationMs, model)?.let { seconds ->
    "Voraussichtliche Transkriptionsdauer: ${formatTranscriptionEstimate(seconds)}"
}

internal fun transcriptionRuntimeDisplay(
    elapsedSeconds: Long,
    audioDurationMs: Long,
    model: WhisperModel
): String {
    val elapsed = formatClock(elapsedSeconds)
    val estimate = estimateTranscriptionDurationSeconds(audioDurationMs, model)
        ?.let(::formatClock)
    return if (estimate == null) "Laufzeit: $elapsed" else "Laufzeit: $elapsed (≈ $estimate)"
}
