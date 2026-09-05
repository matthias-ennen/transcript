package com.whispercppdemo

import de.matthiasennen.transcript.song.SongSeparationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSeparationModelTest {
    @Test
    fun catalogKeepsApprovedFourModelOrder() {
        assertEquals(
            listOf(
                SongSeparationModel.QUICK,
                SongSeparationModel.BALANCED,
                SongSeparationModel.NATIVE_GGUF,
                SongSeparationModel.HIGH_QUALITY
            ),
            SongSeparationModel.entries
        )
    }

    @Test
    fun balancedIsTheOnlyRecommendedTier() {
        assertFalse(SongSeparationModel.QUICK.recommended)
        assertTrue(SongSeparationModel.BALANCED.recommended)
        assertFalse(SongSeparationModel.NATIVE_GGUF.recommended)
        assertFalse(SongSeparationModel.HIGH_QUALITY.recommended)
    }

    @Test
    fun nativeGgufUsesDedicatedModelIdAndArtifact() {
        val model = SongSeparationModel.NATIVE_GGUF
        assertEquals("kim-vocal-2-native", model.id)
        assertEquals("mel-band-roformer-vocals-f16.gguf", model.artifacts.single().fileName)
        assertFalse(model.artifacts.single().exactBytes)
        assertTrue(model.artifacts.single().minimumBytes >= 400_000_000L)
    }

    @Test
    fun unknownPersistedIdFallsBackToBalanced() {
        assertEquals(
            SongSeparationModel.BALANCED,
            SongSeparationModel.fromId("unknown")
        )
    }
}
