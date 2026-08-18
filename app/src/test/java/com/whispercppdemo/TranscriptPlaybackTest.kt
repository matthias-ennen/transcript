package de.matthiasennen.transcript.ui.main

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPlaybackTest {
    private val segments = listOf(
        WhisperSegment(0L, 2_000L, "Eins"),
        WhisperSegment(2_000L, 5_000L, "Zwei"),
        WhisperSegment(6_000L, 8_000L, "Drei")
    )

    @Test
    fun `active segment includes its start and reports local progress`() {
        assertEquals(ActiveTranscriptSegment(1, 0f), activeTranscriptSegment(segments, 2_000L))
        assertEquals(ActiveTranscriptSegment(1, 0.5f), activeTranscriptSegment(segments, 3_500L))
        assertNull(activeTranscriptSegment(segments, 5_500L))
        assertEquals(ActiveTranscriptSegment(2, 1f), activeTranscriptSegment(segments, 8_000L))
    }

    @Test
    fun `previous first seeks to current start then to preceding start`() {
        assertEquals(2_000L, previousTranscriptSegmentPositionMs(segments, 3_000L))
        assertEquals(0L, previousTranscriptSegmentPositionMs(segments, 2_000L))
        assertEquals(0L, previousTranscriptSegmentPositionMs(segments, 2_400L))
    }

    @Test
    fun `next seeks to next segment start`() {
        assertEquals(2_000L, nextTranscriptSegmentPositionMs(segments, 1_000L))
        assertEquals(6_000L, nextTranscriptSegmentPositionMs(segments, 2_000L))
        assertNull(nextTranscriptSegmentPositionMs(segments, 7_000L))
    }

    @Test
    fun `repeat target follows current segment and uses adjacent segments across gaps`() {
        assertEquals(0, repeatTranscriptSegmentIndex(segments, 1_000L))
        assertEquals(1, repeatTranscriptSegmentIndex(segments, 3_000L))
        assertEquals(2, repeatTranscriptSegmentIndex(segments, 5_500L))
        assertEquals(2, repeatTranscriptSegmentIndex(segments, 9_000L))
    }

    @Test
    fun `repeat jumps to target start only after its end`() {
        assertNull(repeatTranscriptSegmentStartMs(segments, 1, 4_999L))
        assertEquals(2_000L, repeatTranscriptSegmentStartMs(segments, 1, 5_000L))
        assertNull(repeatTranscriptSegmentStartMs(segments, null, 5_000L))
    }

    @Test
    fun `floating controls use measured viewport intersections`() {
        assertTrue(
            shouldShowFloatingTranscriptControls(true, 100f, 1_100f, 1_160f, 100f, 1_000f)
        )
        assertFalse(
            shouldShowFloatingTranscriptControls(true, 100f, 980f, 1_040f, 100f, 1_000f)
        )
        assertFalse(
            shouldShowFloatingTranscriptControls(true, 101f, 1_100f, 1_160f, 100f, 1_000f)
        )
    }
}
