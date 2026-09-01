package de.matthiasennen.transcript.song

import android.content.Context
import android.net.Uri
import de.matthiasennen.transcript.media.inspectAudioTrack
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

internal const val SONG_PREPARED_SAMPLE_RATE = 16_000
internal const val SONG_SEPARATOR_STEP_MS = 8_000L
internal const val SONG_SEPARATOR_WINDOW_MS = 11_000L

private const val SONG_PREPARED_SAMPLES_PER_MS = SONG_PREPARED_SAMPLE_RATE / 1_000L
private const val WAV_HEADER_BYTES = 44L
private const val WAV_BYTES_PER_SAMPLE = 2L
private const val WAVEFORM_BAR_COUNT = 180
private const val PREPARED_MANIFEST_MAGIC = 0x53564754
private const val PREPARED_MANIFEST_VERSION = 1
private const val PREPARED_STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L
private const val MAX_STORED_WAVEFORM_BARS = 1_000

internal enum class SongPlaybackSource {
    ORIGINAL,
    VOCALS
}

internal data class PreparedSongTrack(
    val originalUriString: String,
    val modelId: String,
    val durationMs: Long,
    val sampleCount: Int,
    val sourceLengthBytes: Long,
    val sourceLastModifiedMs: Long,
    val file: File,
    val waveformPeaks: List<Float>
)

internal data class SongSampleRange(
    val startSample: Int,
    val sampleCount: Int
)

/**
 * Process-wide bridge between the Song preprocessing pipeline and the existing
 * audio player. The actual track stays in private app storage; this object only
 * exposes the currently matching, fully committed result.
 */
internal object SongPlaybackSourceRuntime {
    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentTrack: PreparedSongTrack? = null

    @Volatile
    private var selectedSource: SongPlaybackSource = SongPlaybackSource.ORIGINAL

    @Volatile
    private var lastNegativeLookupUri: String? = null

    private val sourceChangeListeners = CopyOnWriteArraySet<() -> Unit>()

    fun attach(context: Context) {
        applicationContext = context.applicationContext
    }

    fun publish(track: PreparedSongTrack) {
        val previousUri = currentTrack?.originalUriString
        currentTrack = track
        lastNegativeLookupUri = null
        if (previousUri != null && previousUri != track.originalUriString) {
            selectedSource = SongPlaybackSource.ORIGINAL
        }
    }

    fun preparedFor(originalUri: Uri?): PreparedSongTrack? {
        val uri = originalUri ?: return null
        val uriString = uri.toString()
        currentTrack?.takeIf {
            it.originalUriString == uriString && preparedTrackFileIsComplete(it)
        }?.let { return it }

        if (lastNegativeLookupUri == uriString) return null
        val context = applicationContext ?: return null
        val restored = readLatestPreparedSongTrack(context, uri)
        if (restored == null) {
            lastNegativeLookupUri = uriString
        } else {
            currentTrack = restored
            selectedSource = SongPlaybackSource.ORIGINAL
            lastNegativeLookupUri = null
        }
        return restored
    }

    fun selectionFor(originalUri: Uri?): SongPlaybackSource =
        if (selectedSource == SongPlaybackSource.VOCALS && preparedFor(originalUri) != null) {
            SongPlaybackSource.VOCALS
        } else {
            SongPlaybackSource.ORIGINAL
        }

    fun select(source: SongPlaybackSource, originalUri: Uri?): Boolean {
        if (source == SongPlaybackSource.VOCALS && preparedFor(originalUri) == null) return false
        if (selectedSource == source) return true
        selectedSource = source
        sourceChangeListeners.forEach { listener -> runCatching(listener) }
        return true
    }

    fun resolvePlaybackUri(originalUri: Uri): Uri =
        if (selectionFor(originalUri) == SongPlaybackSource.VOCALS) {
            Uri.fromFile(checkNotNull(preparedFor(originalUri)).file)
        } else {
            originalUri
        }

    fun registerSourceChangeListener(listener: () -> Unit) {
        sourceChangeListeners += listener
    }
}

internal fun preparedSongSampleCount(durationMs: Long): Int {
    require(durationMs > 0L) { "Ungültige Songdauer." }
    val count = durationMs * SONG_PREPARED_SAMPLES_PER_MS
    check(count <= Int.MAX_VALUE.toLong()) { "Der Song ist für die lokale Aufbereitung zu lang." }
    return count.toInt()
}

