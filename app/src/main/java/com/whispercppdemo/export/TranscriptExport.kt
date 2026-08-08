package de.matthiasennen.transcript.export

import com.whispercpp.whisper.WhisperSegment
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String) {
    TEXT("txt", "text/plain"),
    SUBRIP("srt", "application/x-subrip"),
    JSON("json", "application/json")
}

data class TranscriptExportMetadata(
    val whisperModel: String,
    val detectedLanguage: String,
    val transcriptionDurationSeconds: Long,
    val createdAt: String
)

fun exportTranscript(
    segments: List<WhisperSegment>,
    format: ExportFormat,
    metadata: TranscriptExportMetadata
): String = when (format) {
    ExportFormat.TEXT -> buildString {
        appendLine("Whisper-Modell: ${metadata.whisperModel}")
        appendLine("Erkannte Sprache: ${metadata.detectedLanguage}")
        appendLine("Transkriptionsdauer: ${formatDuration(metadata.transcriptionDurationSeconds)}")
        appendLine("Erstellt am: ${metadata.createdAt}")
        appendLine()
        append(segments.joinToString("\n") { it.text })
    }
    ExportFormat.SUBRIP -> segments.mapIndexed { index, segment ->
        buildString {
            appendLine(index + 1)
            append(formatTimestamp(segment.startMs, comma = true))
            append(" --> ")
            appendLine(formatTimestamp(segment.endMs, comma = true))
            appendLine(segment.text)
        }
    }.joinToString("\n")

    ExportFormat.JSON -> buildJsonExport(segments, metadata)
}

private fun buildJsonExport(
    segments: List<WhisperSegment>,
    metadata: TranscriptExportMetadata
): String = buildString {
    appendLine("{")
    appendLine("  \"whisper_model\": ${metadata.whisperModel.asJsonString()},")
    appendLine("  \"detected_language\": ${metadata.detectedLanguage.asJsonString()},")
    appendLine("  \"transcription_duration_seconds\": ${metadata.transcriptionDurationSeconds},")
    appendLine("  \"created_at\": ${metadata.createdAt.asJsonString()},")
    appendLine("  \"segments\": [")
    segments.forEachIndexed { index, segment ->
        appendLine("    {")
        appendLine("      \"start_ms\": ${segment.startMs},")
        appendLine("      \"end_ms\": ${segment.endMs},")
        appendLine("      \"text\": ${segment.text.asJsonString()}")
        append("    }")
        if (index < segments.lastIndex) append(',')
        appendLine()
    }
    appendLine("  ]")
    append('}')
}

private fun String.asJsonString(): String = buildString(length + 2) {
    append('"')
    this@asJsonString.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3_600L
    val minutes = safe % 3_600L / 60L
    val remainingSeconds = safe % 60L
    return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
}

fun formatTimestamp(milliseconds: Long, comma: Boolean = false): String {
    val safe = milliseconds.coerceAtLeast(0)
    val hours = safe / 3_600_000
    val minutes = safe % 3_600_000 / 60_000
    val seconds = safe % 60_000 / 1_000
    val millis = safe % 1_000
    val delimiter = if (comma) ',' else '.'
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d%c%03d",
        hours,
        minutes,
        seconds,
        delimiter,
        millis
    )
}
