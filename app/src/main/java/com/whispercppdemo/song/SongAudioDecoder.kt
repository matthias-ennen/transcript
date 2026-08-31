package de.matthiasennen.transcript.song

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

private const val SONG_DECODER_TIMEOUT_US = 10_000L
private const val SONG_DECODER_MAX_IDLE_POLLS = 500

internal data class SongAudioChunk(
    val interleavedStereo44100: FloatArray,
    val startMs: Long,
    val endMs: Long
)

/**
 * Decodes only one bounded separator window. Source-rate PCM is retained only for
 * the current window and is immediately resampled to 44.1-kHz stereo.
 */
internal fun decodeSongAudioChunk(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    shouldCancel: () -> Boolean = { false }
): SongAudioChunk {
    require(startMs >= 0L && endMs > startMs) { "Ungültiger Song-Audioabschnitt." }
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

        val sourceFrames = ArrayList<StereoFrame>()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        var reachedEnd = false
        var idlePolls = 0

        while (!outputEnded && !reachedEnd) {
            if (shouldCancel()) throw CancellationException("Song-Aufbereitung abgebrochen.")
            var progressed = false
            if (!inputEnded) {
                val inputIndex = decoder.dequeueInputBuffer(SONG_DECODER_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val input = decoder.getInputBuffer(inputIndex)
                        ?: error("Der Audiodecoder stellte keinen Eingabepuffer bereit.")
                    val size = extractor.readSampleData(input, 0)
                    val timeUs = extractor.sampleTime
                    if (size < 0 || timeUs > endUs) {
                        decoder.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, timeUs.coerceAtLeast(0L), 0)
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
                error("Die Song-Audioaufbereitung reagiert nicht mehr.")
            }
        }

        check(sourceFrames.isNotEmpty()) { "Der Songabschnitt enthält keine dekodierbaren Audiodaten." }
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

private data class StereoFrame(val left: Float, val right: Float)

private fun appendSongFrames(
    buffer: ByteBuffer,
    presentationTimeUs: Long,
    sampleRate: Int,
    channelCount: Int,
    pcmEncoding: Int,
    requestedStartUs: Long,
    requestedEndUs: Long,
    destination: MutableList<StereoFrame>
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
        destination += StereoFrame(left, right)
    }
    return false
}

private fun resampleStereo(
    source: List<StereoFrame>,
    sourceRate: Int,
    wantedFrames: Int
): FloatArray {
    val output = FloatArray(wantedFrames * 2)
    if (source.size == 1) {
        for (i in 0 until wantedFrames) {
            output[2 * i] = source[0].left
            output[2 * i + 1] = source[0].right
        }
        return output
    }
    val scale = sourceRate.coerceAtLeast(1).toDouble() / SONG_SAMPLE_RATE.toDouble()
    for (i in 0 until wantedFrames) {
        val position = i * scale
        val leftIndex = position.toInt().coerceIn(0, source.lastIndex)
        val rightIndex = (leftIndex + 1).coerceAtMost(source.lastIndex)
        val fraction = (position - leftIndex).toFloat().coerceIn(0f, 1f)
        val a = source[leftIndex]
        val b = source[rightIndex]
        output[2 * i] = a.left + (b.left - a.left) * fraction
        output[2 * i + 1] = a.right + (b.right - a.right) * fraction
    }
    return output
}

private fun readSongPcm(buffer: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT -> buffer.getFloat(offset).coerceIn(-1f, 1f)
    AudioFormat.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2_147_483_648f
    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128f
    AudioFormat.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32_768f
    else -> error("Nicht unterstütztes PCM-Format für den Songmodus ($encoding).")
}

private fun songBytesPerSample(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_16BIT -> 2
    else -> error("Nicht unterstütztes PCM-Format für den Songmodus ($encoding).")
}

private fun MediaFormat.intOr(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default