internal fun preparedSongSampleRange(
    startMs: Long,
    endMs: Long,
    totalSamples: Int
): SongSampleRange {
    require(startMs >= 0L && endMs > startMs) { "Ungültiger Song-Audioabschnitt." }
    require(totalSamples > 0) { "Die vorbereitete Gesangsspur ist leer." }
    val start = (startMs * SONG_PREPARED_SAMPLES_PER_MS)
        .coerceIn(0L, totalSamples.toLong())
        .toInt()
    val end = (endMs * SONG_PREPARED_SAMPLES_PER_MS)
        .coerceIn(start.toLong(), totalSamples.toLong())
        .toInt()
    return SongSampleRange(startSample = start, sampleCount = end - start)
}

/**
 * Creates one continuous 16-kHz mono vocal WAV for the complete song. The
 * separator works in overlapping 11-second windows, but finalized samples are
 * written only once on the absolute original timeline. Consequently the WAV is
 * exactly as long as the source duration and can be shared by playback and all
 * later Whisper section slices.
 */
internal fun ensurePreparedSongTrack(
    context: Context,
    uri: Uri,
    configuration: SongWorkerConfiguration,
    shouldCancel: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {}
): PreparedSongTrack = synchronized(songPreparationLock) {
    require(configuration.mode == TranscriptionMode.SONG)
    val audioInfo = inspectAudioTrack(context, uri)
    val sampleCount = preparedSongSampleCount(audioInfo.durationMs)
    val fingerprint = sourceFingerprint(context, uri)
    val key = preparedTrackKey(
        uri = uri,
        modelId = configuration.model.id,
        durationMs = audioInfo.durationMs,
        sampleCount = sampleCount,
        sourceLengthBytes = fingerprint.first,
        sourceLastModifiedMs = fingerprint.second
    )
    val directory = File(context.filesDir, "song-prepared").apply { mkdirs() }
    val target = File(directory, "vocals-$key.wav")
    val manifest = File(directory, "vocals-$key.bin")

    readPreparedSongTrack(manifest)?.takeIf { stored ->
        stored.originalUriString == uri.toString() &&
            stored.modelId == configuration.model.id &&
            stored.durationMs == audioInfo.durationMs &&
            stored.sampleCount == sampleCount &&
            stored.sourceLengthBytes == fingerprint.first &&
            stored.sourceLastModifiedMs == fingerprint.second &&
            preparedTrackFileIsComplete(stored)
    }?.let { stored ->
        onProgress(1f)
        SongPlaybackSourceRuntime.publish(stored)
        return@synchronized stored
    }

    val expectedBytes = WAV_HEADER_BYTES + sampleCount.toLong() * WAV_BYTES_PER_SAMPLE
    check(directory.usableSpace >= expectedBytes + PREPARED_STORAGE_RESERVE_BYTES) {
        "Für die Gesangsspur werden mindestens " +
            "${(expectedBytes + PREPARED_STORAGE_RESERVE_BYTES) / (1024L * 1024L)} MB " +
            "freier Speicher benötigt."
    }

    val temporaryTarget = File(directory, "${target.name}.part").also(File::delete)
    val modelDirectory = File(context.filesDir, "song-models")
    val starts = separatorWindowStarts(0L, audioInfo.durationMs)
    val fullWindowSamples = (SONG_SEPARATOR_WINDOW_MS * SONG_PREPARED_SAMPLES_PER_MS).toInt()
    val mixed = FloatArray(fullWindowSamples)
    val weights = FloatArray(fullWindowSamples)
    val waveform = SongPreparedWaveform(sampleCount)
    var bufferStartSample = 0
    var writtenSamples = 0
    var maximumPeak = 0f

    val engine = songStage("${configuration.model.modelLabel} konnte nicht geladen werden") {
        SongSeparatorEngine.open(
            model = configuration.model,
            modelDirectory = modelDirectory,
            threads = configuration.threads
        )
    }

    try {
        BufferedOutputStream(temporaryTarget.outputStream()).use { output ->
            writeMonoPcm16WavHeader(output, sampleCount)
            engine.use {
                starts.forEachIndexed { index, absoluteStartMs ->
                    if (shouldCancel()) throw CancellationException("Gesangstrennung abgebrochen.")
                    val absoluteEndMs = minOf(
                        absoluteStartMs + SONG_SEPARATOR_WINDOW_MS,
                        audioInfo.durationMs
                    )
                    val decoded = songStage("Song-Audio konnte nicht dekodiert werden") {
                        decodeSongAudioChunk(
                            context = context,
                            uri = uri,
                            startMs = absoluteStartMs,
                            endMs = absoluteEndMs,
                            shouldCancel = shouldCancel
                        )
                    }
                    val actualFrames44100 = decoded.interleavedStereo44100.size / 2
                    val padded = padStereoForSeparator(
                        source = decoded.interleavedStereo44100,
                        wantedFrames = KIM_SAMPLES_PER_CHANNEL
                    )
                    val vocals = songStage(
                        "Gesangstrennung mit ${configuration.model.modelLabel} fehlgeschlagen"
                    ) {
                        engine.separateVocals(padded)
                    }
                    val mono16k = downmixAndResamplePreparedVocals(
                        interleavedStereo44100 = vocals,
                        usableFrames44100 = actualFrames44100
                    )
                    val windowStartSample =
                        (absoluteStartMs * SONG_PREPARED_SAMPLES_PER_MS).toInt()
                    val outputOffset = windowStartSample - bufferStartSample
                    check(outputOffset in 0 until mixed.size) {
                        "Die Gesangsspur konnte nicht lückenlos zusammengesetzt werden."
                    }
                    overlapAddPrepared(
                        destination = mixed,
                        weights = weights,
                        source = mono16k,
                        outputOffset = outputOffset,
                        fullWindowSamples = fullWindowSamples
                    )

                    val finalizeUntil = if (index < starts.lastIndex) {
                        (starts[index + 1] * SONG_PREPARED_SAMPLES_PER_MS).toInt()
                    } else {
                        sampleCount
                    }
                    val finalizeCount = finalizeUntil - bufferStartSample
                    check(finalizeCount in 0..mixed.size) {
                        "Die Gesangsspur hat eine ungültige Überlappungsgrenze."
                    }
                    for (sampleIndex in 0 until finalizeCount) {
                        val value = if (weights[sampleIndex] > 1e-6f) {
                            mixed[sampleIndex] / weights[sampleIndex]
                        } else {
                            0f
                        }
                        val clipped = value.coerceIn(-1f, 1f)
                        writePcm16Sample(output, clipped)
                        waveform.add(writtenSamples + sampleIndex, clipped)
                        maximumPeak = maxOf(maximumPeak, kotlin.math.abs(clipped))
                    }
                    writtenSamples += finalizeCount
                    shiftPreparedBuffer(mixed, finalizeCount)
                    shiftPreparedBuffer(weights, finalizeCount)
                    bufferStartSample = finalizeUntil
                    onProgress((index + 1).toFloat() / starts.size.toFloat())
                }
            }

            while (writtenSamples < sampleCount) {
                writePcm16Sample(output, 0f)
                waveform.add(writtenSamples, 0f)
                writtenSamples++
            }
        }
    } catch (failure: Throwable) {
        temporaryTarget.delete()
        throw failure
    }

    check(writtenSamples == sampleCount) { "Die Gesangsspur hat nicht die erwartete Länge." }
    check(maximumPeak > 0.000001f) {
        temporaryTarget.delete()
        "Die Gesangstrennung lieferte kein verwertbares Audiosignal."
    }

    moveAtomically(temporaryTarget, target)
    val track = PreparedSongTrack(
        originalUriString = uri.toString(),
        modelId = configuration.model.id,
        durationMs = audioInfo.durationMs,
        sampleCount = sampleCount,
        sourceLengthBytes = fingerprint.first,
        sourceLastModifiedMs = fingerprint.second,
        file = target,
        waveformPeaks = waveform.normalized()
    )
    writePreparedSongTrackManifest(manifest, track)
    cleanupOtherPreparedTracks(directory, target, manifest)
    SongPlaybackSourceRuntime.publish(track)
    track
}

