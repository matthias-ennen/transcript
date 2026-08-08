package de.matthiasennen.transcript.media

import kotlin.math.ceil

internal const val TARGET_SAMPLE_RATE = 16_000
internal const val DECODER_OUTPUT_MARGIN_MS = 5_000L

internal class AudioDecoderOutputOverflowException(
    requestedSamples: Int,
    capacitySamples: Int
) : IllegalStateException(
    "Der Audiodecoder hat den begrenzten Sicherheitspuffer überschritten " +
        "(Soll: $requestedSamples, Grenze: $capacitySamples 16-kHz-Samples)."
)

internal data class TrimmedDecoderSamples(
    val samples: FloatArray,
    val discardedTrailingSamples: Int
)

internal fun targetSampleCount(durationMs: Long): Int {
    require(durationMs > 0L) { "Die Abschnittsdauer muss größer als null sein." }
    val sampleCount = ceil(durationMs * TARGET_SAMPLE_RATE / 1_000.0).toLong()
    check(sampleCount <= Int.MAX_VALUE) { "Der Audioabschnitt ist zu groß." }
    return sampleCount.toInt().coerceAtLeast(1)
}

internal fun decoderOutputCapacity(requestedSamples: Int): Int {
    require(requestedSamples > 0) { "Die Sollgröße muss größer als null sein." }
    val marginSamples = DECODER_OUTPUT_MARGIN_MS * TARGET_SAMPLE_RATE / 1_000L
    val capacity = requestedSamples.toLong() + marginSamples
    check(capacity <= Int.MAX_VALUE) { "Der Audioabschnitt ist zu groß." }
    return capacity.toInt()
}

internal fun trimDecoderSamples(
    samples: FloatArray,
    requestedSamples: Int
): TrimmedDecoderSamples {
    require(requestedSamples > 0) { "Die Sollgröße muss größer als null sein." }
    val discarded = (samples.size - requestedSamples).coerceAtLeast(0)
    return TrimmedDecoderSamples(
        samples = if (discarded == 0) samples else samples.copyOf(requestedSamples),
        discardedTrailingSamples = discarded
    )
}
