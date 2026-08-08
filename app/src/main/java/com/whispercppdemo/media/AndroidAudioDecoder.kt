package de.matthiasennen.transcript.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.ceil

private const val TARGET_SAMPLE_RATE = 16_000
private const val DECODER_TIMEOUT_US = 10_000L

data class AudioTrackInfo(
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val mimeType: String
)

data class DecodedAudioChunk(
    val samples: FloatArray,
    val decodeStartMs: Long,
    val decodeEndMs: Long,
    val sourceSampleRate: Int,
    val sourceChannelCount: Int,
    val mimeType: String
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
fun decodeAudioChunk(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    shouldCancel: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {}
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

        val maxOutputSamples = ceil((endMs - startMs) * TARGET_SAMPLE_RATE / 1_000.0)
            .toInt()
            .coerceAtLeast(1) + 4
        val samples = BoundedFloatArrayBuilder(maxOutputSamples)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var reachedRequestedEnd = false

        while (!outputEnded && !reachedRequestedEnd) {
            if (shouldCancel()) throw CancellationException("Audiodekodierung abgebrochen.")

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
                }
            }
        }

        if (shouldCancel()) throw CancellationException("Audiodekodierung abgebrochen.")
        onProgress(1f)
        return DecodedAudioChunk(
            samples = samples.toArray(),
            decodeStartMs = startMs,
            decodeEndMs = endMs,
            sourceSampleRate = sampleRate,
            sourceChannelCount = channelCount,
            mimeType = mime
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
        else -> buffer.getShort(offset) / 32_768f
    }

private fun bytesPerSample(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_8BIT -> 1
    else -> 2
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

private class BoundedFloatArrayBuilder(capacity: Int) {
    private val values = FloatArray(capacity)
    private var size = 0

    fun add(value: Float) {
        check(size < values.size) { "Der dekodierte Audioabschnitt ist länger als erwartet." }
        values[size++] = value
    }

    fun toArray(): FloatArray = values.copyOf(size)
}

private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default
