package de.matthiasennen.transcript.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceStorageSnapshotTest {
    @Test
    fun `normalizes storage values and calculates the occupied share`() {
        val snapshot = normalizedStorageSnapshot(
            totalBytes = 1_000L,
            freeBytes = 250L
        )

        assertEquals(750L, snapshot.usedBytes)
        assertEquals(0.75f, snapshot.usedFraction, 0.0001f)
    }

    @Test
    fun `invalid storage values remain safe for rendering`() {
        val excessiveFree = normalizedStorageSnapshot(
            totalBytes = 1_000L,
            freeBytes = 2_000L
        )
        val unavailable = normalizedStorageSnapshot(
            totalBytes = -1L,
            freeBytes = -1L
        )

        assertEquals(1_000L, excessiveFree.freeBytes)
        assertEquals(0L, excessiveFree.usedBytes)
        assertEquals(0f, unavailable.usedFraction, 0f)
    }
}
