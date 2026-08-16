package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val CHECKPOINT_MAGIC = 0x54524350
private const val CHECKPOINT_VERSION = 4
private const val MAX_STRING_BYTES = 4 * 1024 * 1024
private const val MAX_SEGMENT_COUNT = 1_000_000

data class TranscriptionRequest(
    val uri: String,
    val fileName: String,
    val configuration: TranscriptionJobConfiguration,
    val jobId: String = ""
) {
    val modelId: String get() = configuration.modelId
    val language: String get() = configuration.language
}

data class TranscriptionCheckpoint(
    val request: TranscriptionRequest,
    val durationMs: Long,
    val nextStartMs: Long,
    val detectedLanguage: String?,
    val startedAtEpochMs: Long,
    val segments: List<WhisperSegment>
) {
    fun isCompatibleWith(request: TranscriptionRequest, actualDurationMs: Long): Boolean =
        this.request.sameWorkAs(request) && durationMs == actualDurationMs &&
            nextStartMs in 0L..actualDurationMs

    fun hasMeaningfulProgress(): Boolean = nextStartMs > 0L
}

internal fun TranscriptionRequest.sameWorkAs(other: TranscriptionRequest): Boolean =
    uri == other.uri && fileName == other.fileName &&
        configuration.normalized() == other.configuration.normalized()

class TranscriptionCheckpointStore(private val checkpointFile: File) {
    private val temporaryFile = File(checkpointFile.parentFile, "${checkpointFile.name}.tmp")

    fun read(): TranscriptionCheckpoint? = runCatching {
        if (!checkpointFile.isFile) return null
        DataInputStream(BufferedInputStream(checkpointFile.inputStream())).use { input ->
            check(input.readInt() == CHECKPOINT_MAGIC) { "Unbekannte Zwischensicherungsdatei." }
            val version = input.readInt()
            check(version == CHECKPOINT_VERSION) { "Veraltete Zwischensicherung." }
            val request = TranscriptionRequest(
                jobId = input.readSizedString(),
                uri = input.readSizedString(),
                fileName = input.readSizedString(),
                configuration = TranscriptionJobConfiguration.decode(input.readSizedString())
            )
            val durationMs = input.readLong()
            val nextStartMs = input.readLong()
            val detectedLanguage = input.readNullableString()
            val startedAtEpochMs = input.readLong()
            val segmentCount = input.readInt()
            check(segmentCount in 0..MAX_SEGMENT_COUNT) { "Ungültige Segmentanzahl." }
            val segments = List(segmentCount) {
                WhisperSegment(
                    startMs = input.readLong(),
                    endMs = input.readLong(),
                    text = input.readSizedString()
                )
            }
            TranscriptionCheckpoint(
                request = request,
                durationMs = durationMs,
                nextStartMs = nextStartMs,
                detectedLanguage = detectedLanguage,
                startedAtEpochMs = startedAtEpochMs,
                segments = segments
            )
        }
    }.getOrElse {
        clear()
        null
    }

    fun write(checkpoint: TranscriptionCheckpoint) {
        checkpointFile.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(temporaryFile.outputStream())).use { output ->
            output.writeInt(CHECKPOINT_MAGIC)
            output.writeInt(CHECKPOINT_VERSION)
            output.writeSizedString(checkpoint.request.jobId)
            output.writeSizedString(checkpoint.request.uri)
            output.writeSizedString(checkpoint.request.fileName)
            output.writeSizedString(checkpoint.request.configuration.encode())
            output.writeLong(checkpoint.durationMs)
            output.writeLong(checkpoint.nextStartMs)
            output.writeNullableString(checkpoint.detectedLanguage)
            output.writeLong(checkpoint.startedAtEpochMs)
            output.writeInt(checkpoint.segments.size)
            checkpoint.segments.forEach { segment ->
                output.writeLong(segment.startMs)
                output.writeLong(segment.endMs)
                output.writeSizedString(segment.text)
            }
        }
        try {
            Files.move(
                temporaryFile.toPath(),
                checkpointFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile.toPath(),
                checkpointFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun clear() {
        if (temporaryFile.exists()) temporaryFile.delete()
        if (checkpointFile.exists()) checkpointFile.delete()
    }
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeSizedString(value)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readSizedString() else null

private fun DataOutputStream.writeSizedString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_STRING_BYTES) { "Textabschnitt ist zu groß." }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedString(): String {
    val size = readInt()
    check(size in 0..MAX_STRING_BYTES) { "Ungültige Textlänge." }
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}
