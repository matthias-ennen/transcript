package de.matthiasennen.transcript

import com.whispercpp.whisper.WhisperVadSegment
import com.whispercpp.whisper.whisperVadSegmentsFromCentiseconds
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperVadTimebaseTest {
    @Test
    fun `native centiseconds are converted to milliseconds`() {
        val segments = whisperVadSegmentsFromCentiseconds(floatArrayOf(123f, 456f))

        assertEquals(listOf(WhisperVadSegment(1_230L, 4_560L)), segments)
    }

    @Test
    fun `multiple native pairs preserve their boundaries`() {
        val segments = whisperVadSegmentsFromCentiseconds(
            floatArrayOf(0f, 125f, 130f, 275f, 300f, 301f)
        )

        assertEquals(
            listOf(
                WhisperVadSegment(0L, 1_250L),
                WhisperVadSegment(1_300L, 2_750L),
                WhisperVadSegment(3_000L, 3_010L)
            ),
            segments
        )
    }

    @Test
    fun `invalid native pairs are not exposed as speech`() {
        val segments = whisperVadSegmentsFromCentiseconds(
            floatArrayOf(-1f, 10f, 20f, 20f, Float.NaN, 50f, 60f, 80f)
        )

        assertEquals(listOf(WhisperVadSegment(600L, 800L)), segments)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `incomplete native pair is rejected`() {
        whisperVadSegmentsFromCentiseconds(floatArrayOf(10f))
    }
}
