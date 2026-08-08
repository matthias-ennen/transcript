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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

const val WAVEFORM_GENERATION_TIMEOUT_MS = 60_000L

private const val DEFAULT_BAR_COUNT = 180
private const val DECODER_TIMEOUT_US = 10_000L
private const val WAVEFORM_SAMPLES_PER_SECOND = 100

/**
 * Creates a compact waveform without retaining decoded PCM audio.
 *
 * MediaCodec output is sampled while it is decoded and immediately reduced to
 * [barCount] peak values. Memory usage therefore stays effectively constant,
 * regardless of the duration of the selected file.
 */
fun generateWaveform(
    context: Context,
    uri: Uri,
    barCount: Int = DEFAULT_BAR_COUNT,
    shouldCancel: () -> Boolean = { false }
): Pair<List<Float>, Long> {
    val safeBarCount = barCount.coerceAtLeast(1)
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var codecStarted = false

    try {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index)
                .getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: error("Die Datei enthält keine lesbare Audiospur.")

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Das Audioformat konnte nicht bestimmt werden.")
        val declaredDurationUs = inputFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
        val accumulator = WaveformPeakAccumulator(safeBarCount, declaredDurationUs)

        var sampleRate = inputFormat.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, 16_000)
        var channelCount = inputFormat.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var decodedDurationUs = declaredDurationUs
        var inputEnded = false
        var outputEnded = false
        val bufferInfo = MediaCodec.BufferInfo()

        extractor.selectTrack(trackIndex)
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        codecStarted = true

        while (!outputEnded) {
            if (shouldCancel()) throw CancellationException("Wellenformerstellung abgebrochen.")

            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(DECODER_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                        ?: error("Der Audiodecoder stellte keinen Eingabepuffer bereit.")
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
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
                }

                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> if (outputIndex >= 0) {
                    decoder.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                        if (bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.order(ByteOrder.nativeOrder())
                            sampleOutputBuffer(
                                buffer = outputBuffer,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                pcmEncoding = pcmEncoding,
                                accumulator = accumulator
                            )
                            val frameCount = bufferInfo.size / frameSizeBytes(channelCount, pcmEncoding)
                            decodedDurationUs = max(
                                decodedDurationUs,
                                bufferInfo.presentationTimeUs.coerceAtLeast(0L) +
                                    frameCount.toLong() * 1_000_000L / sampleRate.coerceAtLeast(1)
                            )
                        }
                    }

                    outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }

        if (shouldCancel()) throw CancellationException("Wellenformerstellung abgebrochen.")
        return accumulator.normalizedPeaks() to (decodedDurationUs / 1_000L)
    } finally {
        if (codecStarted) runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

private fun sampleOutputBuffer(
    buffer: ByteBuffer,
    presentationTimeUs: Long,
    sampleRate: Int,
    channelCount: Int,
    pcmEncoding: Int,
    accumulator: WaveformPeakAccumulator
) {
    val safeSampleRate = sampleRate.coerceAtLeast(1)
    val frameSize = frameSizeBytes(channelCount, pcmEncoding)
    val frameCount = buffer.remaining() / frameSize
    val frameStride = (safeSampleRate / WAVEFORM_SAMPLES_PER_SECOND).coerceAtLeast(1)
    val start = buffer.position()

    var frameIndex = 0
    while (frameIndex < frameCount) {
        val frameOffset = start + frameIndex * frameSize
        val amplitude = readDownmixedSample(buffer, frameOffset, channelCount, pcmEncoding)
        val timestampUs = presentationTimeUs.coerceAtLeast(0L) +
            frameIndex.toLong() * 1_000_000L / safeSampleRate
        accumulator.add(amplitude, timestampUs)
        frameIndex += frameStride
    }
}

private fun readDownmixedSample(
    buffer: ByteBuffer,
    frameOffset: Int,
    channelCount: Int,
    pcmEncoding: Int
): Float {
    val safeChannels = channelCount.coerceAtLeast(1)
    val bytesPerSample = bytesPerSample(pcmEncoding)
    var sum = 0f

    repeat(safeChannels) { channel ->
        val offset = frameOffset + channel * bytesPerSample
        sum += when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
            AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2_147_483_648f
            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
            else -> buffer.getShort(offset) / 32_768f
        }
    }
    return abs(sum / safeChannels).coerceIn(0f, 1f)
}

private fun frameSizeBytes(channelCount: Int, pcmEncoding: Int): Int =
    channelCount.coerceAtLeast(1) * bytesPerSample(pcmEncoding)

private fun bytesPerSample(pcmEncoding: Int): Int = when (pcmEncoding) {
    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_8BIT -> 1
    else -> 2
}

internal class WaveformPeakAccumulator(
    private val barCount: Int,
    private val durationUs: Long
) {
    private val peaks = FloatArray(barCount)
    private var sampledValueCount = 0L
    private var samplesPerAdaptiveBar = 1L
    private var hasSamples = false

    fun add(amplitude: Float, timestampUs: Long) {
        hasSamples = true
        val safeAmplitude = if (amplitude.isFinite()) amplitude.coerceIn(0f, 1f) else 0f
        if (durationUs > 0L) {
            val fraction = timestampUs.coerceAtLeast(0L).toDouble() / durationUs.toDouble()
            val index = (fraction * barCount).toInt().coerceIn(0, barCount - 1)
            peaks[index] = max(peaks[index], safeAmplitude)
            return
        }

        while (sampledValueCount / samplesPerAdaptiveBar >= barCount) {
            compactAdaptivePeaks()
        }
        val index = (sampledValueCount / samplesPerAdaptiveBar).toInt()
        peaks[index] = max(peaks[index], safeAmplitude)
        sampledValueCount++
    }

    fun normalizedPeaks(): List<Float> {
        if (!hasSamples) return emptyList()
        val rawPeaks = if (durationUs > 0L) {
            peaks.toList()
        } else {
            expandAdaptivePeaks()
        }
        val maximum = rawPeaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
        return rawPeaks.map { (it / maximum).coerceIn(0.04f, 1f) }
    }

    private fun compactAdaptivePeaks() {
        val compactedCount = ceil(barCount / 2.0).toInt()
        repeat(compactedCount) { index ->
            val first = index * 2
            peaks[index] = max(peaks[first], peaks.getOrElse(first + 1) { 0f })
        }
        for (index in compactedCount until barCount) peaks[index] = 0f
        samplesPerAdaptiveBar = (samplesPerAdaptiveBar * 2L).coerceAtMost(Long.MAX_VALUE / 2L)
    }

    private fun expandAdaptivePeaks(): List<Float> {
        if (sampledValueCount == 0L) return emptyList()
        val usedBars = ceil(sampledValueCount.toDouble() / samplesPerAdaptiveBar)
            .toInt()
            .coerceIn(1, barCount)
        return List(barCount) { outputIndex ->
            val sourceIndex = (outputIndex.toLong() * usedBars / barCount)
                .toInt()
                .coerceIn(0, usedBars - 1)
            peaks[sourceIndex]
        }
    }
}

private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default
