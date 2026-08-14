package de.matthiasennen.transcript

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.buildTranscriptTimeline
import de.matthiasennen.transcript.ui.main.isVirtualTimelineSegment
import de.matthiasennen.transcript.ui.main.restoreManualTimelineText
import de.matthiasennen.transcript.ui.main.transcriptNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTimelineTest {
    @Test
    fun fillsBeginningMiddleAndEndWithEditableEmptySegments() {
        val raw = listOf(
            WhisperSegment(2_000L, 4_000L, "Hallo"),
            WhisperSegment(6_000L, 8_000L, "Welt")
        )

        val timeline = buildTranscriptTimeline(raw, 10_000L)

        assertEquals(
            listOf(0L to 2_000L, 2_000L to 4_000L, 4_000L to 6_000L,
                6_000L to 8_000L, 8_000L to 10_000L),
            timeline.map { it.startMs to it.endMs }
        )
        assertEquals(listOf("", "Hallo", "", "Welt", ""), timeline.map { it.text })
        assertTrue(isVirtualTimelineSegment(timeline[0], raw))
        assertFalse(isVirtualTimelineSegment(timeline[1], raw))
    }

    @Test
    fun absorbsSubsecondTechnicalGapsWithoutCreatingTinyCards() {
        val raw = listOf(
            WhisperSegment(300L, 2_000L, "Eins"),
            WhisperSegment(2_400L, 4_500L, "Zwei")
        )

        val timeline = buildTranscriptTimeline(raw, 4_900L)

        assertEquals(2, timeline.size)
        assertEquals(0L, timeline.first().startMs)
        assertEquals(2_400L, timeline.first().endMs)
        assertEquals(4_900L, timeline.last().endMs)
    }

    @Test
    fun representsCompletelySilentAudioAsOnePause() {
        val timeline = buildTranscriptTimeline(emptyList(), 12_000L)

        assertEquals(listOf(WhisperSegment(0L, 12_000L, "")), timeline)
    }

    @Test
    fun keepsManualTextInAPauseWhenTheTimelineIsRestored() {
        val raw = listOf(WhisperSegment(2_000L, 4_000L, "Hallo"))
        val rebuilt = buildTranscriptTimeline(raw, 6_000L)
        val previous = rebuilt.map { segment ->
            if (segment.startMs == 0L) segment.copy(text = "Sprechername") else segment
        }

        val restored = restoreManualTimelineText(rebuilt, previous, raw)

        assertEquals("Sprechername", restored.first().text)
        assertEquals("Hallo", restored[1].text)
    }

    @Test
    fun numbersOnlyWhisperCardsInTheContinuousTimeline() {
        val raw = listOf(
            WhisperSegment(2_000L, 4_000L, "Hallo"),
            WhisperSegment(6_000L, 8_000L, "Welt")
        )
        val timeline = buildTranscriptTimeline(raw, 10_000L)

        assertEquals(listOf(null, 1, null, 2, null), transcriptNumbers(timeline, raw))
    }
}
