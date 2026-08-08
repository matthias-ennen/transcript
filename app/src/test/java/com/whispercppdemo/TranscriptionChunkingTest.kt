package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionChunkingTest {
    private val longDurationMs = (69 * 60L + 5L) * 1_000L

    @Test
    fun `69 minute recording is planned in bounded five minute sections`() {
        val sections = planTranscriptionSections(longDurationMs)

        assertEquals(14, sections.size)
        assertEquals(0L, sections.first().mainStartMs)
        assertEquals(300_000L, sections.first().mainEndMs)
        assertEquals(302_000L, sections.first().decodeEndMs)
        assertEquals(298_000L, sections[1].decodeStartMs)
        assertEquals(longDurationMs, sections.last().mainEndMs)
        assertTrue(sections.all { it.decodeEndMs - it.decodeStartMs <= 304_000L })
    }

    @Test
    fun `failed standard section is replaced by two fallback sections`() {
        val standard = planTranscriptionSections(longDurationMs).first()

        val fallback = splitIntoFallbackSections(standard, longDurationMs)

        assertEquals(2, fallback.size)
        assertEquals(0L, fallback[0].mainStartMs)
        assertEquals(150_000L, fallback[0].mainEndMs)
        assertEquals(150_000L, fallback[1].mainStartMs)
        assertEquals(300_000L, fallback[1].mainEndMs)
        assertTrue(fallback.all(TranscriptionSection::usedFallbackSize))
        assertTrue(fallback.all { it.decodeEndMs - it.decodeStartMs <= 154_000L })
    }

    @Test
    fun `overlap midpoint assigns boundary segment exactly once`() {
        val firstTwo = planTranscriptionSections(longDurationMs).take(2)
        val firstChunkResult = WhisperSegment(299_000L, 301_000L, "Grenzsatz")
        val secondChunkResult = WhisperSegment(1_000L, 3_000L, "Grenzsatz")

        val fromFirst = selectAbsoluteSegments(
            listOf(firstChunkResult),
            firstTwo[0],
            longDurationMs
        )
        val fromSecond = selectAbsoluteSegments(
            listOf(secondChunkResult),
            firstTwo[1],
            longDurationMs
        )

        assertTrue(fromFirst.isEmpty())
        assertEquals(listOf(WhisperSegment(299_000L, 301_000L, "Grenzsatz")), fromSecond)
    }

    @Test
    fun `timestamps beyond one hour are shifted to absolute recording time`() {
        val section = planTranscriptionSections(longDurationMs)[12]

        val absolute = selectAbsoluteSegments(
            localSegments = listOf(WhisperSegment(244_000L, 246_000L, "Später Satz")),
            section = section,
            totalDurationMs = longDurationMs
        )

        assertEquals(3_842_000L, absolute.single().startMs)
        assertEquals(3_844_000L, absolute.single().endMs)
        assertFalse(absolute.single().text.isBlank())
    }
}
