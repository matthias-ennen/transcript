package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperVadSegment
import kotlin.math.roundToInt

internal data class VadAutomaticDecision(
    val useVad: Boolean,
    val reason: String,
    val silencePercent: Int,
    val speechPercent: Int,
    val longestSilenceMs: Long,
    val speechSegmentCount: Int,
    val analyzedSampleCount: Long,
    val detectedSpeechSampleCount: Long
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
    private var analyzedSampleCount = 0L

    fun add(
        chunkDurationMs: Long,
        segments: List<WhisperVadSegment>,
        chunkSampleCount: Int? = null
    ) {
        val duration = chunkDurationMs.coerceAtLeast(0L)
        val chunkStartMs = totalDurationMs
        totalDurationMs += duration
        analyzedSampleCount += chunkSampleCount?.coerceAtLeast(0)?.toLong()
            ?: duration * WHISPER_SAMPLE_RATE / 1_000L

        val normalized = segments.asSequence()
            .filter { it.startMs >= 0L && it.endMs > it.startMs && it.startMs < duration }
            .map { WhisperVadSegment(it.startMs, it.endMs.coerceAtMost(duration)) }
            .filter { it.endMs > it.startMs }
            .sortedBy(WhisperVadSegment::startMs)
            .fold(mutableListOf<WhisperVadSegment>()) { merged, segment ->
                val previous = merged.lastOrNull()
                if (previous != null && segment.startMs <= previous.endMs) {
                    merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, segment.endMs))
                } else {
                    merged += segment
                }
                merged
            }

        normalized.forEach { segment ->
            val startMs = segment.startMs
            val endMs = segment.endMs
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
                speechSegmentCount = 0,
                analyzedSampleCount = analyzedSampleCount,
                detectedSpeechSampleCount = 0L
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
        val stableSpeechDuration = clampedSpeechMs >= 2_000L
        val useVad = speechSegmentCount > 0 &&
            stableSpeechDuration &&
            silenceRatio >= 0.15 &&
            effectiveLongestSilenceMs >= 2_500L &&
            segmentsPerMinute <= 18.0 &&
            shortSegmentRatio <= 0.35
        val reason = when {
            speechSegmentCount == 0 -> "Silero hat keine belastbaren Sprachbereiche erkannt"
            !stableSpeechDuration -> "Silero hat zu wenig belastbare Sprache erkannt"
            silenceRatio < 0.15 -> "Silero würde zu wenig Audio einsparen"
            effectiveLongestSilenceMs < 2_500L -> "keine ausreichend lange von Silero erkannte Pause"
            segmentsPerMinute > 18.0 -> "Silero würde die Aufnahme zu stark zerstückeln"
            shortSegmentRatio > 0.35 -> "zu viele sehr kurze, unsichere Sprachbereiche"
            else -> "Silero erkennt klare längere Pausen bei stabilen Sprachbereichen"
        }
        val speechPercent = if (clampedSpeechMs > 0L) {
            (speechRatio * 100).roundToInt().coerceIn(1, 100)
        } else {
            0
        }
        return VadAutomaticDecision(
            useVad = useVad,
            reason = reason,
            silencePercent = 100 - speechPercent,
            speechPercent = speechPercent,
            longestSilenceMs = effectiveLongestSilenceMs,
            speechSegmentCount = speechSegmentCount,
            analyzedSampleCount = analyzedSampleCount,
            detectedSpeechSampleCount = clampedSpeechMs * WHISPER_SAMPLE_RATE / 1_000L
        )
    }

    private companion object {
        const val WHISPER_SAMPLE_RATE = 16_000L
    }
}
