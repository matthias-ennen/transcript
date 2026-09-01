package de.matthiasennen.transcript.song

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import de.matthiasennen.transcript.media.decoderTimestampOffsetUs
import de.matthiasennen.transcript.media.isExtractorEndOfStream
import de.matthiasennen.transcript.media.normalizedDecoderTimestampUs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

private const val SONG_DECODER_TIMEOUT_US = 10_000L
private const val SONG_DECODER_MAX_IDLE_POLLS = 500
private const val MAX_SOURCE_FRAMES_PER_WINDOW = 6_000_000

internal data class SongAudioChunk(
    val interleavedStereo44100: FloatArray,
    val startMs: Long,
    val endMs: Long
)

/**
 * Decodes only one bounded separator window. PCM is kept in a primitive float
 * buffer rather than boxed frame objects and is immediately resampled to
 * 44.1-kHz stereo after the current window.
 *
 * The timestamp normalization mirrors the proven normal transcription decoder.
 * This matters for media whose first extractor timestamp is negative: without
 * normalization Android can emit a window that appears to end before the
 * requested range and Song mode would fail before the separator is reached.
 */
internal fun decodeSongAudioChunk(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    shouldCancel: () -> Boolean = { false }
): SongAudioChunk {
    require(startMs >= 0L && endMs > startMs) { "Ungültiger Audioabschnitt für die Stimmisolierung." }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var codecStarted = false
    try {
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("Die Datei enthält keine lesbare Audiospur.")
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("Das Audioformat konnte nicht bestimmt werden.")
        val startUs = startMs * 1_000L
        val endUs = endMs * 1_000L
        var sampleRate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, SONG_SAMPLE_RATE)
        var channelCount = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(mime)
        codec = decoder
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()
        codecStarted = true

        val estimatedFrames = (((endMs - startMs) * sampleRate.coerceAtLeast(1)) / 1_000L)
            .coerceIn(1L, MAX_SOURCE_FRAMES_PER_WINDOW.toLong())
            .toInt()
        val sourceFrames = StereoFloatBuffer(estimatedFrames)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var inputTimestampOffsetUs: Long? = null
        var outputEnded = false
        var reachedEnd = false
        var idlePolls = 0

        while (!outputEnded && !reachedEnd) {
            if (shouldCancel()) throw CancellationException("Stimmisolierung abgebrochen.")
            var progressed = false
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(SONG_DECODER_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)
                        ?: error("Der Audiodecoder stellte keinen Eingabepuffer bereit.")
                    val size = extractor.readSampleData(input, 0)
                    val timeUs = extractor.sampleTime
                    if (isExtractorEndOfStream(size) || timeUs > endUs) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        val timestampOffsetUs = inputTimestampOffsetUs
                            ?: decoderTimestampOffsetUs(timeUs).also {
                                inputTimestampOffsetUs = it
                            }
                        decoder.queueInputBuffer(
                            inputIndex,
                            0,
                            size,
                            normalizedDecoderTimestampUs(timeUs, timestampOffsetUs),
                            0
                        )
                        extractor.advance()
                    }
                    progressed = true
                }
            }

            when (val outputIndex = decoder.dequeueOutputBuffer(info, SONG_DECODER_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = decoder.outputFormat
                    sampleRate = output.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channelCount = output.intOr(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    pcmEncoding = output.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    progressed = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    decoder.getOutputBuffer(outputIndex)?.let { buffer ->
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            reachedEnd = appendSongFrames(
                                buffer = buffer,
                                presentationTimeUs = info.presentationTimeUs,
                                sampleRate = sampleRate,
                                channelCount = channelCount,
                                pcmEncoding = pcmEncoding,
                                requestedStartUs = startUs,
                                requestedEndUs = endUs,
                                destination = sourceFrames
                            )
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                    progressed = true
                }
            }

            if (progressed) {
                idlePolls = 0
            } else if (++idlePolls >= SONG_DECODER_MAX_IDLE_POLLS) {
                error("Die Audioaufbereitung für die Stimmisolierung reagiert nicht mehr.")
            }
        }

        check(sourceFrames.frameCount > 0) { "Der Audioabschnitt enthält keine dekodierbaren Audiodaten." }
        val wantedFrames = (((endMs - startMs) * SONG_SAMPLE_RATE) / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        return SongAudioChunk(
            interleavedStereo44100 = resampleStereo(sourceFrames, sampleRate, wantedFrames),
            startMs = startMs,
            endMs = endMs
        )
    } finally {
        if (codecStarted) runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

private class StereoFloatBuffer(initialFrames: Int) {
    private var values = FloatArray((initialFrames.coerceAtLeast(1) * 2).coerceAtMost(MAX_SOURCE_FRAMES_PER_WINDOW * 2))
    var frameCount: Int = 0
        private set

    fun add(left: Float, right: Float) {
        check(frameCount < MAX_SOURCE_FRAMES_PER_WINDOW) {
            "Der Audiodecoder lieferte ungewöhnlich viele Samples für einen Abschnitt der Stimmisolierung."
        }
        val needed = (frameCount + 1) * 2
        if (needed > values.size) {
            val grown = maxOf(needed, (values.size * 3 / 2).coerceAtLeast(2))
                .coerceAtMost(MAX_SOURCE_FRAMES_PER_WINDOW * 2)
            values = values.copyOf(grown)
        }
        values[frameCount * 2] = left
        values[frameCount * 2 + 1] = right
        frameCount++
    }

    fun left(frame: Int): Float = values[frame * 2]
    fun right(frame: Int): Float = values[frame * 2 + 1]
}

private fun appendSongFrames(
    buffer: ByteBuffer,
    presentationTimeUs: Long,
    sampleRate: Int,
    channelCount: Int,
    pcmEncoding: Int,
    requestedStartUs: Long,
    requestedEndUs: Long,
    destination: StereoFloatBuffer
): Boolean {
    val rate = sampleRate.coerceAtLeast(1)
    val channels = channelCount.coerceAtLeast(1)
    val bytesPerSample = songBytesPerSample(pcmEncoding)
    val frameSize = channels * bytesPerSample
    val frameCount = buffer.remaining() / frameSize
    val initial = buffer.position()
    for (frameIndex in 0 until frameCount) {
        val timestampUs = presentationTimeUs + frameIndex.toLong() * 1_000_000L / rate
        if (timestampUs >= requestedEndUs) return true
        if (timestampUs < requestedStartUs) continue
        val frameOffset = initial + frameIndex * frameSize
        val left = readSongPcm(buffer, frameOffset, pcmEncoding)
        val right = if (channels >= 2) {
            readSongPcm(buffer, frameOffset + bytesPerSample, pcmEncoding)
        } else {
            left
        }
        destination.add(left, right)
    }
    return false
}

private fun resampleStereo(
    source: StereoFloatBuffer,
    sourceRate: Int,
    wantedFrames: Int
): FloatArray {
    val output = FloatArray(wantedFrames * 2)
    if (source.frameCount == 1) {
        for (i in 0 until wantedFrames) {
            output[2 * i] = source.left(0)
            output[2 * i + 1] = source.right(0)
        }
        return output
    }
    val scale = sourceRate.coerceAtLeast(1).toDouble() / SONG_SAMPLE_RATE.toDouble()
    for (i in 0 until wantedFrames) {
        val position = i * scale
        val leftIndex = position.toInt().coerceIn(0, source.frameCount - 1)
        val rightIndex = (leftIndex + 1).coerceAtMost(source.frameCount - 1)
        val fraction = (position - leftIndex).toFloat().coerceIn(0f, 1f)
        val leftA = source.left(leftIndex)
        val leftB = source.left(rightIndex)
        val rightA = source.right(leftIndex)
        val rightB = source.right(rightIndex)
        output[2 * i] = leftA + (leftB - leftA) * fraction
        output[2 * i + 1] = rightA + (rightB - rightA) * fraction
    }
    return output
}

private fun readSongPcm(buffer: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
    AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2_147_483_648f
    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
    AudioFormat.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32_768f
    else -> error("Nicht unterstütztes PCM-Format für die Stimmisolierung ($encoding).")
}

private fun songBytesPerSample(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_16BIT -> 2
    else -> error("Nicht unterstütztes PCM-Format für die Stimmisolierung ($encoding).")
}

private fun MediaFormat.intOr(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default
