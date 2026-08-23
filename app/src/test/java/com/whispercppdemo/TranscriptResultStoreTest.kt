package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class TranscriptResultStoreTest {
    @Test
    fun `result survives a new store instance`() {
        val directory = Files.createTempDirectory("transcript-result-test").toFile()
        try {
            val file = File(directory, "current.bin")
            val original = StoredTranscriptResult(
                sourceUri = "content://audio/one",
                fileName = "aufnahme.m4a",
                modelId = "balanced",
                detectedLanguage = "de",
                transcriptionDurationSeconds = 42L,
                savedAtEpochMs = 1234L,
                rawWhisperSegments = listOf(WhisperSegment(0L, 1000L, "Rohtext")),
                displayedSegments = listOf(WhisperSegment(0L, 1000L, "Korrigierter Text")),
                vadSummary = VadProcessingSummary(
                    requestedMode = WhisperVadMode.AUTOMATIC,
                    usedVad = true,
                    originalDurationMs = 10_000L,
                    processedDurationMs = 7_000L,
                    skippedDurationMs = 3_000L,
                    speechRegionCount = 3,
                    reason = "klare Pausen"
                ),
                sectionMinutes = 2
            )

            TranscriptResultStore(file).write(original)

            assertEquals(original, TranscriptResultStore(file).read())
            assertEquals(2, TranscriptResultStore(file).read()?.sectionMinutes)
            assertFalse(File(directory, "current.bin.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `version two result keeps legacy five minute grouping`() {
        val directory = Files.createTempDirectory("transcript-result-v2").toFile()
        try {
            val file = File(directory, "current.bin")
            DataOutputStream(file.outputStream().buffered()).use { output ->
                output.writeInt(0x54525253)
                output.writeInt(2)
                output.writeLegacyString("content://audio/legacy")
                output.writeLegacyString("alt.m4a")
                output.writeLegacyString("base")
                output.writeLegacyString("de")
                output.writeLong(10L)
                output.writeLong(123L)
                output.writeLegacySegments(listOf(WhisperSegment(0L, 1_000L, "Original")))
                output.writeLegacySegments(listOf(WhisperSegment(0L, 1_000L, "Bearbeitet")))
                output.writeBoolean(false)
            }

            val restored = TranscriptResultStore(file).read()

            assertEquals(5, restored?.sectionMinutes)
            assertEquals("Bearbeitet", restored?.displayedSegments?.single()?.text)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `corrupt result is removed instead of restored`() {
        val directory = Files.createTempDirectory("transcript-result-corrupt").toFile()
        try {
            val file = File(directory, "current.bin").apply { writeText("kaputt") }

            assertNull(TranscriptResultStore(file).read())
            assertFalse(file.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `orphaned temporary result is removed on startup`() {
        val directory = Files.createTempDirectory("transcript-result-temporary").toFile()
        try {
            File(directory, "current.bin.tmp").writeText("unvollständig")

            assertNull(TranscriptResultStore(File(directory, "current.bin")).read())
            assertFalse(File(directory, "current.bin.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

private fun DataOutputStream.writeLegacyString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeLegacySegments(segments: List<WhisperSegment>) {
    writeInt(segments.size)
    segments.forEach { segment ->
        writeLong(segment.startMs)
        writeLong(segment.endMs)
        writeLegacyString(segment.text)
    }
}
