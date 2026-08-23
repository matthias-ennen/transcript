package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TranscriptGroupingTest {
    private val segments = listOf(
        WhisperSegment(0L, 2_000L, "Start"),
        WhisperSegment(119_999L, 121_000L, "Ende Gruppe eins"),
        WhisperSegment(120_000L, 122_000L, "Start Gruppe zwei"),
        WhisperSegment(245_000L, 247_000L, "Gruppe drei")
    )

    @Test
    fun segmentsUseConfiguredTwoMinuteRangesByStartTimestamp() {
        val groups = groupTranscriptSegments(segments, sectionMinutes = 2)

        assertEquals(listOf(0L, 120_000L, 240_000L), groups.map { it.startMs })
        assertEquals(listOf(120_000L, 240_000L, 360_000L), groups.map { it.endMs })
        assertEquals(listOf(0, 1), groups.first().segments.map { it.originalIndex })
        assertEquals(2, groups[1].segments.single().originalIndex)
    }

    @Test
    fun allSupportedSectionLengthsProduceMatchingRanges() {
        (1..5).forEach { minutes ->
            val durationMs = minutes * 60_000L
            val sample = listOf(
                WhisperSegment(0L, 1_000L, "A"),
                WhisperSegment(durationMs - 1L, durationMs + 500L, "B"),
                WhisperSegment(durationMs, durationMs + 1_000L, "C")
            )

            val groups = groupTranscriptSegments(sample, minutes)

            assertEquals(listOf(0L, durationMs), groups.map { it.startMs })
            assertEquals(listOf(durationMs, durationMs * 2L), groups.map { it.endMs })
        }
    }

    @Test
    fun applyingOneGroupChangesNoOtherSegment() {
        val draft = segments.withUpdatedTranscriptText(2, "Korrigiert")
            .withUpdatedTranscriptText(0, "Darf nicht übernommen werden")

        val applied = applyTranscriptGroupEdits(
            original = segments,
            draft = draft,
            groupStartMs = 120_000L,
            sectionMinutes = 2
        )

        assertEquals("Start", applied[0].text)
        assertEquals("Korrigiert", applied[2].text)
        assertEquals(segments[3], applied[3])
    }

    @Test
    fun stableSegmentIdentityDoesNotDependOnEditedText() {
        val original = WhisperSegment(12_000L, 15_000L, "Original")
        val edited = original.copy(text = "Bearbeitet")

        assertEquals(
            stableTranscriptSegmentId(7, original),
            stableTranscriptSegmentId(7, edited)
        )
        assertNotEquals(
            stableTranscriptSegmentId(7, original),
            stableTranscriptSegmentId(8, original)
        )
    }
}
