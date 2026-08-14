package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.StatusMessageKind
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val STATE_MAGIC = 0x54525354
private const val STATE_VERSION = 3
private const val MAX_STATE_STRING_BYTES = 4 * 1024 * 1024
private const val MAX_STATE_LIST_COUNT = 1_000_000

data class PersistedTranscriptionState(
    val workerStartedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val state: TranscriptionState
)

class TranscriptionStateStore(private val stateFile: File) {
    private val temporaryFile = File(stateFile.parentFile, "${stateFile.name}.tmp")

    fun read(): PersistedTranscriptionState? = runCatching {
        if (!stateFile.isFile) return null
        DataInputStream(BufferedInputStream(stateFile.inputStream())).use { input ->
            check(input.readInt() == STATE_MAGIC) { "Unbekannte Statusdatei." }
            val version = input.readInt()
            check(version in 1..STATE_VERSION) { "Veraltete Statusdatei." }
            PersistedTranscriptionState(
                input.readLong(),
                input.readLong(),
                input.readState(version)
            )
        }
    }.getOrElse {
        clear()
        null
    }

    fun write(envelope: PersistedTranscriptionState) {
        stateFile.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(temporaryFile.outputStream())).use { output ->
            output.writeInt(STATE_MAGIC)
            output.writeInt(STATE_VERSION)
            output.writeLong(envelope.workerStartedAtEpochMs)
            output.writeLong(envelope.updatedAtEpochMs)
            output.writeState(envelope.state)
        }
        try {
            Files.move(temporaryFile.toPath(), stateFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryFile.toPath(), stateFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear() {
        if (temporaryFile.exists()) temporaryFile.delete()
        if (stateFile.exists()) stateFile.delete()
    }
}

private fun DataOutputStream.writeState(state: TranscriptionState) {
    when (state) {
        TranscriptionState.Idle -> writeByte(0)
        is TranscriptionState.Starting -> { writeByte(1); writeSizedString(state.fileName) }
        is TranscriptionState.Running -> {
            writeByte(2); writeSizedString(state.fileName); writeSizedString(state.model.id)
            writeFloat(state.progress); writeInt(state.sectionNumber); writeInt(state.sectionCount)
            writeLong(state.startedAtEpochMs); writeLong(state.elapsedSeconds)
            writeSizedString(state.status); writeSizedString(state.activityDetail)
            writeStringList(state.diagnostics); writeSegments(state.committedSegments)
            writeNullableString(state.detectedLanguage)
            writeSizedString(state.statusKind.name)
        }
        is TranscriptionState.Completed -> {
            writeByte(3); writeSizedString(state.fileName); writeSizedString(state.model.id)
            writeSegments(state.segments); writeSizedString(state.detectedLanguage)
            writeLong(state.transcriptionDurationSeconds)
            writeVadSummary(state.vadSummary)
        }
        is TranscriptionState.Cancelled -> { writeByte(4); writeSizedString(state.fileName) }
        is TranscriptionState.Failed -> {
            writeByte(5); writeSizedString(state.fileName); writeSizedString(state.message)
            writeBoolean(state.canResume); writeSegments(state.committedSegments)
        }
    }
}

private fun DataInputStream.readState(version: Int): TranscriptionState = when (readByte().toInt()) {
    0 -> TranscriptionState.Idle
    1 -> TranscriptionState.Starting(readSizedString())
    2 -> TranscriptionState.Running(
        readSizedString(), WhisperModel.fromId(readSizedString()), readFloat(), readInt(), readInt(),
        readLong(), readLong(), readSizedString(), readSizedString(), readStringList(),
        readSegments(), readNullableString(),
        if (version >= 2) {
            runCatching { StatusMessageKind.valueOf(readSizedString()) }
                .getOrDefault(StatusMessageKind.PROGRESS)
        } else {
            StatusMessageKind.PROGRESS
        }
    )
    3 -> TranscriptionState.Completed(
        readSizedString(), WhisperModel.fromId(readSizedString()), readSegments(),
        readSizedString(), readLong(), if (version >= 3) readVadSummary() else null
    )
    4 -> TranscriptionState.Cancelled(readSizedString())
    5 -> TranscriptionState.Failed(
        readSizedString(), readSizedString(), readBoolean(), readSegments()
    )
    else -> error("Unbekannter Transkriptionsstatus.")
}

private fun DataOutputStream.writeSegments(segments: List<WhisperSegment>) {
    require(segments.size <= MAX_STATE_LIST_COUNT) { "Zu viele Segmente." }
    writeInt(segments.size)
    segments.forEach { writeLong(it.startMs); writeLong(it.endMs); writeSizedString(it.text) }
}

private fun DataInputStream.readSegments(): List<WhisperSegment> {
    val count = readInt()
    check(count in 0..MAX_STATE_LIST_COUNT) { "Ungültige Segmentanzahl." }
    return List(count) { WhisperSegment(readLong(), readLong(), readSizedString()) }
}

private fun DataOutputStream.writeStringList(values: List<String>) {
    require(values.size <= MAX_STATE_LIST_COUNT) { "Zu viele Diagnoseeinträge." }
    writeInt(values.size)
    values.forEach(::writeSizedString)
}

private fun DataInputStream.readStringList(): List<String> {
    val count = readInt()
    check(count in 0..MAX_STATE_LIST_COUNT) { "Ungültige Listenlänge." }
    return List(count) { readSizedString() }
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeSizedString(value)
}

private fun DataInputStream.readNullableString(): String? =
    if (readBoolean()) readSizedString() else null

private fun DataOutputStream.writeVadSummary(summary: VadProcessingSummary?) {
    writeBoolean(summary != null)
    if (summary == null) return
    writeSizedString(summary.requestedMode.name)
    writeBoolean(summary.usedVad)
    writeLong(summary.originalDurationMs)
    writeLong(summary.processedDurationMs)
    writeLong(summary.skippedDurationMs)
    writeInt(summary.speechRegionCount)
    writeSizedString(summary.reason)
    writeBoolean(summary.measurementsAvailable)
}

private fun DataInputStream.readVadSummary(): VadProcessingSummary? {
    if (!readBoolean()) return null
    return VadProcessingSummary(
        requestedMode = runCatching { WhisperVadMode.valueOf(readSizedString()) }
            .getOrDefault(WhisperVadMode.OFF),
        usedVad = readBoolean(),
        originalDurationMs = readLong().coerceAtLeast(0L),
        processedDurationMs = readLong().coerceAtLeast(0L),
        skippedDurationMs = readLong().coerceAtLeast(0L),
        speechRegionCount = readInt().coerceAtLeast(0),
        reason = readSizedString(),
        measurementsAvailable = readBoolean()
    )
}

private fun DataOutputStream.writeSizedString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_STATE_STRING_BYTES) { "Text ist zu groß." }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedString(): String {
    val size = readInt()
    check(size in 0..MAX_STATE_STRING_BYTES) { "Ungültige Textlänge." }
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}
