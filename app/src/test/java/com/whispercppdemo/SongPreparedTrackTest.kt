package com.whispercppdemo

import de.matthiasennen.transcript.song.preparedSongSampleCount
import de.matthiasennen.transcript.song.preparedSongSampleRange
import org.junit.Assert.assertEquals
import org.junit.Test

class SongPreparedTrackTest {
    @Test
    fun preparedTrackHasExactMillisecondTimeline() {
        val durationMs = 222_417L
        val sampleCount = preparedSongSampleCount(durationMs)

        assertEquals(3_558_672, sampleCount)
        val wholeTrack = preparedSongSampleRange(0L, durationMs, sampleCount)
        assertEquals(0, wholeTrack.startSample)
        assertEquals(sampleCount, wholeTrack.sampleCount)
    }

    @Test
    fun whisperSlicesUseAbsolutePreparedTimeline() {
        val totalSamples = preparedSongSampleCount(60_000L)
        val slice = preparedSongSampleRange(8_000L, 19_000L, totalSamples)

        assertEquals(128_000, slice.startSample)
        assertEquals(176_000, slice.sampleCount)
    }
}