internal fun readPreparedSongSamples(
    track: PreparedSongTrack,
    startMs: Long,
    endMs: Long
): FloatArray {
    val range = preparedSongSampleRange(startMs, endMs, track.sampleCount)
    check(range.sampleCount > 0) { "Der angeforderte Bereich der Gesangsspur ist leer." }
    check(preparedTrackFileIsComplete(track)) { "Die vorbereitete Gesangsspur ist unvollständig." }

    val output = FloatArray(range.sampleCount)
    RandomAccessFile(track.file, "r").use { input ->
        input.seek(WAV_HEADER_BYTES + range.startSample.toLong() * WAV_BYTES_PER_SAMPLE)
        val byteBuffer = ByteArray(16 * 1024)
        var outputIndex = 0
        while (outputIndex < output.size) {
            val samplesToRead = minOf(byteBuffer.size / 2, output.size - outputIndex)
            val bytesToRead = samplesToRead * 2
            input.readFully(byteBuffer, 0, bytesToRead)
            var byteIndex = 0
            repeat(samplesToRead) {
                val low = byteBuffer[byteIndex].toInt() and 0xff
                val high = byteBuffer[byteIndex + 1].toInt()
                val pcm = ((high shl 8) or low).toShort()
                output[outputIndex++] = pcm / 32768f
                byteIndex += 2
            }
        }
    }
    return output
}

