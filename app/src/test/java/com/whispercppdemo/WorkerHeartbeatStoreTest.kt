package de.matthiasennen.transcript.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkerHeartbeatStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `heartbeat roundtrip preserves worker identity phase and progress time`() {
        val file = temporaryFolder.newFile("heartbeat.bin")
        file.delete()
        val store = WorkerHeartbeatStore(file)
        val heartbeat = WorkerHeartbeat(
            jobId = "job-42",
            pid = 1234,
            workerStartedAtEpochMs = 10_000L,
            phase = "inference",
            backend = "VULKAN",
            sectionNumber = 3,
            heartbeatAtEpochMs = 20_000L,
            lastProgressAtEpochMs = 19_000L
        )

        store.write(heartbeat)

        assertEquals(heartbeat, store.read())
        store.clear()
        assertNull(store.read())
    }
}
