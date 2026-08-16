package de.matthiasennen.transcript.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
private const val DECODER_TIMEOUT_US = 10_000L

data class AudioTrackInfo(
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val mimeType: String
)

data class AudioSampleDiagnostics(
    val sampleCount: Int,
    val durationMs: Long,
    val peak: Float,
    val rms: Float,
    val nearSilentSampleRatio: Float
)

class UnusableAudioSamplesException(message: String) : IllegalStateException(message)

data class DecodedAudioChunk(
    val samples: FloatArray,
    val decodeStartMs: Long,
    val decodeEndMs: Long,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val mimeType: String,
    val pcmEncoding: Int,
    val discardedTrailingSamples: Int,
    val diagnostics: AudioSampleDiagnostics
)

fun inspectAudioTrack(context: Context, uri: Uri): AudioTrackInfo {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val trackIndex = extractor.audioTrackIndex()
        val format = extractor.getTrackFormat(trackIndex)
        val durationUs = format.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
        check(durationUs > 0L) { "Die Dauer der Audiospur konnte nicht bestimmt werden." }
        return AudioTrackInfo(
            durationMs = durationUs / 1_000L,
            sourceSampleRate = format.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE),
            sourceChannelCount = format.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1),
            mimeType = format.getString(MediaFormat.KEY_MIME)
                ?: error("Das Audioformat konnte nicht bestimmt werden.")
        )
    } finally {
        extractor.release()
    }
}

/**
 * Decodes only [startMs, endMs) and resamples it while MediaCodec output is
 * consumed. Source-rate PCM is never accumulated, so the peak Java memory is
 * bounded by the current 16-kHz Whisper section.
 */
internal fun decodeAudioChunk(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    shouldCancel: () -> Boolean = { false },
    onDecoderRestart: (AudioDecoderStallException) -> Unit = {},
    onProgress: (Float) -> Unit = {}
): DecodedAudioChunk = withSingleDecoderRestart(onDecoderRestart) {
    decodeAudioChunkAttempt(context, uri, startMs, endMs, shouldCancel, onProgress)
}

private fun decodeAudioChunkAttempt(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    shouldCancel: () -> Boolean,
    onProgress: (Float) -> Unit
): DecodedAudioChunk {
    require(startMs >= 0L && endMs > startMs) { "Ungültiger Audioabschnitt." }

    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var codecStarted = false
    try {
        extractor.setDataSource(context, uri, null)
        val trackIndex = extractor.audioTrackIndex()
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Das Audioformat konnte nicht bestimmt werden.")
        val startUs = startMs * 1_000L
        val endUs = endMs * 1_000L

        var sampleRate = inputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
        var channelCount = inputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler: StreamingMonoResampler? = null

        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        codecStarted = true

        val requestedOutputSamples = targetSampleCount(endMs - startMs)
        val samples = BoundedFloatArrayBuilder(
            capacity = decoderOutputCapacity(requestedOutputSamples),
            requestedSamples = requestedOutputSamples
        )
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var reachedRequestedEnd = false
        val watchdog = DecoderProgressWatchdog(SystemClock.elapsedRealtime())

        while (!outputEnded && !reachedRequestedEnd) {
            if (shouldCancel()) throw CancellationException("Audiodekodierung abgebrochen.")
            var madeProgress = false

            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                        ?: error("Der Audiodecoder stellte keinen Eingabepuffer bereit.")
                    val size = extractor.readSampleData(inputBuffer, 0)
                    val sampleTime = extractor.sampleTime
                    if (size < 0 || sampleTime < 0L || sampleTime > endUs) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                        extractor.advance()
                    }
                    madeProgress = true
                    watchdog.recordProgress(SystemClock.elapsedRealtime(), inputQueued = true)
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, DECODER_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = decoder.outputFormat
                    sampleRate = outputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channelCount = outputFormat.getIntOrDefault(
                        MediaFormat.KEY_CHANNEL_COUNT,
                        channelCount
                    )
                    pcmEncoding = outputFormat.getIntOrDefault(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    resampler = StreamingMonoResampler(sampleRate, TARGET_SAMPLE_RATE, samples)
                    madeProgress = true
                    watchdog.recordProgress(SystemClock.elapsedRealtime())
                }

                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> if (outputIndex >= 0) {
                    decoder.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.order(ByteOrder.nativeOrder())
                            val activeResampler = resampler
                                ?: StreamingMonoResampler(sampleRate, TARGET_SAMPLE_RATE, samples)
                                    .also { resampler = it }
                            reachedRequestedEnd = consumeOutputBuffer(
                                buffer = outputBuffer,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                pcmEncoding = pcmEncoding,
                                requestedStartUs = startUs,
                                requestedEndUs = endUs,
                                resampler = activeResampler
                            )
                            val coveredUs = (bufferInfo.presentationTimeUs - startUs)
                                .coerceIn(0L, endUs - startUs)
                            onProgress(coveredUs.toFloat() / (endUs - startUs).toFloat())
                        }
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                    madeProgress = true
                    watchdog.recordProgress(
                        nowMs = SystemClock.elapsedRealtime(),
                        outputReleased = true,
                        presentationTimeUs = bufferInfo.presentationTimeUs
                    )
                }
            }
            if (!madeProgress) {
                watchdog.recordIdle(SystemClock.elapsedRealtime())?.let { snapshot ->
                    throw AudioDecoderStallException(mime, startMs, endMs, snapshot)
                }
            }
        }

        if (shouldCancel()) throw CancellationException("Audiodekodierung abgebrochen.")
        onProgress(1f)
        val trimmedSamples = trimDecoderSamples(samples.toArray(), requestedOutputSamples)
        val diagnostics = analyzeAudioSamples(trimmedSamples.samples)
        validateAudioSamples(diagnostics)
        return DecodedAudioChunk(
            samples = trimmedSamples.samples,
            decodeStartMs = startMs,
            decodeEndMs = endMs,
            sourceSampleRate = sampleRate,
            sourceChannelCount = channelCount,
            mimeType = mime,
            pcmEncoding = pcmEncoding,
            discardedTrailingSamples = trimmedSamples.discardedTrailingSamples,
            diagnostics = diagnostics
        )
    } finally {
        if (codecStarted) runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

private fun MediaExtractor.audioTrackIndex(): Int =
    (0 until trackCount).firstOrNull { index ->
        getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    } ?: error("Die Datei enthält keine lesbare Audiospur.")

private fun consumeOutputBuffer(
    buffer: ByteBuffer,
    presentationTimeUs: Long,
    sampleRate: Int,
    channelCount: Int,
    pcmEncoding: Int,
    requestedStartUs: Long,
    requestedEndUs: Long,
    resampler: StreamingMonoResampler
): Boolean {
    val safeRate = sampleRate.coerceAtLeast(1)
    val safeChannels = channelCount.coerceAtLeast(1)
    val bytesPerSample = bytesPerSample(pcmEncoding)
    val frameSize = bytesPerSample * safeChannels
    val frameCount = buffer.remaining() / frameSize
    val initialPosition = buffer.position()

    for (frameIndex in 0 until frameCount) {
        val timestampUs = presentationTimeUs + frameIndex.toLong() * 1_000_000L / safeRate
        if (timestampUs >= requestedEndUs) return true
        if (timestampUs < requestedStartUs) continue

        val frameOffset = initialPosition + frameIndex * frameSize
        var mono = 0f
        repeat(safeChannels) { channel ->
            mono += readPcmSample(buffer, frameOffset + channel * bytesPerSample, pcmEncoding)
        }
        resampler.add(mono / safeChannels)
    }
    return false
}

private fun readPcmSample(buffer: ByteBuffer, offset: Int, encoding: Int): Float =
    when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
        AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2_147_483_648f
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
        AudioFormat.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32_768f
        else -> throw UnusableAudioSamplesException(
            "Der Android-Audiodecoder lieferte ein nicht unterstütztes PCM-Format ($encoding)."
        )
    }

