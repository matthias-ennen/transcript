package com.whispercppdemo

import de.matthiasennen.transcript.song.SongSeparationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSeparationModelTest {
    @Test
    fun catalogKeepsApprovedThreeTierOrder() {
        assertEquals(
            listOf(
                SongSeparationModel.QUICK,
                SongSeparationModel.BALANCED,
                SongSeparationModel.HIGH_QUALITY
            ),
            SongSeparationModel.entries
        )
    }

    @Test
    fun balancedIsTheOnlyRecommendedTier() {
        assertFalse(SongSeparationModel.QUICK.recommended)
        assertTrue(SongSeparationModel.BALANCED.recommended)
        assertFalse(SongSeparationModel.HIGH_QUALITY.recommended)
    }

    @Test
    fun unknownPersistedIdFallsBackToBalanced() {
        assertEquals(
            SongSeparationModel.BALANCED,
            SongSeparationModel.fromId("unknown")
        )
    }
}
