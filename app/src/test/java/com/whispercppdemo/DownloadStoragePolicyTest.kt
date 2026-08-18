package de.matthiasennen.transcript

import de.matthiasennen.transcript.download.DownloadStoragePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoragePolicyTest {
    @Test
    fun `requires remaining model bytes plus temporary reserve`() {
        val requirement = DownloadStoragePolicy.requirement(
            modelLabel = "Testmodell",
            modelBytes = 1_000L,
            partialBytes = 400L,
            availableBytes = 200_000_000L
        )

        assertEquals(400L, requirement.partialBytes)
        assertEquals(600L + 8L * 1024L * 1024L + 128L * 1024L * 1024L, requirement.requiredFreeBytes)
        assertTrue(requirement.hasEnoughSpace)
    }

    @Test
    fun `rejects a new download before a partial file is created`() {
        val requirement = DownloadStoragePolicy.requirement(
            modelLabel = "Großes Modell",
            modelBytes = 1_000_000_000L,
            partialBytes = 0L,
            availableBytes = 100_000_000L
        )

        assertFalse(requirement.hasEnoughSpace)
    }
}