private val songPreparationLock = Any()

private fun sourceFingerprint(context: Context, uri: Uri): Pair<Long, Long> {
    val length = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length
        }
    }.getOrNull() ?: -1L
    val modified = if (uri.scheme.equals("file", ignoreCase = true)) {
        uri.path?.let(::File)?.lastModified() ?: 0L
    } else {
        0L
    }
    return length to modified
}

private fun preparedTrackKey(
    uri: Uri,
    modelId: String,
    durationMs: Long,
    sampleCount: Int,
    sourceLengthBytes: Long,
    sourceLastModifiedMs: Long
): String {
    val identity = listOf(
        uri.toString(),
        modelId,
        durationMs.toString(),
        sampleCount.toString(),
        sourceLengthBytes.toString(),
        sourceLastModifiedMs.toString()
    ).joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(20)
}

private fun preparedTrackFileIsComplete(track: PreparedSongTrack): Boolean =
    track.file.isFile &&
        track.file.length() == WAV_HEADER_BYTES + track.sampleCount.toLong() * WAV_BYTES_PER_SAMPLE

private fun readLatestPreparedSongTrack(context: Context, uri: Uri): PreparedSongTrack? {
    val directory = File(context.filesDir, "song-prepared")
    if (!directory.isDirectory) return null
    return directory.listFiles { file ->
        file.name.startsWith("vocals-") && file.name.endsWith(".bin")
    }.orEmpty()
        .sortedByDescending(File::lastModified)
        .asSequence()
        .mapNotNull(::readPreparedSongTrack)
        .firstOrNull { track ->
            track.originalUriString == uri.toString() && preparedTrackFileIsComplete(track)
        }
}

private fun readPreparedSongTrack(manifest: File): PreparedSongTrack? = runCatching {
    if (!manifest.isFile) return null
    DataInputStream(BufferedInputStream(manifest.inputStream())).use { input ->
        check(input.readInt() == PREPARED_MANIFEST_MAGIC)
        check(input.readInt() == PREPARED_MANIFEST_VERSION)
        val originalUri = input.readUTF()
        val modelId = input.readUTF()
        val durationMs = input.readLong()
        val sampleCount = input.readInt()
        val sourceLength = input.readLong()
        val sourceModified = input.readLong()
        val fileName = input.readUTF()
        val peakCount = input.readInt()
        check(peakCount in 1..MAX_STORED_WAVEFORM_BARS)
        val peaks = List(peakCount) { input.readFloat().also { value -> check(value.isFinite()) } }
        PreparedSongTrack(
            originalUriString = originalUri,
            modelId = modelId,
            durationMs = durationMs,
            sampleCount = sampleCount,
            sourceLengthBytes = sourceLength,
            sourceLastModifiedMs = sourceModified,
            file = File(manifest.parentFile, fileName),
            waveformPeaks = peaks
        )
    }
}.getOrNull()

private fun writePreparedSongTrackManifest(manifest: File, track: PreparedSongTrack) {
    val temporary = File(manifest.parentFile, "${manifest.name}.part").also(File::delete)
    DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
        output.writeInt(PREPARED_MANIFEST_MAGIC)
        output.writeInt(PREPARED_MANIFEST_VERSION)
        output.writeUTF(track.originalUriString)
        output.writeUTF(track.modelId)
        output.writeLong(track.durationMs)
        output.writeInt(track.sampleCount)
        output.writeLong(track.sourceLengthBytes)
        output.writeLong(track.sourceLastModifiedMs)
        output.writeUTF(track.file.name)
        output.writeInt(track.waveformPeaks.size)
        track.waveformPeaks.forEach(output::writeFloat)
    }
    moveAtomically(temporary, manifest)
}

private fun cleanupOtherPreparedTracks(directory: File, target: File, manifest: File) {
    directory.listFiles()?.forEach { file ->
        if (file != target && file != manifest) file.delete()
    }
}

