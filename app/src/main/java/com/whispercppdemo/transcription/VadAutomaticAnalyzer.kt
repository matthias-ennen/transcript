package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperVadSegment

internal data class VadAutomaticDecision(
    val useVad: Boolean,
    val reason: String,
    val silencePercent: Int,
    val speechPercent: Int,
    val longestSilenceMs: Long,
    val speechSegmentCount: Int
)

/**
 * Aggregates real Silero-VAD segments in constant memory and accepts VAD only
 * when it removes a useful amount of silence without fragmenting the audio.
 */
internal class VadAutomaticAnalyzer {
    private var totalDurationMs = 0L
    private var speechDurationMs = 0L
    private var longestSilenceMs = 0L
    private var lastSpeechEndMs = 0L
    private var speechSegmentCount = 0
    private var shortSpeechSegmentCount = 0

    fun add(chunkDurationMs: Long, segments: List<WhisperVadSegment>) {
        val duration = chunkDurationMs.coerceAtLeast(0L)
        val chunkStartMs = totalDurationMs
        totalDurationMs += duration
        segments.sortedBy(WhisperVadSegment::startMs).forEach { segment ->
            val startMs = segment.startMs.coerceIn(0L, duration)
            val endMs = segment.endMs.coerceIn(startMs, duration)
            val absoluteStartMs = chunkStartMs + startMs
            val absoluteEndMs = chunkStartMs + endMs
            longestSilenceMs = maxOf(longestSilenceMs, absoluteStartMs - lastSpeechEndMs)
            val speechMs = endMs - startMs
            speechDurationMs += speechMs
            speechSegmentCount++
            if (speechMs < 750L) shortSpeechSegmentCount++
            lastSpeechEndMs = maxOf(lastSpeechEndMs, absoluteEndMs)
        }
    }

    fun decide(): VadAutomaticDecision {
        if (totalDurationMs <= 0L) {
            return VadAutomaticDecision(
                useVad = false,
                reason = "keine auswertbaren Audiodaten",
                silencePercent = 0,
                speechPercent = 0,
                longestSilenceMs = 0L,
                speechSegmentCount = 0
            )
        }
        val effectiveLongestSilenceMs = maxOf(longestSilenceMs, totalDurationMs - lastSpeechEndMs)
        val clampedSpeechMs = speechDurationMs.coerceIn(0L, totalDurationMs)
        val speechRatio = clampedSpeechMs.toDouble() / totalDurationMs
        val silenceRatio = 1.0 - speechRatio
        val durationMinutes = totalDurationMs / 60_000.0
        val segmentsPerMinute = speechSegmentCount / durationMinutes.coerceAtLeast(1.0)
        val shortSegmentRatio = if (speechSegmentCount == 0) 1.0 else {
            shortSpeechSegmentCount.toDouble() / speechSegmentCount
        }
        val useVad = speechSegmentCount > 0 &&
            silenceRatio in 0.15..0.80 &&
            effectiveLongestSilenceMs >= 2_500L &&
            segmentsPerMinute <= 10.0 &&
            shortSegmentRatio <= 0.35
        val reason = when {
            speechSegmentCount == 0 -> "Silero hat keine belastbaren Sprachbereiche erkannt"
            silenceRatio < 0.15 -> "Silero würde zu wenig Audio einsparen"
            silenceRatio > 0.80 -> "Silero würde zu viel unsicheres Audio entfernen"
            effectiveLongestSilenceMs < 2_500L -> "keine ausreichend lange von Silero erkannte Pause"
            segmentsPerMinute > 10.0 -> "Silero würde die Aufnahme zu stark zerstückeln"
            shortSegmentRatio > 0.35 -> "zu viele sehr kurze, unsichere Sprachbereiche"
            else -> "Silero erkennt klare längere Pausen bei stabilen Sprachbereichen"
        }
        return VadAutomaticDecision(
            useVad = useVad,
            reason = reason,
            silencePercent = (silenceRatio * 100).toInt().coerceIn(0, 100),
            speechPercent = (speechRatio * 100).toInt().coerceIn(0, 100),
            longestSilenceMs = effectiveLongestSilenceMs,
            speechSegmentCount = speechSegmentCount
        )
    }
}
