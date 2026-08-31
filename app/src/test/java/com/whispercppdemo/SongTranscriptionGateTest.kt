package com.whispercppdemo

import de.matthiasennen.transcript.song.TranscriptionMode
import de.matthiasennen.transcript.song.canStartTranscriptionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTranscriptionGateTest {
    @Test
    fun speechModeNeverRequiresSongModel() {
        assertTrue(canStartTranscriptionMode(TranscriptionMode.SPEECH, false))
    }

    @Test
    fun songModeRequiresSelectedSeparator() {
        assertFalse(canStartTranscriptionMode(TranscriptionMode.SONG, false))
        assertTrue(canStartTranscriptionMode(TranscriptionMode.SONG, true))
    }
}