private fun writeMonoPcm16WavHeader(output: OutputStream, sampleCount: Int) {
    val dataBytes = sampleCount.toLong() * WAV_BYTES_PER_SAMPLE
    output.write("RIFF".toByteArray(Charsets.US_ASCII))
    writeLittleEndian32(output, 36L + dataBytes)
    output.write("WAVE".toByteArray(Charsets.US_ASCII))
    output.write("fmt ".toByteArray(Charsets.US_ASCII))
    writeLittleEndian32(output, 16L)
    writeLittleEndian16(output, 1)
    writeLittleEndian16(output, 1)
    writeLittleEndian32(output, SONG_PREPARED_SAMPLE_RATE.toLong())
    writeLittleEndian32(output, SONG_PREPARED_SAMPLE_RATE.toLong() * WAV_BYTES_PER_SAMPLE)
    writeLittleEndian16(output, WAV_BYTES_PER_SAMPLE.toInt())
    writeLittleEndian16(output, 16)
    output.write("data".toByteArray(Charsets.US_ASCII))
    writeLittleEndian32(output, dataBytes)
}

private fun writeLittleEndian16(output: OutputStream, value: Int) {
    output.write(value and 0xff)
    output.write((value ushr 8) and 0xff)
}

private fun writeLittleEndian32(output: OutputStream, value: Long) {
    output.write((value and 0xffL).toInt())
    output.write((value ushr 8 and 0xffL).toInt())
    output.write((value ushr 16 and 0xffL).toInt())
    output.write((value ushr 24 and 0xffL).toInt())
}

private fun writePcm16Sample(output: OutputStream, sample: Float) {
    val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    output.write(pcm and 0xff)
    output.write((pcm ushr 8) and 0xff)
}

private fun padStereoForSeparator(source: FloatArray, wantedFrames: Int): FloatArray {
    require(source.size % 2 == 0)
    val wanted = wantedFrames * 2
    if (source.size == wanted) return source
    return FloatArray(wanted).also { output ->
        source.copyInto(output, endIndex = minOf(source.size, output.size))
    }
}

private fun downmixAndResamplePreparedVocals(
    interleavedStereo44100: FloatArray,
    usableFrames44100: Int
): FloatArray {
    val usable = usableFrames44100.coerceIn(1, interleavedStereo44100.size / 2)
    val outputFrames = (usable.toLong() * SONG_PREPARED_SAMPLE_RATE / SONG_SAMPLE_RATE)
        .coerceAtLeast(1L)
        .toInt()
    val output = FloatArray(outputFrames)
    val scale = SONG_SAMPLE_RATE.toDouble() / SONG_PREPARED_SAMPLE_RATE.toDouble()
    for (index in output.indices) {
        val position = index * scale
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
        output[index] = a + (b - a) * fraction
    }
    return output
}

private fun overlapAddPrepared(
    destination: FloatArray,
    weights: FloatArray,
    source: FloatArray,
    outputOffset: Int,
    fullWindowSamples: Int
) {
    source.forEachIndexed { index, sample ->
        val target = outputOffset + index
        if (target !in destination.indices) return@forEachIndexed
        val weight = preparedHammingWeight(index, fullWindowSamples)
        destination[target] += sample * weight
        weights[target] += weight
    }
}

private fun preparedHammingWeight(index: Int, size: Int): Float {
    if (size <= 1) return 1f
    val safeIndex = index.coerceIn(0, size - 1)
    return (0.54 - 0.46 * cos(2.0 * PI * safeIndex / (size - 1))).toFloat()
}

private fun shiftPreparedBuffer(values: FloatArray, count: Int) {
    if (count <= 0) return
    if (count >= values.size) {
        values.fill(0f)
        return
    }
    values.copyInto(values, destinationOffset = 0, startIndex = count, endIndex = values.size)
    values.fill(0f, values.size - count, values.size)
}

private inline fun <T> songStage(label: String, block: () -> T): T = try {
    block()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: Exception) {
    val detail = failure.localizedMessage?.takeIf(String::isNotBlank)
        ?: failure.javaClass.simpleName
    throw IllegalStateException("$label: $detail", failure)
}

private fun moveAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private class SongPreparedWaveform(private val totalSamples: Int) {
    private val peaks = FloatArray(WAVEFORM_BAR_COUNT)

    fun add(absoluteSample: Int, value: Float) {
        if (absoluteSample !in 0 until totalSamples) return
        val index = (absoluteSample.toLong() * peaks.size / totalSamples.toLong())
            .toInt()
            .coerceIn(0, peaks.lastIndex)
        peaks[index] = maxOf(peaks[index], kotlin.math.abs(value))
    }

    fun normalized(): List<Float> {
        val maximum = peaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
        return peaks.map { (it / maximum).coerceIn(0.04f, 1f) }
    }
}
