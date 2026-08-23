package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ai.IndexedTranscriptSegment
import de.matthiasennen.transcript.ai.applyCorrection
import de.matthiasennen.transcript.ai.buildCorrectionContext
import de.matthiasennen.transcript.ai.buildCorrectionTarget
import de.matthiasennen.transcript.ai.maximumCorrectionTokens
import de.matthiasennen.transcript.ai.parseCorrectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptCorrectionTest {
    private val source = listOf(
        WhisperSegment(0L, 1_000L, "hallo wie gehts"),
        WhisperSegment(1_000L, 2_000L, "mir geht es gut")
    )

    @Test
    fun contextCarriesCompleteVisibleGroupAsJson() {
        val prompt = buildCorrectionContext(
            source.mapIndexed { index, segment -> IndexedTranscriptSegment(index, segment) }
        )

        assertTrue(prompt.contains("transcript-correction-v2"))
        assertTrue(prompt.contains("\"source\":\"accepted_transcript\""))
        assertTrue(prompt.contains("\"id\":1,\"start_ms\":0,\"end_ms\":1000,\"text\":\"hallo wie gehts\""))
        assertTrue(prompt.contains("\"id\":2"))
        assertTrue(prompt.contains("genau ein Zielsegment"))
        assertFalse(prompt.contains("/no_think"))
    }

    @Test
    fun targetContainsOnlyCurrentAcceptedSegmentAndSimpleInstruction() {
        val target = buildCorrectionTarget(IndexedTranscriptSegment(1, source[1]))

        assertTrue(target.contains("\"target_id\":2"))
        assertTrue(target.contains("\"accepted_text\":\"mir geht es gut\""))
        assertFalse(target.contains("hallo wie gehts"))
        assertFalse(target.contains("/no_think"))
    }

    @Test
    fun parserAcceptsAnyNonEmptyResultWithoutContentRestrictions() {
        val parsed = parseCorrectionResult(
            "{\"result\":\"Eine deutlich längere, frei formulierte Antwort.\"}",
            source[0].text
        )

        assertEquals("Eine deutlich längere, frei formulierte Antwort.", parsed.text)
        assertTrue(parsed.changed)
        assertFalse(parsed.retainedOriginal)
    }

    @Test
    fun parserKeepsOriginalOnlyForEmptyResult() {
        val parsed = parseCorrectionResult("{\"result\":\"\"}", source[0].text)

        assertEquals(source[0].text, parsed.text)
        assertFalse(parsed.changed)
        assertTrue(parsed.retainedOriginal)
    }

    @Test
    fun parserKeepsOriginalWhenEnvelopeCannotBeRead() {
        val parsed = parseCorrectionResult("keine strukturierte Antwort", source[0].text)

        assertEquals(source[0].text, parsed.text)
        assertTrue(parsed.retainedOriginal)
    }

    @Test
    fun parserDecodesJsonEscapes() {
        val parsed = parseCorrectionResult(
            "{\"result\":\"Er sagte: \\\"Hallo\\\".\\nDann ging er.\"}",
            source[0].text
        )

        assertEquals("Er sagte: \"Hallo\".\nDann ging er.", parsed.text)
    }

    @Test
    fun correctionPreservesTimestampsAndUntouchedSegments() {
        val updated = applyCorrection(source, 1, "Mir geht es sehr gut.")

        assertEquals(source[0], updated[0])
        assertEquals(1_000L, updated[1].startMs)
        assertEquals(2_000L, updated[1].endMs)
        assertEquals("Mir geht es sehr gut.", updated[1].text)
    }

    @Test
    fun outputBudgetRemainsSmallForShortTargetSegments() {
        assertEquals(64, maximumCorrectionTokens("kurzer Text"))
        assertTrue(maximumCorrectionTokens("x".repeat(2_000)) <= 256)
    }
}
