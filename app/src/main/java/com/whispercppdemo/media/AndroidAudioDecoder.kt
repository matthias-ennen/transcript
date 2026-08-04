package de.matthiasennen.transcript.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

private const val TARGET_SAMPLE_RATE = 16_000
private const val TIMEOUT_US = 10_000L

/** Decodes any Android-supported audio file and returns 16 kHz mono float PCM for Whisper. */
fun decodeAudio(
    context: Context,
    uri: Uri,
    onProgress: (Float) -> Unit = {}
): FloatArray {
    val extractor = MediaExtractor()
    extractor.setDataSource(context, uri, null)

    val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
        extractor.getTrackFormat(index)
            .getString(MediaFormat.KEY_MIME)
            ?.startsWith("audio/") == true
    } ?: error("Die Datei enthält keine lesbare Audiospur.")

    val inputFormat = extractor.getTrackFormat(trackIndex)
    val mime = inputFormat.getString(MediaFormat.KEY_MIME)
        ?: error("Das Audioformat konnte nicht bestimmt werden.")
    val durationUs = inputFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)

    extractor.selectTrack(trackIndex)
    val codec = MediaCodec.createDecoderByType(mime)
    codec.configure(inputFormat, null, null, 0)
    codec.start()

    val samples = FloatArrayBuilder()
    val bufferInfo = MediaCodec.BufferInfo()
    var inputEnded = false
    var outputEnded = false
    var sampleRate = inputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
    var channelCount = inputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
    var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

    try {
        while (!outputEnded) {
            if (!inputEnded) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: error("Der Audiodecoder stellte keinen Eingabepuffer bereit.")
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    sampleRate = outputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channelCount = outputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    pcmEncoding = outputFormat.getIntOrDefault(
                        MediaFormat.KEY_PCM_ENCODING,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                }

                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> if (outputIndex >= 0) {
                    codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.order(ByteOrder.nativeOrder())
                            appendDownmixed(samples, outputBuffer, channelCount, pcmEncoding)
                        }
                    }

                    if (durationUs > 0L) {
                        onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    }
                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    } finally {
        codec.stop()
        codec.release()
        extractor.release()
    }

    onProgress(1f)
    return resample(samples.toArray(), sampleRate, TARGET_SAMPLE_RATE)
}

private fun appendDownmixed(
    destination: FloatArrayBuilder,
    buffer: java.nio.ByteBuffer,
    channels: Int,
    encoding: Int
) {
    val safeChannels = channels.coerceAtLeast(1)
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 2
    }
    val frameCount = buffer.remaining() / (bytesPerSample * safeChannels)

    repeat(frameCount) {
        var sum = 0f
        repeat(safeChannels) {
            sum += when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
                AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                else -> buffer.short / 32_768f
            }
        }
        destination.add(sum / safeChannels)
    }
}

private fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
    if (input.isEmpty() || sourceRate == targetRate) return input
    val outputSize = ceil(input.size.toDouble() * targetRate / sourceRate).toInt()
    return FloatArray(outputSize) { index ->
        val sourcePosition = index.toDouble() * sourceRate / targetRate
        val left = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
        val right = (left + 1).coerceAtMost(input.lastIndex)
        val fraction = (sourcePosition - left).toFloat()
        input[left] + (input[right] - input[left]) * fraction
    }
}

private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default

private class FloatArrayBuilder(initialCapacity: Int = 16_384) {
    private var values = FloatArray(initialCapacity)
    private var size = 0

    fun add(value: Float) {
        if (size == values.size) values = values.copyOf(values.size * 2)
        values[size++] = value
    }

    fun toArray(): FloatArray = values.copyOf(size)
}
