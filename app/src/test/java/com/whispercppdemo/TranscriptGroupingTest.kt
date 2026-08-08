package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptGroupingTest {
    private val segments = listOf(
        WhisperSegment(0L, 2_000L, "Start"),
        WhisperSegment(299_999L, 301_000L, "Ende Gruppe eins"),
        WhisperSegment(300_000L, 302_000L, "Start Gruppe zwei"),
        WhisperSegment(605_000L, 607_000L, "Gruppe drei")
    )

    @Test
    fun segmentsAreGroupedIntoFiveMinuteRangesByStartTimestamp() {
        val groups = groupTranscriptSegments(segments)

        assertEquals(listOf(0L, 300_000L, 600_000L), groups.map { it.startMs })
        assertEquals(listOf(300_000L, 600_000L, 900_000L), groups.map { it.endMs })
        assertEquals(listOf(0, 1), groups.first().segments.map { it.originalIndex })
        assertEquals(2, groups[1].segments.single().originalIndex)
    }

    @Test
    fun applyingOneGroupChangesNoOtherSegment() {
        val draft = segments.withUpdatedTranscriptText(2, "Korrigiert")
            .withUpdatedTranscriptText(0, "Darf nicht übernommen werden")

        val applied = applyTranscriptGroupEdits(
            original = segments,
            draft = draft,
            groupStartMs = 300_000L
        )

        assertEquals("Start", applied[0].text)
        assertEquals("Korrigiert", applied[2].text)
        assertEquals(segments[3], applied[3])
    }
}
