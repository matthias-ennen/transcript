package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class AiPostProcessingGroupingTest {
    private val segments = listOf(
        WhisperSegment(10_000L, 20_000L, "Gruppe null"),
        WhisperSegment(130_000L, 140_000L, "Gruppe zwei"),
        WhisperSegment(250_000L, 260_000L, "Gruppe vier vor fünf Minuten"),
        WhisperSegment(310_000L, 320_000L, "Gruppe vier nach fünf Minuten"),
        WhisperSegment(370_000L, 380_000L, "Gruppe sechs")
    )

    @Test
    fun manualTwoMinuteGroupMatchesVisibleBoundaryAcrossFiveMinuteMark() {
        val groups = aiPostProcessingGroups(
            segments = segments,
            mode = AiPostProcessingMode.MANUAL_GROUP,
            groupStartMs = 240_000L,
            sectionMinutes = 2
        )

        assertEquals(listOf(listOf(2, 3)), groups)
    }

    @Test
    fun manualTwoMinuteGroupProcessesOnlySelectedVisibleGroup() {
        val groups = aiPostProcessingGroups(
            segments = segments,
            mode = AiPostProcessingMode.MANUAL_GROUP,
            groupStartMs = 120_000L,
            sectionMinutes = 2
        )

        assertEquals(listOf(listOf(1)), groups)
    }

    @Test
    fun automaticGroupingUsesFrozenSectionMinutes() {
        val groups = aiPostProcessingGroups(
            segments = segments,
            mode = AiPostProcessingMode.AUTOMATIC,
            groupStartMs = null,
            sectionMinutes = 2
        )

        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), groups)
    }
}
