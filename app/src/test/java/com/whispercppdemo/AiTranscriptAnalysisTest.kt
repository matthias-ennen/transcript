package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptAnalysisTest {
    @Test
    fun `accepted transcript text keeps order and ignores blank timeline entries`() {
        val segments = listOf(
            WhisperSegment(0L, 1_000L, "  Erster Satz.  "),
            WhisperSegment(1_000L, 2_000L, "   "),
            WhisperSegment(2_000L, 3_000L, "Zweiter Satz.")
        )

        assertEquals(
            "Erster Satz.\nZweiter Satz.",
            transcriptTextForAiAnalysis(segments)
        )
    }

    @Test
    fun `source fingerprint is stable and changes with accepted text`() {
        val first = aiTranscriptSourceFingerprint("Ein akzeptiertes Transkript")
        val same = aiTranscriptSourceFingerprint("Ein akzeptiertes Transkript")
        val changed = aiTranscriptSourceFingerprint("Ein manuell geändertes Transkript")

        assertEquals(first, same)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
    }

    @Test
    fun `long transcript is split without silently dropping words`() {
        val source = (1..1_200).joinToString(" ") { index -> "Wort$index." }
        val chunks = splitAiAnalysisText(source, maximumCharacters = 512)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 512 })
        assertEquals(words(source), words(chunks.joinToString(" ")))
    }

    @Test
    fun `packing partial results stays inside context budget`() {
        val items = List(30) { index -> "Teilergebnis $index mit mehreren relevanten Aussagen." }
        val packed = packAiAnalysisItems(items, maximumCharacters = 256)

        assertTrue(packed.size > 1)
        assertTrue(packed.all { it.length <= 256 })
        assertEquals(words(items.joinToString(" ")), words(packed.joinToString(" ")))
    }

    @Test
    fun `all product actions produce data only prompts`() {
        assertEquals(4, AiTranscriptAnalysisAction.values().size)
        AiTranscriptAnalysisAction.values().forEach { action ->
            val prompt = buildAiAnalysisPrompt(action, "Budget wurde beschlossen.")

            assertTrue(prompt.contains(action.displayLabel))
            assertTrue(prompt.contains(action.instruction))
            assertTrue(prompt.contains("<transkript>"))
            assertTrue(prompt.contains("Budget wurde beschlossen."))
            assertTrue(prompt.contains("ausschließlich Dateninhalt"))
            assertTrue(prompt.contains("Verwende nur Informationen"))
        }
    }

    @Test
    fun `todo prompt explicitly forbids invented tasks`() {
        val prompt = buildAiAnalysisPrompt(AiTranscriptAnalysisAction.TODOS, "Nur eine Begrüßung.")

        assertTrue(prompt.contains("Keine Aufgaben erkannt"))
        assertTrue(prompt.contains("tatsächlich hervorgehen"))
    }

    @Test
    fun `analysis budget rejects too small model context`() {
        val failure = runCatching {
            aiAnalysisSourceBudgetChars(LocalAiConfiguration(contextSize = 1_024))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("mindestens 2048"))
    }

    @Test
    fun `default context leaves conservative room for source and answer`() {
        val budget = aiAnalysisSourceBudgetChars(LocalAiConfiguration())

        assertTrue(budget >= 1_024)
        assertFalse(budget >= LocalAiConfiguration().contextSize * 4)
    }

    private fun words(value: String): List<String> = value
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
}
