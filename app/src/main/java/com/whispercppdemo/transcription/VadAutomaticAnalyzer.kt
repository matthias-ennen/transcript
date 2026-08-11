package de.matthiasennen.transcript.transcription

import kotlin.math.abs
import kotlin.math.sqrt

internal data class VadAutomaticDecision(
    val useVad: Boolean,
    val reason: String,
    val silencePercent: Int,
    val longestSilenceMs: Long
)

/**
 * Constant-memory, deliberately conservative scan for clear silence. It does
 * not try to classify speech itself: ambiguous audio is always left to Whisper.
 */
internal class VadAutomaticAnalyzer(
    private val sampleRate: Int = 16_000,
    private val windowSamples: Int = 480
) {
    private var totalWindows = 0L
    private var silentWindows = 0L
    private var currentSilentWindows = 0L
    private var longestSilentWindows = 0L
    private var silenceRuns = 0L

    fun add(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val end = (offset + windowSamples).coerceAtMost(samples.size)
            var squared = 0.0
            var peak = 0f
            for (index in offset until end) {
                val value = samples[index]
                squared += value * value
                peak = maxOf(peak, abs(value))
            }
            val rms = sqrt(squared / (end - offset).coerceAtLeast(1)).toFloat()
            val silent = rms < 0.0035f && peak < 0.02f
            totalWindows++
            if (silent) {
                silentWindows++
                if (currentSilentWindows == 0L) silenceRuns++
                currentSilentWindows++
                longestSilentWindows = maxOf(longestSilentWindows, currentSilentWindows)
            } else {
                currentSilentWindows = 0L
            }
            offset = end
        }
    }

    fun decide(): VadAutomaticDecision {
        if (totalWindows == 0L) {
            return VadAutomaticDecision(false, "keine auswertbaren Audiodaten", 0, 0L)
        }
        val silenceRatio = silentWindows.toDouble() / totalWindows
        val durationMinutes = totalWindows * windowSamples.toDouble() / sampleRate / 60.0
        val runsPerMinute = silenceRuns / durationMinutes.coerceAtLeast(1.0)
        val longestMs = longestSilentWindows * windowSamples * 1_000L / sampleRate
        val useVad = silenceRatio >= 0.15 && silenceRatio <= 0.85 &&
            longestMs >= 2_500L && runsPerMinute <= 8.0
        val reason = when {
            silenceRatio < 0.15 -> "zu wenig klare längere Stille"
            silenceRatio > 0.85 -> "überwiegend Stille oder sehr leises, unsicheres Audio"
            longestMs < 2_500L -> "keine ausreichend lange eindeutige Pause"
            runsPerMinute > 8.0 -> "zu viele kurze Unterbrechungen; Qualitätsrisiko durch Zerstückelung"
            else -> "klare längere Pausen bei geringer Zerstückelung"
        }
        return VadAutomaticDecision(useVad, reason, (silenceRatio * 100).toInt(), longestMs)
    }
}
