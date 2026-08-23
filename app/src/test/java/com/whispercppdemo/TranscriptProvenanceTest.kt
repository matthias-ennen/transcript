package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptProvenanceTest {
    private val raw = listOf(
        WhisperSegment(0L, 1_000L, "Alpha"),
        WhisperSegment(1_000L, 2_000L, "Beta")
    )

    @Test
    fun `manual ai and return to original follow accepted text`() {
        val originalOrigins = defaultTranscriptOrigins(raw, raw)
        val manualSegments = raw.withUpdatedTranscriptText(0, "Alpha korrigiert")
        val manualOrigins = updateTranscriptOrigins(
            previousSegments = raw,
            updatedSegments = manualSegments,
            rawWhisperSegments = raw,
            existingOrigins = originalOrigins,
            changedOrigin = TranscriptSegmentOrigin.MANUAL
        )
        assertEquals(TranscriptSegmentOrigin.MANUAL, acceptedTranscriptOrigin(0, manualSegments[0], raw, manualOrigins))
        assertEquals(TranscriptSegmentOrigin.ORIGINAL, acceptedTranscriptOrigin(1, manualSegments[1], raw, manualOrigins))

        val aiSegments = manualSegments.withUpdatedTranscriptText(1, "Beta KI")
        val aiOrigins = updateTranscriptOrigins(
            previousSegments = manualSegments,
            updatedSegments = aiSegments,
            rawWhisperSegments = raw,
            existingOrigins = manualOrigins,
            changedOrigin = TranscriptSegmentOrigin.AI
        )
        assertEquals(TranscriptSegmentOrigin.MANUAL, acceptedTranscriptOrigin(0, aiSegments[0], raw, aiOrigins))
        assertEquals(TranscriptSegmentOrigin.AI, acceptedTranscriptOrigin(1, aiSegments[1], raw, aiOrigins))

        val restoredOriginal = aiSegments.withUpdatedTranscriptText(0, "Alpha")
        val restoredOrigins = updateTranscriptOrigins(
            previousSegments = aiSegments,
            updatedSegments = restoredOriginal,
            rawWhisperSegments = raw,
            existingOrigins = aiOrigins,
            changedOrigin = TranscriptSegmentOrigin.MANUAL
        )
        assertEquals(TranscriptSegmentOrigin.ORIGINAL, acceptedTranscriptOrigin(0, restoredOriginal[0], raw, restoredOrigins))
    }

    @Test
    fun `global original view never changes accepted edited text`() {
        val edited = raw.withUpdatedTranscriptText(0, "Bearbeitet")
        val state = TranscriptUiState(
            rawWhisperSegments = raw,
            segments = edited,
            transcriptView = TranscriptViewMode.ORIGINAL
        )

        assertEquals("Alpha", state.transcriptSegmentsForSelectedView()[0].text)
        assertEquals("Bearbeitet", state.segments[0].text)
        assertEquals("Alpha", state.exportSegmentsForSelectedView()[0].text)
    }

    @Test
    fun `live whisper segments without raw source are treated as original`() {
        val segment = WhisperSegment(0L, 1_000L, "Live")
        assertEquals(
            TranscriptSegmentOrigin.ORIGINAL,
            acceptedTranscriptOrigin(0, segment, emptyList(), emptyMap())
        )
    }
}
