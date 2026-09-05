package com.whispercppdemo

import de.matthiasennen.transcript.song.NATIVE_GGUF_SAMPLES_PER_CHANNEL
import de.matthiasennen.transcript.song.SongSeparationModel
import de.matthiasennen.transcript.song.separatorWindowStarts
import de.matthiasennen.transcript.song.songSeparatorTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTranscriptionAudioTest {
    @Test
    fun shortSectionUsesOneWindow() {
        assertEquals(listOf(2_000L), separatorWindowStarts(2_000L, 8_000L))
    }

    @Test
    fun legacyModelsKeepElevenSecondWindowAndEightSecondStep() {
        val starts = separatorWindowStarts(
            startMs = 0L,
            endMs = 30_000L,
            model = SongSeparationModel.HIGH_QUALITY
        )
        val timing = songSeparatorTiming(SongSeparationModel.HIGH_QUALITY)

        assertEquals(listOf(0L, 8_000L, 16_000L, 24_000L), starts)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a == 8_000L })
        assertEquals(11_000L, timing.windowMs)
        assertEquals(8_000L, timing.stepMs)
    }

    @Test
    fun nativeGgufUsesTrainedEightSecondWindowWithQuarterOverlap() {
        val starts = separatorWindowStarts(
            startMs = 0L,
            endMs = 30_000L,
            model = SongSeparationModel.NATIVE_GGUF
        )
        val timing = songSeparatorTiming(SongSeparationModel.NATIVE_GGUF)

        assertEquals(listOf(0L, 6_000L, 12_000L, 18_000L, 24_000L), starts)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a == 6_000L })
        assertEquals(8_000L, timing.windowMs)
        assertEquals(6_000L, timing.stepMs)
        assertEquals(352_800, timing.inputFrames44100)
        assertEquals(352_800, NATIVE_GGUF_SAMPLES_PER_CHANNEL)
    }
}
