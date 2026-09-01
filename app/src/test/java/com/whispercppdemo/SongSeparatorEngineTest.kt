package com.whispercppdemo

import de.matthiasennen.transcript.song.SpleeterTensorLayout
import de.matthiasennen.transcript.song.spleeterTensorIndex
import de.matthiasennen.transcript.song.spleeterTensorPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SongSeparatorEngineTest {
    @Test
    fun dynamicChannelsFirstUsesRequiredSplitCount() {
        val plan = spleeterTensorPlan(longArrayOf(2, -1, 512, 1024), requiredSplits = 1)

        assertEquals(SpleeterTensorLayout.CHANNELS_FIRST, plan.layout)
        assertEquals(1, plan.splitCount)
        assertArrayEquals(longArrayOf(2, 1, 512, 1024), plan.shape)
    }

    @Test
    fun fixedChannelsFirstPadsShortWindowToModelSplitCount() {
        val plan = spleeterTensorPlan(longArrayOf(2, 2, 512, 1024), requiredSplits = 1)

        assertEquals(SpleeterTensorLayout.CHANNELS_FIRST, plan.layout)
        assertEquals(2, plan.splitCount)
        assertArrayEquals(longArrayOf(2, 2, 512, 1024), plan.shape)
    }

    @Test
    fun splitsFirstModelUsesRuntimeLayout() {
        val plan = spleeterTensorPlan(longArrayOf(-1, 2, 512, 1024), requiredSplits = 1)

        assertEquals(SpleeterTensorLayout.SPLITS_FIRST, plan.layout)
        assertEquals(1, plan.splitCount)
        assertArrayEquals(longArrayOf(1, 2, 512, 1024), plan.shape)
    }

    @Test
    fun tensorIndexFollowsSelectedLayout() {
        val channelsFirst = spleeterTensorIndex(
            layout = SpleeterTensorLayout.CHANNELS_FIRST,
            splitCount = 2,
            channel = 1,
            frame = 513,
            bin = 7
        )
        val splitsFirst = spleeterTensorIndex(
            layout = SpleeterTensorLayout.SPLITS_FIRST,
            splitCount = 2,
            channel = 1,
            frame = 513,
            bin = 7
        )

        assertEquals((((1 * 2 + 1) * 512 + 1) * 1024) + 7, channelsFirst)
        assertEquals((((1 * 2 + 1) * 512 + 1) * 1024) + 7, splitsFirst)
    }
}
