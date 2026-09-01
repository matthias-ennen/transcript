package com.whispercppdemo

import de.matthiasennen.transcript.song.separatorWindowStarts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTranscriptionAudioTest {
    @Test
    fun shortSectionUsesOneWindow() {
        assertEquals(listOf(2_000L), separatorWindowStarts(2_000L, 8_000L))
    }

    @Test
    fun longSectionAdvancesWithEightSecondOverlapStep() {
        val starts = separatorWindowStarts(0L, 30_000L)

        assertEquals(listOf(0L, 8_000L, 16_000L, 24_000L), starts)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a == 8_000L })
    }
}
