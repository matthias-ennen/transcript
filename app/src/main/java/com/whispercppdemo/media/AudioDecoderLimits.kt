package de.matthiasennen.transcript.media

import kotlin.math.ceil

internal const val TARGET_SAMPLE_RATE = 16_000
internal const val DECODER_OUTPUT_MARGIN_MS = 5_000L
internal const val DECODER_STALL_TIMEOUT_MS = 30_000L
internal const val DECODER_MAX_IDLE_CYCLES = 1_500

internal data class DecoderStallSnapshot(
    val idleDurationMs: Long,
    val idleCycles: Int,
    val queuedInputBuffers: Int,
    val releasedOutputBuffers: Int,
    val lastPresentationTimeUs: Long?
)

internal class DecoderProgressWatchdog(
    startedAtMs: Long,
    private val stallTimeoutMs: Long = DECODER_STALL_TIMEOUT_MS,
    private val maxIdleCycles: Int = DECODER_MAX_IDLE_CYCLES
) {
    private var lastProgressAtMs = startedAtMs
    private var idleCycles = 0
    private var queuedInputBuffers = 0
    private var releasedOutputBuffers = 0
    private var lastPresentationTimeUs: Long? = null

    fun recordProgress(
        nowMs: Long,
        inputQueued: Boolean = false,
        outputReleased: Boolean = false,
        presentationTimeUs: Long? = null
    ) {
        lastProgressAtMs = nowMs
        idleCycles = 0
        if (inputQueued) queuedInputBuffers++
        if (outputReleased) releasedOutputBuffers++
        if (presentationTimeUs != null) lastPresentationTimeUs = presentationTimeUs
    }

    fun recordIdle(nowMs: Long): DecoderStallSnapshot? {
        idleCycles++
        val idleDurationMs = (nowMs - lastProgressAtMs).coerceAtLeast(0L)
        if (idleDurationMs < stallTimeoutMs && idleCycles < maxIdleCycles) return null
        return DecoderStallSnapshot(
            idleDurationMs = idleDurationMs,
            idleCycles = idleCycles,
            queuedInputBuffers = queuedInputBuffers,
            releasedOutputBuffers = releasedOutputBuffers,
            lastPresentationTimeUs = lastPresentationTimeUs
        )
    }
}

internal class AudioDecoderStallException(
    mimeType: String,
    startMs: Long,
    endMs: Long,
    snapshot: DecoderStallSnapshot
) : IllegalStateException(
    "Audiodecoder ohne Fortschritt: Format $mimeType, Abschnitt $startMs-$endMs ms, " +
        "Leerlauf ${snapshot.idleDurationMs} ms/${snapshot.idleCycles} Zyklen, " +
        "Eingaben ${snapshot.queuedInputBuffers}, Ausgaben ${snapshot.releasedOutputBuffers}, " +
        "letzter Zeitstempel ${snapshot.lastPresentationTimeUs ?: -1L} us."
)

internal inline fun <T> withSingleDecoderRestart(
    onRestart: (AudioDecoderStallException) -> Unit = {},
    block: (attempt: Int) -> T
): T {
    try {
        return block(0)
    } catch (stall: AudioDecoderStallException) {
        onRestart(stall)
        return block(1)
    }
}

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
