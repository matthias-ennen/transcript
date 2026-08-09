package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ai.IndexedTranscriptSegment
import de.matthiasennen.transcript.ai.applyCorrections
import de.matthiasennen.transcript.ai.buildCorrectionPrompt
import de.matthiasennen.transcript.ai.parseCorrectedSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptCorrectionTest {
    private val source = listOf(
        WhisperSegment(0L, 1_000L, "hallo wie gehts"),
        WhisperSegment(1_000L, 2_000L, "mir geht es gut")
    )

    @Test
    fun promptCarriesStableMarkers() {
        val prompt = buildCorrectionPrompt(
            source.mapIndexed { index, segment -> IndexedTranscriptSegment(index, segment) }
        )
        assertTrue(prompt.contains("[[SEGMENT_0001]] hallo wie gehts"))
        assertTrue(prompt.contains("[[SEGMENT_0002]] mir geht es gut"))
    }

    @Test
    fun promptUsesNeighborsAsReadOnlyContext() {
        val prompt = buildCorrectionPrompt(
            segments = listOf(IndexedTranscriptSegment(1, source[1])),
            contextBefore = listOf(IndexedTranscriptSegment(0, source[0])),
            contextAfter = listOf(
                IndexedTranscriptSegment(2, WhisperSegment(2_000L, 3_000L, "bis später"))
            )
        )
        assertTrue(prompt.contains("[[VORHER_0001]] hallo wie gehts"))
        assertTrue(prompt.contains("[[SEGMENT_0002]] mir geht es gut"))
        assertTrue(prompt.contains("[[NACHHER_0003]] bis später"))
        assertTrue(prompt.contains("Bei Unsicherheit bleibt der Originaltext exakt stehen"))
    }

    @Test
    fun parserMapsOnlyExpectedMarkers() {
        val parsed = parseCorrectedSegments(
            "[[SEGMENT_0001]] Hallo, wie geht's?\n[[SEGMENT_0002]] Mir geht es gut.",
            listOf(1, 2)
        )
        assertEquals("Hallo, wie geht's?", parsed[0])
        assertEquals("Mir geht es gut.", parsed[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsMissingSegments() {
        parseCorrectedSegments("[[SEGMENT_0001]] Hallo.", listOf(1, 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsLeakedContext() {
        parseCorrectedSegments(
            "[[SEGMENT_0001]] Hallo.\n[[VORHER_0000]] Fremder Kontext",
            listOf(1)
        )
    }

    @Test
    fun correctionsPreserveTimestampsAndUntouchedSegments() {
        val updated = applyCorrections(source, mapOf(1 to "Mir geht es sehr gut."))
        assertEquals(source[0], updated[0])
        assertEquals(1_000L, updated[1].startMs)
        assertEquals(2_000L, updated[1].endMs)
        assertEquals("Mir geht es sehr gut.", updated[1].text)
    }
}
