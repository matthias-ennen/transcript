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
    fun `whisper timestamps are clamped to the decoded overlap window`() {
        val first = planTranscriptionSections(
            durationMs = 300_000L,
            sectionDurationMs = 120_000L
        ).first()

        val absolute = selectAbsoluteSegments(
            localSegments = listOf(
                WhisperSegment(116_000L, 123_000L, "Grenzsatz")
            ),
            section = first,
            totalDurationMs = 300_000L
        )

        assertEquals(
            listOf(WhisperSegment(116_000L, 122_000L, "Grenzsatz")),
            absolute
        )
    }

    @Test
    fun `different overlap segmentation at two minute seam is stitched once`() {
        val sections = planTranscriptionSections(
            durationMs = 300_000L,
            sectionDurationMs = 120_000L
        )
        val first = selectAbsoluteSegments(
            localSegments = listOf(
                WhisperSegment(116_000L, 123_000L, "Vorheriger Grenzsatz")
            ),
            section = sections[0],
            totalDurationMs = 300_000L
        )
        val second = selectAbsoluteSegments(
            localSegments = listOf(
                WhisperSegment(0L, 4_120L, "Alternative Grenzsegmentierung"),
                WhisperSegment(4_120L, 6_120L, "Danach")
            ),
            section = sections[1],
            totalDurationMs = 300_000L
        )

        val stitched = mergeCommittedSegments(first, second)

        assertEquals(
            listOf(
                WhisperSegment(116_000L, 122_000L, "Vorheriger Grenzsatz"),
                WhisperSegment(122_120L, 124_120L, "Danach")
            ),
            stitched
        )
        assertTrue(stitched.zipWithNext().all { (left, right) -> left.endMs <= right.startMs })
    }

    @Test
    fun `partial cross chunk overlap is split without dropping distinct text`() {
        val committed = listOf(
            WhisperSegment(118_000L, 121_000L, "Linker Satz")
        )
        val incoming = listOf(
            WhisperSegment(119_000L, 124_000L, "Rechter Satz")
        )

        val stitched = mergeCommittedSegments(committed, incoming)

        assertEquals(2, stitched.size)
        assertEquals("Linker Satz", stitched[0].text)
        assertEquals("Rechter Satz", stitched[1].text)
        assertEquals(stitched[0].endMs, stitched[1].startMs)
        assertEquals(120_000L, stitched[0].endMs)
    }

    @Test
    fun `real repeated text is preserved when time ranges do not overlap`() {
        val stitched = mergeCommittedSegments(
            committed = listOf(WhisperSegment(117_000L, 119_000L, "Noch einmal")),
            next = listOf(WhisperSegment(121_000L, 123_000L, "Noch einmal"))
        )

        assertEquals(2, stitched.size)
        assertEquals(listOf("Noch einmal", "Noch einmal"), stitched.map { it.text })
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
