package de.matthiasennen.transcript.transcription

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.roundToInt

internal const val PREPARED_SAMPLE_RATE = 16_000
internal const val PREPARED_BYTES_PER_SAMPLE = 2L
internal const val PREPARED_SAFETY_RESERVE_BYTES = 64L * 1024L * 1024L
private const val MANIFEST_MAGIC = 0x54525043
private const val MANIFEST_VERSION = 1
private const val MAX_PREPARED_SECTIONS = 100_000
private const val MAX_WAVEFORM_PEAKS = 1_000

internal data class PreparedAudioSection(
    val index: Int,
    val section: TranscriptionSection,
    val fileName: String,
    val sampleCount: Int
)

internal data class PreparedAudioManifest(
    val requestKey: String,
    val durationMs: Long,
    val sectionDurationMs: Long,
    val complete: Boolean,
    val sections: List<PreparedAudioSection>,
    val waveformPeaks: List<Float>
)

internal fun preparedAudioRequestKey(request: TranscriptionRequest, durationMs: Long): String {
    val identity = listOf(
        request.jobId,
        request.uri,
        request.fileName,
        request.configuration.encode(),
        durationMs.toString()
    ).joinToString("|")
    return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

internal fun estimatePreparedAudioBytes(sections: List<TranscriptionSection>): Long =
    sections.sumOf { section ->
        val durationMs = (section.decodeEndMs - section.decodeStartMs).coerceAtLeast(0L)
        durationMs * PREPARED_SAMPLE_RATE * PREPARED_BYTES_PER_SAMPLE / 1_000L
    }

internal fun requiredPreparedAudioFreeBytes(estimatedBytes: Long): Long =
    estimatedBytes.coerceAtLeast(0L) +
        maxOf(PREPARED_SAFETY_RESERVE_BYTES, estimatedBytes.coerceAtLeast(0L) / 10L)

internal class PreparedAudioStore(private val directory: File) {
    private val manifestFile = File(directory, "manifest.bin")
    private val temporaryManifestFile = File(directory, "manifest.bin.part")

    val usableSpace: Long get() = (directory.parentFile ?: directory).usableSpace

    fun readManifest(): PreparedAudioManifest? = runCatching {
        if (!manifestFile.isFile) return null
        DataInputStream(BufferedInputStream(manifestFile.inputStream())).use { input ->
            check(input.readInt() == MANIFEST_MAGIC) { "Unbekanntes PCM-Manifest." }
            check(input.readInt() == MANIFEST_VERSION) { "Veraltetes PCM-Manifest." }
            val requestKey = input.readUTF()
            val durationMs = input.readLong()
            val sectionDurationMs = input.readLong()
            val complete = input.readBoolean()
            val sectionCount = input.readInt()
            check(sectionCount in 0..MAX_PREPARED_SECTIONS) { "Ungültige PCM-Abschnittszahl." }
            val sections = List(sectionCount) {
                val index = input.readInt()
                val section = TranscriptionSection(
                    mainStartMs = input.readLong(),
                    mainEndMs = input.readLong(),
                    decodeStartMs = input.readLong(),
                    decodeEndMs = input.readLong(),
                    usedFallbackSize = input.readBoolean()
                )
                PreparedAudioSection(index, section, input.readUTF(), input.readInt())
            }
            val peakCount = input.readInt()
            check(peakCount in 0..MAX_WAVEFORM_PEAKS) { "Ungültige Wellenformgröße." }
            val peaks = List(peakCount) { input.readFloat().also { check(it.isFinite()) } }
            PreparedAudioManifest(
                requestKey = requestKey,
                durationMs = durationMs,
                sectionDurationMs = sectionDurationMs,
                complete = complete,
                sections = sections,
                waveformPeaks = peaks
            )
        }
    }.getOrElse {
        clear()
        null
    }

    fun writeManifest(manifest: PreparedAudioManifest) {
        directory.mkdirs()
        DataOutputStream(BufferedOutputStream(temporaryManifestFile.outputStream())).use { output ->
            output.writeInt(MANIFEST_MAGIC)
            output.writeInt(MANIFEST_VERSION)
            output.writeUTF(manifest.requestKey)
            output.writeLong(manifest.durationMs)
            output.writeLong(manifest.sectionDurationMs)
            output.writeBoolean(manifest.complete)
            output.writeInt(manifest.sections.size)
            manifest.sections.forEach { prepared ->
                output.writeInt(prepared.index)
                output.writeLong(prepared.section.mainStartMs)
                output.writeLong(prepared.section.mainEndMs)
                output.writeLong(prepared.section.decodeStartMs)
                output.writeLong(prepared.section.decodeEndMs)
                output.writeBoolean(prepared.section.usedFallbackSize)
                output.writeUTF(prepared.fileName)
                output.writeInt(prepared.sampleCount)
            }
            output.writeInt(manifest.waveformPeaks.size)
            manifest.waveformPeaks.forEach { output.writeFloat(it.coerceIn(0f, 1f)) }
        }
        moveAtomically(temporaryManifestFile, manifestFile)
    }

    fun writeSection(index: Int, samples: FloatArray): Pair<String, Int> {
        directory.mkdirs()
        val name = "section-${index.toString().padStart(5, '0')}.pcm16le"
        val target = File(directory, name)
        val temporary = File(directory, "$name.part")
        BufferedOutputStream(temporary.outputStream()).use { output ->
            samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.write(pcm and 0xff)
                output.write((pcm ushr 8) and 0xff)
            }
        }
        moveAtomically(temporary, target)
        return name to samples.size
    }

    fun readSection(prepared: PreparedAudioSection): FloatArray {
        val file = File(directory, prepared.fileName)
        check(file.isFile) { "Vorbereiteter Audioabschnitt ${prepared.index + 1} fehlt." }
        check(file.length() == prepared.sampleCount.toLong() * PREPARED_BYTES_PER_SAMPLE) {
            "Vorbereiteter Audioabschnitt ${prepared.index + 1} ist unvollständig."
        }
        return FloatArray(prepared.sampleCount).also { samples ->
            BufferedInputStream(file.inputStream()).use { input ->
                for (index in samples.indices) {
                    val low = input.read()
                    val high = input.read()
                    check(low >= 0 && high >= 0) { "Unerwartetes Ende des PCM-Abschnitts." }
                    val value = ((high shl 8) or low).toShort()
                    samples[index] = value / 32768f
                }
            }
        }
    }

    fun isUsable(manifest: PreparedAudioManifest, requestKey: String, nextStartMs: Long): Boolean =
        manifest.requestKey == requestKey && manifest.complete &&
            manifest.sections.filter { it.section.mainEndMs > nextStartMs }.all(::sectionExists)

    fun sectionExists(section: PreparedAudioSection): Boolean {
        val file = File(directory, section.fileName)
        return file.isFile && file.length() == section.sampleCount.toLong() * PREPARED_BYTES_PER_SAMPLE
    }

    fun deleteSection(section: PreparedAudioSection) {
        File(directory, section.fileName).delete()
    }

    fun clear() {
        directory.listFiles()?.forEach(File::delete)
        directory.delete()
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal class PreparedWaveformAccumulator(
    private val durationMs: Long,
    private val barCount: Int = 180
) {
    private val peaks = FloatArray(barCount.coerceAtLeast(1))

    fun add(section: TranscriptionSection, samples: FloatArray) {
        if (durationMs <= 0L || samples.isEmpty()) return
        val stride = (samples.size / (peaks.size * 4)).coerceAtLeast(1)
        var index = 0
        while (index < samples.size) {
            val absoluteMs = section.decodeStartMs + index.toLong() * 1_000L / PREPARED_SAMPLE_RATE
            val peakIndex = (absoluteMs * peaks.size / durationMs)
                .toInt()
                .coerceIn(0, peaks.lastIndex)
            peaks[peakIndex] = maxOf(peaks[peakIndex], kotlin.math.abs(samples[index]))
            index += stride
        }
    }

    fun restore(values: List<Float>) {
        values.take(peaks.size).forEachIndexed { index, value -> peaks[index] = value.coerceIn(0f, 1f) }
    }

    fun normalized(): List<Float> {
        val maximum = peaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 1f
        return peaks.map { (it / maximum).coerceIn(0.04f, 1f) }
    }
}