private fun bytesPerSample(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_16BIT -> 2
    else -> throw UnusableAudioSamplesException(
        "Der Android-Audiodecoder lieferte ein nicht unterstütztes PCM-Format ($encoding)."
    )
}

internal fun analyzeAudioSamples(samples: FloatArray): AudioSampleDiagnostics {
    if (samples.isEmpty()) {
        return AudioSampleDiagnostics(0, 0L, 0f, 0f, 1f)
    }
    var peak = 0f
    var sumSquares = 0.0
    var nearSilent = 0
    samples.forEach { sample ->
        if (!sample.isFinite()) {
            throw UnusableAudioSamplesException(
                "Die dekodierte Audiospur enthält ungültige Samplewerte."
            )
        }
        val absolute = kotlin.math.abs(sample)
        peak = maxOf(peak, absolute)
        sumSquares += sample.toDouble() * sample.toDouble()
        if (absolute < 0.0001f) nearSilent++
    }
    return AudioSampleDiagnostics(
        sampleCount = samples.size,
        durationMs = samples.size.toLong() * 1_000L / TARGET_SAMPLE_RATE,
        peak = peak,
        rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat(),
        nearSilentSampleRatio = nearSilent.toFloat() / samples.size.toFloat()
    )
}

private fun validateAudioSamples(diagnostics: AudioSampleDiagnostics) {
    if (diagnostics.sampleCount == 0) {
        throw UnusableAudioSamplesException(
            "Die Audiospur wurde dekodiert, enthält aber keine Audiosamples."
        )
    }
    if (diagnostics.peak < 0.00001f || diagnostics.rms < 0.000001f) {
        throw UnusableAudioSamplesException(
            "Die Audiospur wurde dekodiert, enthält aber kein verwertbares Audiosignal."
        )
    }
}

private class StreamingMonoResampler(
    sourceRate: Int,
    targetRate: Int,
    private val destination: BoundedFloatArrayBuilder
) {
    private val sourceFramesPerOutput = sourceRate.coerceAtLeast(1).toDouble() /
        targetRate.coerceAtLeast(1).toDouble()
    private var sourceIndex = -1L
    private var nextOutputPosition = 0.0
    private var previous = 0f

    fun add(value: Float) {
        sourceIndex++
        if (sourceIndex == 0L) {
            previous = value
            destination.add(value)
            nextOutputPosition = sourceFramesPerOutput
            return
        }

        while (nextOutputPosition <= sourceIndex.toDouble()) {
            val leftIndex = sourceIndex - 1L
            val fraction = (nextOutputPosition - leftIndex).toFloat().coerceIn(0f, 1f)
            destination.add(previous + (value - previous) * fraction)
            nextOutputPosition += sourceFramesPerOutput
        }
        previous = value
    }
}

private class BoundedFloatArrayBuilder(
    capacity: Int,
    private val requestedSamples: Int
) {
    private val values = FloatArray(capacity)
    private var size = 0

    fun add(value: Float) {
        if (size >= values.size) {
            throw AudioDecoderOutputOverflowException(requestedSamples, values.size)
        }
        values[size++] = value
    }

    fun toArray(): FloatArray = values.copyOf(size)
}

private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default
