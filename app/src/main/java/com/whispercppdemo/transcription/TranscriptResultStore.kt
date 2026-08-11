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
import java.util.concurrent.Executors

private const val RESULT_MAGIC = 0x54525253
private const val RESULT_VERSION = 1
private const val MAX_RESULT_STRING_BYTES = 4 * 1024 * 1024
private const val MAX_RESULT_FILE_BYTES = 64L * 1024L * 1024L
private const val MAX_RESULT_SEGMENTS = 100_000

data class StoredTranscriptResult(
    val sourceUri: String,
    val fileName: String,
    val modelId: String,
    val detectedLanguage: String,
    val transcriptionDurationSeconds: Long,
    val savedAtEpochMs: Long,
    val rawWhisperSegments: List<WhisperSegment>,
    val displayedSegments: List<WhisperSegment>
)

/** Atomically stores the one transcript currently shown on the main screen. */
class TranscriptResultStore(private val resultFile: File) {
    private val temporaryFile = File(resultFile.parentFile, "${resultFile.name}.tmp")

    @Synchronized
    fun read(): StoredTranscriptResult? = runCatching {
        if (!resultFile.isFile) {
            if (temporaryFile.exists()) temporaryFile.delete()
            return null
        }
        check(resultFile.length() in 1..MAX_RESULT_FILE_BYTES) { "Ungültige Transkriptdateigröße." }
        DataInputStream(BufferedInputStream(resultFile.inputStream())).use { input ->
            check(input.readInt() == RESULT_MAGIC) { "Unbekannte Transkriptdatei." }
            check(input.readInt() == RESULT_VERSION) { "Veraltete Transkriptdatei." }
            StoredTranscriptResult(
                sourceUri = input.readResultString(),
                fileName = input.readResultString(),
                modelId = input.readResultString(),
                detectedLanguage = input.readResultString(),
                transcriptionDurationSeconds = input.readLong().coerceAtLeast(0L),
                savedAtEpochMs = input.readLong().coerceAtLeast(0L),
                rawWhisperSegments = input.readSegments(),
                displayedSegments = input.readSegments()
            )
        }
    }.getOrElse {
        clear()
        null
    }

    @Synchronized
    fun write(result: StoredTranscriptResult) {
        resultFile.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(temporaryFile.outputStream())).use { output ->
            output.writeInt(RESULT_MAGIC)
            output.writeInt(RESULT_VERSION)
            output.writeResultString(result.sourceUri)
            output.writeResultString(result.fileName)
            output.writeResultString(result.modelId)
            output.writeResultString(result.detectedLanguage)
            output.writeLong(result.transcriptionDurationSeconds.coerceAtLeast(0L))
            output.writeLong(result.savedAtEpochMs.coerceAtLeast(0L))
            output.writeSegments(result.rawWhisperSegments)
            output.writeSegments(result.displayedSegments)
        }
        try {
            Files.move(
                temporaryFile.toPath(),
                resultFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile.toPath(),
                resultFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    @Synchronized
    fun clear() {
        if (temporaryFile.exists()) temporaryFile.delete()
        if (resultFile.exists()) resultFile.delete()
    }
}

/** Serializes result writes without blocking Compose's main thread. */
class TranscriptResultPersistence(private val store: TranscriptResultStore) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "transcript-result-writer").apply { isDaemon = true }
    }

    fun save(result: StoredTranscriptResult) {
        executor.execute { store.write(result) }
    }

    fun clear() {
        executor.execute(store::clear)
    }

    override fun close() {
        executor.shutdown()
    }
}

private fun DataOutputStream.writeSegments(segments: List<WhisperSegment>) {
    require(segments.size <= MAX_RESULT_SEGMENTS) { "Zu viele Transkriptsegmente." }
    writeInt(segments.size)
    segments.forEach { segment ->
        writeLong(segment.startMs)
        writeLong(segment.endMs)
        writeResultString(segment.text)
    }
}

private fun DataInputStream.readSegments(): List<WhisperSegment> {
    val count = readInt()
    check(count in 0..MAX_RESULT_SEGMENTS) { "Ungültige Segmentanzahl." }
    return List(count) {
        WhisperSegment(
            startMs = readLong(),
            endMs = readLong(),
            text = readResultString()
        )
    }
}

private fun DataOutputStream.writeResultString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_RESULT_STRING_BYTES) { "Transkripttext ist zu groß." }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readResultString(): String {
    val size = readInt()
    check(size in 0..MAX_RESULT_STRING_BYTES) { "Ungültige Textlänge." }
    return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
}
