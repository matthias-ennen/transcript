package de.matthiasennen.transcript

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.TranscriptExportMetadata
import de.matthiasennen.transcript.export.exportTranscript
import de.matthiasennen.transcript.export.formatDuration
import de.matthiasennen.transcript.export.formatTimestamp
import de.matthiasennen.transcript.ui.main.TranscriptUiState
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.exportMetadata
import de.matthiasennen.transcript.ui.main.formatClock
import de.matthiasennen.transcript.ui.main.orderedShareFormats
import de.matthiasennen.transcript.ui.main.transcriptExportFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportTest {
    private val metadata = TranscriptExportMetadata(
        whisperModel = "Whisper Small Q5_1",
        detectedLanguage = "en",
        transcriptionDurationSeconds = 605L,
        createdAt = "2026-08-07T21:15:30+02:00"
    )
    private val segments = listOf(
        WhisperSegment(1_250, 3_500, "Too heavy"),
        WhisperSegment(4_000, 6_025, "to carry alone")
    )

    @Test
    fun formatsSrtWithCommaTimestamps() {
        val srt = exportTranscript(segments, ExportFormat.SUBRIP, metadata)
        assertTrue(srt.contains("00:00:01,250 --> 00:00:03,500"))
        assertTrue(srt.contains("2\n00:00:04,000 --> 00:00:06,025"))
        assertTrue(!srt.contains("Whisper-Modell"))
    }

    @Test
    fun exportsPlainTextWithMetadata() {
        assertEquals(
            "Whisper-Modell: Whisper Small Q5_1\n" +
                "Erkannte Sprache: en\n" +
                "Transkriptionsdauer: 00:10:05\n" +
                "Erstellt am: 2026-08-07T21:15:30+02:00\n\n" +
                "Too heavy\nto carry alone",
            exportTranscript(segments, ExportFormat.TEXT, metadata)
        )
    }

    @Test
    fun exportsJsonWithMetadataAndSegments() {
        val json = exportTranscript(segments, ExportFormat.JSON, metadata)

        assertTrue(json.contains("\"whisper_model\": \"Whisper Small Q5_1\""))
        assertTrue(json.contains("\"detected_language\": \"en\""))
        assertTrue(json.contains("\"transcription_duration_seconds\": 605"))
        assertTrue(json.contains("\"created_at\": \"2026-08-07T21:15:30+02:00\""))
        assertTrue(json.contains("\"segments\": ["))
        assertTrue(json.contains("\"start_ms\": 1250"))
        assertTrue(json.contains("\"origin\": \"whisper\""))
    }

    @Test
    fun exportsEmptyTimelineGapsOnlyToJsonAndMarksManualGapText() {
        val raw = listOf(WhisperSegment(2_000L, 4_000L, "Hallo"))
        val timeline = listOf(
            WhisperSegment(0L, 2_000L, ""),
            raw.single(),
            WhisperSegment(4_000L, 6_000L, "manuell ergänzt")
        )

        val json = exportTranscript(timeline, ExportFormat.JSON, metadata, raw)
        val text = exportTranscript(timeline, ExportFormat.TEXT, metadata, raw)
        val srt = exportTranscript(timeline, ExportFormat.SUBRIP, metadata, raw)

        assertTrue(json.contains("\"origin\": \"virtual_pause\""))
        assertTrue(json.contains("\"origin\": \"manual\""))
        assertTrue(json.contains("\"text\": \"\""))
        assertTrue(text.endsWith("Hallo\nmanuell ergänzt"))
        assertTrue(!srt.contains("00:00:00,000 --> 00:00:02,000"))
        assertTrue(srt.contains("manuell ergänzt"))
    }

    @Test
    fun escapesJsonControlCharacters() {
        val json = exportTranscript(
            listOf(WhisperSegment(0, 1, "Quote \" slash \\ line\nnext\titem")),
            ExportFormat.JSON,
            metadata
        )

        assertTrue(json.contains("Quote \\\" slash \\\\ line\\nnext\\titem"))
        assertTrue(!json.contains("line\nnext"))
    }

    @Test
    fun formatsTranscriptionDuration() {
        assertEquals("00:00:00", formatDuration(0L))
        assertEquals("00:10:05", formatDuration(605L))
        assertEquals("01:02:03", formatDuration(3_723L))
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

    @Test
    fun ordersSelectedShareFormatsLikeTheExportButtons() {
        assertEquals(
            listOf(ExportFormat.TEXT, ExportFormat.SUBRIP, ExportFormat.JSON),
            orderedShareFormats(setOf(ExportFormat.JSON, ExportFormat.TEXT, ExportFormat.SUBRIP))
        )
        assertEquals(
            listOf(ExportFormat.SUBRIP, ExportFormat.JSON),
            orderedShareFormats(setOf(ExportFormat.JSON, ExportFormat.SUBRIP))
        )
    }

    @Test
    fun usesTheSameSafeFileNamesForExportAndShare() {
        val state = TranscriptUiState(selectedFileName = "Interview: Teil 1.mp3")

        assertEquals(
            "Interview_ Teil 1 Transcript.txt",
            transcriptExportFileName(state, ExportFormat.TEXT)
        )
        assertEquals(
            "Transcript Transcript.json",
            transcriptExportFileName(TranscriptUiState(), ExportFormat.JSON)
        )
    }

    @Test
    fun createsOneMetadataSnapshotForAllSharedFormats() {
        val metadata = TranscriptUiState(
            selectedModel = WhisperModel.SMALL_Q5_1,
            completedModel = WhisperModel.BASE,
            detectedLanguage = "de",
            transcriptionDurationSeconds = 42L
        ).exportMetadata(createdAt = "2026-08-08T22:30:00Z")

        assertEquals(WhisperModel.BASE.modelLabel, metadata.whisperModel)
        assertEquals("de", metadata.detectedLanguage)
        assertEquals(42L, metadata.transcriptionDurationSeconds)
        assertEquals("2026-08-08T22:30:00Z", metadata.createdAt)
    }
}
