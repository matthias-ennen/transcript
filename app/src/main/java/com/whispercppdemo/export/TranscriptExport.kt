package de.matthiasennen.transcript.export

import com.whispercpp.whisper.WhisperSegment
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String) {
    TEXT("txt", "text/plain"),
    SUBRIP("srt", "application/x-subrip"),
    JSON("json", "application/json")
}

fun exportTranscript(segments: List<WhisperSegment>, format: ExportFormat): String = when (format) {
    ExportFormat.TEXT -> segments.joinToString("\n") { it.text }
    ExportFormat.SUBRIP -> segments.mapIndexed { index, segment ->
        buildString {
            appendLine(index + 1)
            append(formatTimestamp(segment.startMs, comma = true))
            append(" --> ")
            appendLine(formatTimestamp(segment.endMs, comma = true))
            appendLine(segment.text)
        }
    }.joinToString("\n")

    ExportFormat.JSON -> JSONArray().apply {
        segments.forEach { segment ->
            put(
                JSONObject()
                    .put("start_ms", segment.startMs)
                    .put("end_ms", segment.endMs)
                    .put("text", segment.text)
            )
        }
    }.toString(2)
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
