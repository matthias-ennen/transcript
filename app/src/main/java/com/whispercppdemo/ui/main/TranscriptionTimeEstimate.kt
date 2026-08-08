package de.matthiasennen.transcript.ui.main

import kotlin.math.ceil

private const val ESTIMATE_STARTUP_SECONDS = 30.0

private fun WhisperModel.provisionalRealtimeFactor(): Double = when (this) {
    WhisperModel.TINY -> 0.8
    WhisperModel.BASE -> 1.5
    WhisperModel.SMALL_Q5_1 -> 2.5
    WhisperModel.LARGE_V3_TURBO_Q5_0 -> 5.0
    WhisperModel.LARGE_V3_Q5_0 -> 8.0
}

/**
 * Deliberately conservative first estimate for on-device transcription.
 * The factors can later be calibrated per model from measured device runs.
 */
internal fun estimateTranscriptionDurationSeconds(
    audioDurationMs: Long,
    model: WhisperModel
): Long? {
    if (audioDurationMs <= 0L) return null
    val audioSeconds = audioDurationMs / 1_000.0
    val estimateSeconds =
        audioSeconds * model.provisionalRealtimeFactor() + ESTIMATE_STARTUP_SECONDS
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
