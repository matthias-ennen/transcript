package de.matthiasennen.transcript.song

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import de.matthiasennen.transcript.media.AudioSampleDiagnostics
import de.matthiasennen.transcript.media.DecodedAudioChunk
import de.matthiasennen.transcript.media.analyzeAudioSamples
import java.io.File
import kotlin.math.PI
import kotlin.math.cos

private const val WHISPER_SAMPLE_RATE = 16_000
private const val SEPARATOR_STEP_MS = 8_000L
private const val SEPARATOR_WINDOW_MS = 11_000L

internal fun decodeAndSeparateSongChunk(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    configuration: SongWorkerConfiguration,
    shouldCancel: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {}
): DecodedAudioChunk {
    require(configuration.mode == TranscriptionMode.SONG)
    require(endMs > startMs)
    val outputSamples = (((endMs - startMs) * WHISPER_SAMPLE_RATE) / 1_000L)
        .coerceAtLeast(1L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val mixed = FloatArray(outputSamples)
    val weights = FloatArray(outputSamples)
    val modelDirectory = File(context.filesDir, "song-models")
    val starts = separatorWindowStarts(startMs, endMs)

    SongSeparatorEngine.open(
        model = configuration.model,
        modelDirectory = modelDirectory,
        threads = configuration.threads
    ).use { engine ->
        starts.forEachIndexed { index, absoluteStartMs ->
            if (shouldCancel()) throw java.util.concurrent.CancellationException(
                "Gesangstrennung abgebrochen."
            )
            val absoluteEndMs = minOf(absoluteStartMs + SEPARATOR_WINDOW_MS, endMs)
            val decoded = decodeSongAudioChunk(
                context = context,
                uri = uri,
                startMs = absoluteStartMs,
                endMs = absoluteEndMs,
                shouldCancel = shouldCancel
            )
            val actualFrames44100 = decoded.interleavedStereo44100.size / 2
            val padded = padStereo(decoded.interleavedStereo44100, KIM_SAMPLES_PER_CHANNEL)
            val vocals = engine.separateVocals(padded)
            val mono16k = downmixAndResampleToWhisper(
                interleavedStereo44100 = vocals,
                usableFrames44100 = actualFrames44100
            )
            val outputOffset = (((absoluteStartMs - startMs) * WHISPER_SAMPLE_RATE) / 1_000L)
                .toInt()
            overlapAdd(
                destination = mixed,
                weights = weights,
                source = mono16k,
                outputOffset = outputOffset,
                fullWindowSamples = (SEPARATOR_WINDOW_MS * WHISPER_SAMPLE_RATE / 1_000L).toInt()
            )
            onProgress((index + 1).toFloat() / starts.size.toFloat())
        }
    }

    for (i in mixed.indices) {
        if (weights[i] > 1e-6f) mixed[i] /= weights[i]
    }
    val diagnostics: AudioSampleDiagnostics = analyzeAudioSamples(mixed)
    check(diagnostics.sampleCount > 0 && diagnostics.peak > 0.000001f) {
        "Die Gesangstrennung lieferte kein verwertbares Audiosignal."
    }
    return DecodedAudioChunk(
        samples = mixed,
        decodeStartMs = startMs,
        decodeEndMs = endMs,
        sourceSampleRate = SONG_SAMPLE_RATE,
        sourceChannelCount = 2,
        mimeType = "audio/song-separated",
        pcmEncoding = AudioFormat.ENCODING_PCM_FLOAT,
        discardedTrailingSamples = 0,
        diagnostics = diagnostics
    )
}

internal fun separatorWindowStarts(startMs: Long, endMs: Long): List<Long> {
    require(endMs > startMs)
    val starts = mutableListOf<Long>()
    var position = startMs
    while (position < endMs) {
        starts += position
        if (position + SEPARATOR_WINDOW_MS >= endMs) break
        position += SEPARATOR_STEP_MS
    }
    return starts
}

private fun padStereo(source: FloatArray, wantedFrames: Int): FloatArray {
    require(source.size % 2 == 0)
    val wanted = wantedFrames * 2
    if (source.size == wanted) return source
    return FloatArray(wanted).also { output ->
        source.copyInto(output, endIndex = minOf(source.size, output.size))
    }
}

private fun downmixAndResampleToWhisper(
    interleavedStereo44100: FloatArray,
    usableFrames44100: Int
): FloatArray {
    val usable = usableFrames44100.coerceIn(1, interleavedStereo44100.size / 2)
    val outputFrames = (usable.toLong() * WHISPER_SAMPLE_RATE / SONG_SAMPLE_RATE)
        .coerceAtLeast(1L)
        .toInt()
    val output = FloatArray(outputFrames)
    val scale = SONG_SAMPLE_RATE.toDouble() / WHISPER_SAMPLE_RATE.toDouble()
    for (i in output.indices) {
        val position = i * scale
        val leftFrame = position.toInt().coerceIn(0, usable - 1)
        val rightFrame = (leftFrame + 1).coerceAtMost(usable - 1)
        val fraction = (position - leftFrame).toFloat().coerceIn(0f, 1f)
        val a = (
            interleavedStereo44100[leftFrame * 2] +
                interleavedStereo44100[leftFrame * 2 + 1]
            ) * 0.5f
        val b = (
            interleavedStereo44100[rightFrame * 2] +
                interleavedStereo44100[rightFrame * 2 + 1]
            ) * 0.5f
        output[i] = a + (b - a) * fraction
    }
    return output
}

private fun overlapAdd(
    destination: FloatArray,
    weights: FloatArray,
    source: FloatArray,
    outputOffset: Int,
    fullWindowSamples: Int
) {
    source.forEachIndexed { index, sample ->
        val target = outputOffset + index
        if (target !in destination.indices) return@forEachIndexed
        val weight = hammingWeight(index, fullWindowSamples)
        destination[target] += sample * weight
        weights[target] += weight
    }
}

private fun hammingWeight(index: Int, size: Int): Float {
    if (size <= 1) return 1f
    val safeIndex = index.coerceIn(0, size - 1)
    return (0.54 - 0.46 * cos(2.0 * PI * safeIndex / (size - 1))).toFloat()
}
