package de.matthiasennen.transcript.ai

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

private const val AI_ANALYSIS_REQUEST_VERSION = 1
private const val MAX_ANALYSIS_SOURCE_BYTES = 16 * 1024 * 1024

internal data class AiTranscriptAnalysisRequest(
    val action: AiTranscriptAnalysisAction,
    val modelId: String,
    val fileName: String,
    val sourceText: String,
    val sourceFingerprint: String
)

internal class AiTranscriptAnalysisRequestStore(private val file: File) {
    fun write(request: AiTranscriptAnalysisRequest) {
        val sourceBytes = request.sourceText.toByteArray(Charsets.UTF_8)
        require(sourceBytes.isNotEmpty()) { "Das Transkript enthält keinen auswertbaren Text." }
        require(sourceBytes.size <= MAX_ANALYSIS_SOURCE_BYTES) {
            "Das Transkript ist für die lokale KI-Auswertung zu groß."
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
            output.writeInt(AI_ANALYSIS_REQUEST_VERSION)
            output.writeUTF(request.action.name)
            output.writeUTF(request.modelId)
            output.writeUTF(request.fileName.take(8_000))
            output.writeUTF(request.sourceFingerprint)
            output.writeInt(sourceBytes.size)
            output.write(sourceBytes)
        }
        if (file.exists()) check(file.delete()) { "Alter KI-Auswertungsauftrag konnte nicht ersetzt werden." }
        check(temporary.renameTo(file)) { "KI-Auswertungsauftrag konnte nicht gespeichert werden." }
    }

    fun read(): AiTranscriptAnalysisRequest? = runCatching {
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            check(input.readInt() == AI_ANALYSIS_REQUEST_VERSION)
            val action = AiTranscriptAnalysisAction.valueOf(input.readUTF())
            val modelId = input.readUTF()
            val fileName = input.readUTF()
            val fingerprint = input.readUTF()
            val sourceLength = input.readInt()
            check(sourceLength in 1..MAX_ANALYSIS_SOURCE_BYTES)
            val sourceBytes = ByteArray(sourceLength)
            input.readFully(sourceBytes)
            val sourceText = sourceBytes.toString(Charsets.UTF_8)
            check(aiTranscriptSourceFingerprint(sourceText) == fingerprint) {
                "Der gespeicherte KI-Auswertungsauftrag ist beschädigt."
            }
            AiTranscriptAnalysisRequest(
                action = action,
                modelId = modelId,
                fileName = fileName,
                sourceText = sourceText,
                sourceFingerprint = fingerprint
            )
        }
    }.getOrNull()

    fun clear() {
        if (file.exists()) file.delete()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        if (temporary.exists()) temporary.delete()
    }
}
