package de.matthiasennen.transcript.transcription

import de.matthiasennen.transcript.ui.main.WhisperVadMode

data class VadProcessingSummary(
    val requestedMode: WhisperVadMode,
    val usedVad: Boolean,
    val originalDurationMs: Long,
    val processedDurationMs: Long,
    val skippedDurationMs: Long,
    val speechRegionCount: Int,
    val reason: String,
    val measurementsAvailable: Boolean = true
)

internal fun fullAudioVadSummary(
    mode: WhisperVadMode,
    durationMs: Long,
    reason: String,
    speechRegionCount: Int = 0
): VadProcessingSummary {
    val duration = durationMs.coerceAtLeast(0L)
    return VadProcessingSummary(
        requestedMode = mode,
        usedVad = false,
        originalDurationMs = duration,
        processedDurationMs = duration,
        skippedDurationMs = 0L,
        speechRegionCount = speechRegionCount.coerceAtLeast(0),
        reason = reason,
        measurementsAvailable = true
    )
}

internal fun analyzedVadSummary(
    mode: WhisperVadMode,
    useVad: Boolean,
    durationMs: Long,
    decision: VadAutomaticDecision
): VadProcessingSummary {
    val duration = durationMs.coerceAtLeast(0L)
    val detectedSpeechMs = if (decision.analyzedSampleCount > 0L) {
        decision.detectedSpeechSampleCount * duration / decision.analyzedSampleCount
    } else {
        0L
    }.coerceIn(0L, duration)
    val processed = if (useVad) detectedSpeechMs else duration
    return VadProcessingSummary(
        requestedMode = mode,
        usedVad = useVad,
        originalDurationMs = duration,
        processedDurationMs = processed,
        skippedDurationMs = (duration - processed).coerceAtLeast(0L),
        speechRegionCount = decision.speechSegmentCount.coerceAtLeast(0),
        reason = decision.reason,
        measurementsAvailable = true
    )
}
