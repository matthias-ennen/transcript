package de.matthiasennen.transcript.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollToTopVisibilityTest {
    @Test
    fun `short transcripts never show the scroll shortcut`() {
        assertFalse(
            shouldShowScrollToTop(
                segmentCount = 19,
                scrollOffsetPx = 2_000,
                thresholdPx = 720
            )
        )
    }

    @Test
    fun `long transcripts show the shortcut after the threshold`() {
        assertTrue(
            shouldShowScrollToTop(
                segmentCount = 20,
                scrollOffsetPx = 720,
                thresholdPx = 720
            )
        )
    }

    @Test
    fun `shortcut stays hidden near the top`() {
        assertFalse(
            shouldShowScrollToTop(
                segmentCount = 100,
                scrollOffsetPx = 719,
                thresholdPx = 720
            )
        )
    }
}
