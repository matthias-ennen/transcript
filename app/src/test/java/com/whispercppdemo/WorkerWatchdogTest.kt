package de.matthiasennen.transcript.transcription

import android.app.ApplicationExitInfo
import de.matthiasennen.transcript.ui.main.WhisperSettings
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
    fun `cpu heartbeat outage is never a destructive watchdog recovery`() {
        val staleCpu = heartbeat(
            heartbeatAtEpochMs = now - 30_000L,
            lastProgressAtEpochMs = now - 181_000L
        ).copy(backend = "CPU")

        assertFalse(
            shouldProceedWithWatchdogRecovery(
                initialHeartbeat = staleCpu,
                confirmedHeartbeat = staleCpu,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `fresh heartbeat during confirmation cancels vulkan recovery`() {
        val staleVulkan = heartbeat(
            heartbeatAtEpochMs = now - 30_000L,
            lastProgressAtEpochMs = now - 181_000L
        )
        val refreshed = staleVulkan.copy(heartbeatAtEpochMs = now - 1_000L)

        assertFalse(
            shouldProceedWithWatchdogRecovery(
                initialHeartbeat = staleVulkan,
                confirmedHeartbeat = refreshed,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `confirmed stale vulkan heartbeat proceeds to one cpu recovery`() {
        val staleVulkan = heartbeat(
            heartbeatAtEpochMs = now - 30_000L,
            lastProgressAtEpochMs = now - 181_000L
        )

        assertTrue(
            shouldProceedWithWatchdogRecovery(
                initialHeartbeat = staleVulkan,
                confirmedHeartbeat = staleVulkan,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `new worker generation cancels pending watchdog recovery`() {
        val staleVulkan = heartbeat(
            heartbeatAtEpochMs = now - 30_000L,
            lastProgressAtEpochMs = now - 181_000L
        )
        val replacement = staleVulkan.copy(
            workerStartedAtEpochMs = workerStart + 1L,
            heartbeatAtEpochMs = now - 1_000L
        )

        assertFalse(
            shouldProceedWithWatchdogRecovery(
                initialHeartbeat = staleVulkan,
                confirmedHeartbeat = replacement,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `native vulkan crash retries once on cpu`() {
        val vulkanLoading = heartbeat(
            heartbeatAtEpochMs = now - 1_000L,
            lastProgressAtEpochMs = now - 1_000L
        ).copy(phase = "model_loading", backend = "VULKAN_REQUESTED")

        assertTrue(
            shouldRetryCrashedWorkerOnCpu(
                exit = WorkerExit(ApplicationExitInfo.REASON_CRASH_NATIVE, null),
                heartbeat = vulkanLoading,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryCrashedWorkerOnCpu(
                exit = WorkerExit(ApplicationExitInfo.REASON_CRASH_NATIVE, null),
                heartbeat = vulkanLoading.copy(backend = "CPU"),
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryCrashedWorkerOnCpu(
                exit = WorkerExit(ApplicationExitInfo.REASON_LOW_MEMORY, null),
                heartbeat = vulkanLoading,
                expectedJobId = "job-large-model",
                cpuRetryAlreadyUsed = false
            )
        )
        assertFalse(
            shouldRetryCrashedWorkerOnCpu(
                exit = WorkerExit(ApplicationExitInfo.REASON_CRASH_NATIVE, null),
                heartbeat = vulkanLoading,
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
