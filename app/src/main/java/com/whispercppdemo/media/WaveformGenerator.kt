package de.matthiasennen.transcript.media

import android.content.Context
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max

private const val DEFAULT_BAR_COUNT = 180

fun generateWaveform(
    context: Context,
    uri: Uri,
    barCount: Int = DEFAULT_BAR_COUNT
): Pair<List<Float>, Long> {
    val decoded = decodeAudio(context, uri)
    if (decoded.samples.isEmpty()) return emptyList<Float>() to decoded.durationMs
    val safeBarCount = barCount.coerceAtLeast(1)
    val samplesPerBar = max(1, decoded.samples.size / safeBarCount)
    val peaks = (0 until safeBarCount).map { barIndex ->
        val start = barIndex * samplesPerBar
        if (start >= decoded.samples.size) return@map 0f
        val end = minOf(decoded.samples.size, start + samplesPerBar)
        var peak = 0f
        for (index in start until end) peak = max(peak, abs(decoded.samples[index]))
        peak
    }
    val maximum = peaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
    return peaks.map { (it / maximum).coerceIn(0.04f, 1f) } to decoded.durationMs
}
