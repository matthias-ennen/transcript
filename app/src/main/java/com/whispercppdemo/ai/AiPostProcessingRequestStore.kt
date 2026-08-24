package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

private const val FORMAT_VERSION = 2
private const val MIN_SECTION_MINUTES = 1
private const val MAX_SECTION_MINUTES = 5
private const val LEGACY_SECTION_MINUTES = 5

internal data class AiPostProcessingRequest(
    val mode: AiPostProcessingMode,
    val modelId: String,
    val fileName: String,
    val groupStartMs: Long?,
    val segments: List<WhisperSegment>,
    val sectionMinutes: Int = LEGACY_SECTION_MINUTES,
    val nextGroupIndex: Int = 0
)

internal class AiPostProcessingRequestStore(private val file: File) {
    fun write(request: AiPostProcessingRequest) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeUTF(request.mode.name)
            output.writeUTF(request.modelId)
            output.writeUTF(request.fileName)
            output.writeBoolean(request.groupStartMs != null)
            request.groupStartMs?.let(output::writeLong)
            output.writeInt(request.sectionMinutes.coerceIn(MIN_SECTION_MINUTES, MAX_SECTION_MINUTES))
            output.writeInt(request.nextGroupIndex)
            output.writeInt(request.segments.size)
            request.segments.forEach { segment ->
                output.writeLong(segment.startMs)
                output.writeLong(segment.endMs)
                output.writeUTF(segment.text)
            }
        }
        if (file.exists()) check(file.delete()) { "Alter KI-Zwischenstand konnte nicht ersetzt werden." }
        check(temporary.renameTo(file)) { "KI-Zwischenstand konnte nicht gespeichert werden." }
    }

    fun read(): AiPostProcessingRequest? = runCatching {
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            val version = input.readInt()
            check(version in 1..FORMAT_VERSION)
            val mode = AiPostProcessingMode.valueOf(input.readUTF())
            val modelId = input.readUTF()
            val fileName = input.readUTF()
            val groupStartMs = if (input.readBoolean()) input.readLong() else null
            val sectionMinutes = if (version >= 2) {
                input.readInt().coerceIn(MIN_SECTION_MINUTES, MAX_SECTION_MINUTES)
            } else {
                LEGACY_SECTION_MINUTES
            }
            val nextGroupIndex = input.readInt()
            val count = input.readInt().coerceIn(0, 100_000)
            val segments = List(count) {
                WhisperSegment(input.readLong(), input.readLong(), input.readUTF())
            }
            AiPostProcessingRequest(
                mode = mode,
                modelId = modelId,
                fileName = fileName,
                groupStartMs = groupStartMs,
                segments = segments,
                sectionMinutes = sectionMinutes,
                nextGroupIndex = nextGroupIndex
            )
        }
    }.getOrNull()

    fun clear() {
        if (file.exists()) file.delete()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        if (temporary.exists()) temporary.delete()
    }
}
