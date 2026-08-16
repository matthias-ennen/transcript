package de.matthiasennen.transcript.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerWatchdogTest {
    private val workerStart = 10_000L
    private val now = 500_000L

    @Test
    fun `fresh heartbeat stays healthy even when native progress is very old`() {
        val heartbeat = heartbeat(
            heartbeatAtEpochMs = now - 2_000L,
            lastProgressAtEpochMs = now - 20 * 60_000L
        )

        assertEquals(
            WorkerWatchdogState.HEALTHY,
            evaluateWorkerWatchdog(
                heartbeat = heartbeat,
                expectedWorkerStartedAtEpochMs = workerStart,
                envelopeUpdatedAtEpochMs = workerStart,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `missing heartbeat requests recovery after heartbeat timeout`() {
        val heartbeat = heartbeat(
            heartbeatAtEpochMs = now - 15_001L,
            lastProgressAtEpochMs = now - 1_000L
        )

        assertEquals(
            WorkerWatchdogState.HEARTBEAT_MISSING,
            evaluateWorkerWatchdog(
                heartbeat = heartbeat,
                expectedWorkerStartedAtEpochMs = workerStart,
                envelopeUpdatedAtEpochMs = workerStart,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `worker gets startup grace before its first matching heartbeat`() {
        assertEquals(
            WorkerWatchdogState.AWAITING_FIRST_HEARTBEAT,
            evaluateWorkerWatchdog(
                heartbeat = null,
                expectedWorkerStartedAtEpochMs = workerStart,
                envelopeUpdatedAtEpochMs = now - 179_000L,
                nowEpochMs = now
            )
        )
        assertEquals(
            WorkerWatchdogState.HEARTBEAT_MISSING,
            evaluateWorkerWatchdog(
                heartbeat = null,
                expectedWorkerStartedAtEpochMs = workerStart,
                envelopeUpdatedAtEpochMs = now - 181_000L,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `heartbeat from previous worker generation is not accepted`() {
        val oldHeartbeat = heartbeat(
            workerStartedAtEpochMs = workerStart - 1L,
            heartbeatAtEpochMs = now,
            lastProgressAtEpochMs = now
        )

        assertEquals(
            WorkerWatchdogState.HEARTBEAT_MISSING,
            evaluateWorkerWatchdog(
                heartbeat = oldHeartbeat,
                expectedWorkerStartedAtEpochMs = workerStart,
                envelopeUpdatedAtEpochMs = now - 181_000L,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `fresh inference heartbeat with old progress produces neutral running notice`() {
        val heartbeat = heartbeat(
            heartbeatAtEpochMs = now - 2_000L,
            lastProgressAtEpochMs = now - 181_000L
        )

        assertTrue(
            isLongRunningInferenceWithoutNativeProgress(
                heartbeat = heartbeat,
                expectedWorkerStartedAtEpochMs = workerStart,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `stale heartbeat never produces long running notice`() {
        val heartbeat = heartbeat(
            heartbeatAtEpochMs = now - 16_000L,
            lastProgressAtEpochMs = now - 181_000L
        )

        assertFalse(
            isLongRunningInferenceWithoutNativeProgress(
                heartbeat = heartbeat,
                expectedWorkerStartedAtEpochMs = workerStart,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `only a matching vulkan model phase may retry on cpu`() {
        val vulkanInference = heartbeat(
            heartbeatAtEpochMs = now - 16_000L,
            lastProgressAtEpochMs = now - 181_000L
        )

        assertTrue(
            shouldRetryUnresponsiveWorkerOnCpu(
                heartbeat = vulkanInference,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryUnresponsiveWorkerOnCpu(
                heartbeat = vulkanInference.copy(backend = "CPU"),
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryUnresponsiveWorkerOnCpu(
                heartbeat = vulkanInference.copy(phase = "decoding"),
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryUnresponsiveWorkerOnCpu(
                heartbeat = vulkanInference,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = true
            )
        )
    }

    @Test
    fun `fully prepared audio is resumable before the first text segment`() {
        val checkpoint = TranscriptionCheckpoint(
            request = TranscriptionRequest(
                uri = "content://video",
                fileName = "reference.mp4",
                configuration = TranscriptionJobConfiguration(
                    modelId = "large-v3",
                    language = "de",
                    whisperSettings = WhisperSettings()
                ),
                jobId = "job-large-model"
            ),
            durationMs = 180_000L,
            nextStartMs = 0L,
            detectedLanguage = null,
            startedAtEpochMs = workerStart,
            segments = emptyList()
        )

        assertTrue(
            canResumeAfterWorkerExit(
                checkpoint = checkpoint,
                preparedAudioUsable = true,
                committedSegments = emptyList()
            )
        )
        assertFalse(
            canResumeAfterWorkerExit(
                checkpoint = checkpoint,
                preparedAudioUsable = false,
                committedSegments = emptyList()
            )
        )
    }

    private fun heartbeat(
        workerStartedAtEpochMs: Long = workerStart,
        heartbeatAtEpochMs: Long,
        lastProgressAtEpochMs: Long
    ) = WorkerHeartbeat(
        jobId = "job-large-model",
        pid = 1234,
        workerStartedAtEpochMs = workerStartedAtEpochMs,
        phase = "inference",
        backend = "VULKAN",
        sectionNumber = 1,
        heartbeatAtEpochMs = heartbeatAtEpochMs,
        lastProgressAtEpochMs = lastProgressAtEpochMs
    )
}
