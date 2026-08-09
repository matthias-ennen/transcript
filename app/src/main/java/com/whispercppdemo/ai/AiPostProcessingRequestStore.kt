package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

private const val FORMAT_VERSION = 1

internal data class AiPostProcessingRequest(
    val mode: AiPostProcessingMode,
    val modelId: String,
    val fileName: String,
    val groupStartMs: Long?,
    val segments: List<WhisperSegment>,
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
            check(input.readInt() == FORMAT_VERSION)
            val mode = AiPostProcessingMode.valueOf(input.readUTF())
            val modelId = input.readUTF()
            val fileName = input.readUTF()
            val groupStartMs = if (input.readBoolean()) input.readLong() else null
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
