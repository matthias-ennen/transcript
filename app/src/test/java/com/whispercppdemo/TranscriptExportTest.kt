package de.matthiasennen.transcript

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.exportTranscript
import de.matthiasennen.transcript.export.formatTimestamp
import de.matthiasennen.transcript.ui.main.formatClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportTest {
    private val whisperModel = "Whisper Small Q5_1"
    private val segments = listOf(
        WhisperSegment(1_250, 3_500, "Too heavy"),
        WhisperSegment(4_000, 6_025, "to carry alone")
    )

    @Test
    fun formatsSrtWithCommaTimestamps() {
        val srt = exportTranscript(segments, ExportFormat.SUBRIP, whisperModel)
        assertTrue(srt.contains("00:00:01,250 --> 00:00:03,500"))
        assertTrue(srt.contains("2\n00:00:04,000 --> 00:00:06,025"))
    }

    @Test
    fun exportsPlainTextWithWhisperModel() {
        assertEquals(
            "Whisper-Modell: Whisper Small Q5_1\n\nToo heavy\nto carry alone",
            exportTranscript(segments, ExportFormat.TEXT, whisperModel)
        )
    }

    @Test
    fun exportsJsonWithWhisperModelAndSegments() {
        val json = exportTranscript(segments, ExportFormat.JSON, whisperModel)

        assertTrue(json.contains("\"whisper_model\": \"Whisper Small Q5_1\""))
        assertTrue(json.contains("\"segments\": ["))
        assertTrue(json.contains("\"start_ms\": 1250"))
    }

    @Test
    fun formatsLongDurations() {
        assertEquals("01:02:03.004", formatTimestamp(3_723_004))
    }

    @Test
    fun formatsDiagnosticRuntime() {
        assertEquals("00:00", formatClock(0))
        assertEquals("03:40", formatClock(220))
        assertEquals("1:02:03", formatClock(3_723))
    }
}
