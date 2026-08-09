package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ai.IndexedTranscriptSegment
import de.matthiasennen.transcript.ai.applyCorrections
import de.matthiasennen.transcript.ai.buildCorrectionPrompt
import de.matthiasennen.transcript.ai.maximumCorrectionTokens
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
        assertTrue(prompt.contains("#1 hallo wie gehts"))
        assertTrue(prompt.contains("#2 mir geht es gut"))
        assertTrue(prompt.contains("[[PATCH_0001]]<TAB>"))
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
        assertTrue(prompt.contains("#1 hallo wie gehts"))
        assertTrue(prompt.contains("#2 mir geht es gut"))
        assertTrue(prompt.contains("#3 bis später"))
        assertTrue(prompt.contains("Bei Unsicherheit nichts ändern"))
    }

    @Test
    fun parserMapsOnlyExpectedMarkers() {
        val parsed = parseCorrectedSegments(
            "[[PATCH_0001]]\tHallo, wie geht's?\n[[PATCH_0002]]\tMir geht es gut.",
            listOf(1, 2)
        )
        assertEquals("Hallo, wie geht's?", parsed.corrections[0])
        assertEquals("Mir geht es gut.", parsed.corrections[1])
    }

    @Test
    fun parserAcceptsOnlyActualChanges() {
        val parsed = parseCorrectedSegments("[[PATCH_0001]]\tHallo.", listOf(1, 2))
        assertEquals(1, parsed.corrections.size)
    }

    @Test
    fun parserKeepsValidChangesWhenOneLineIsInvalid() {
        val parsed = parseCorrectedSegments("[[PATCH_0001]]\tHallo.\n[[PATCH_0009]]\tFremd", listOf(1))
        assertEquals("Hallo.", parsed.corrections[0])
        assertEquals(1, parsed.rejectedEntries)
    }

    @Test
    fun correctionsPreserveTimestampsAndUntouchedSegments() {
        val updated = applyCorrections(source, mapOf(1 to "Mir geht es sehr gut."))
        assertEquals(source[0], updated[0])
        assertEquals(1_000L, updated[1].startMs)
        assertEquals(2_000L, updated[1].endMs)
        assertEquals("Mir geht es sehr gut.", updated[1].text)
    }

    @Test
    fun outputBudgetIncludesSegmentMarkerOverhead() {
        val manyShortSegments = List(100) { index ->
            IndexedTranscriptSegment(
                index,
                WhisperSegment(index * 1_000L, (index + 1) * 1_000L, "kurzer Text")
            )
        }

        assertTrue(maximumCorrectionTokens(manyShortSegments) <= 512)
    }
}
