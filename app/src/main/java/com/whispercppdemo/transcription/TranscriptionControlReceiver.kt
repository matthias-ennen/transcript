package de.matthiasennen.transcript.transcription

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File

/**
 * Runs outside the native worker. A stuck Whisper call therefore cannot block
 * the user's cancel request or accidentally recreate an already dead worker.
 */
class TranscriptionControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val watchdogRecovery = intent.action == ACTION_RECOVER_TRANSCRIPTION_ON_CPU
        if (!watchdogRecovery && intent.action != ACTION_CANCEL_TRANSCRIPTION) return
        val pending = goAsync()
        Thread {
            try {
                val appContext = context.applicationContext
                val checkpoint = TranscriptionCheckpointStore(
                    File(appContext.filesDir, "active-transcription.bin")
                ).read()
                val heartbeatStore = workerHeartbeatStore(appContext.filesDir)
                val initialHeartbeat = heartbeatStore.read()
                val cpuRetryAlreadyUsed = watchdogRecovery && checkpoint != null &&
                    cpuRetryFile(appContext).readTextOrEmpty() == checkpoint.request.jobId

                if (watchdogRecovery) {
                    val savedCheckpoint = checkpoint ?: return@Thread
                    // A missing heartbeat alone is not proof that a CPU/unknown worker is dead.
                    // Large native Whisper calls can temporarily starve the Java heartbeat writer.
                    // Automatic hard-stop is therefore restricted to a worker that was positively
                    // identified as Vulkan/GPU and has a safe one-time CPU recovery path.
                    if (!shouldRetryUnresponsiveWorkerOnCpu(
                            heartbeat = initialHeartbeat,
                            expectedJobId = savedCheckpoint.request.jobId,
                            cpuRetryAlreadyUsed = cpuRetryAlreadyUsed
                        )
                    ) {
                        return@Thread
                    }

                    // Re-read after a short grace period. This closes the race where the UI process
                    // observes a stale heartbeat just before the worker manages to publish a fresh one.
                    Thread.sleep(WATCHDOG_CONFIRMATION_GRACE_MS)
                    val confirmedHeartbeat = heartbeatStore.read()
                    if (!shouldProceedWithWatchdogRecovery(
                            initialHeartbeat = initialHeartbeat,
                            confirmedHeartbeat = confirmedHeartbeat,
                            expectedJobId = savedCheckpoint.request.jobId,
                            cpuRetryAlreadyUsed = cpuRetryAlreadyUsed,
                            nowEpochMs = System.currentTimeMillis()
                        )
                    ) {
                        return@Thread
                    }

                    appContext.stopService(Intent(appContext, TranscriptionService::class.java))
                    Thread.sleep(CANCEL_GRACE_PERIOD_MS)
                    killWorkerIfStillRunning(appContext, savedCheckpoint.request.jobId)
                    cpuRetryFile(appContext).writeText(savedCheckpoint.request.jobId)
                    TranscriptionService.resumeCheckpoint(appContext, forceCpu = true)
                    return@Thread
                }

                cancellationFile(appContext).apply {
                    parentFile?.mkdirs()
                    writeText(checkpoint?.request?.jobId.orEmpty())
                }
                appContext.stopService(Intent(appContext, TranscriptionService::class.java))
                Thread.sleep(CANCEL_GRACE_PERIOD_MS)
                killWorkerIfStillRunning(appContext, checkpoint?.request?.jobId.orEmpty())
                appContext.getSystemService(NotificationManager::class.java)
                    .cancel(TRANSCRIPTION_NOTIFICATION_ID)
                TranscriptionCoordinator.publish(
                    appContext,
                    TranscriptionState.Cancelled(checkpoint?.request?.fileName.orEmpty()),
                    System.currentTimeMillis()
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun killWorkerIfStillRunning(context: Context, jobId: String) {
        val workerName = "${context.packageName}:transcription"
        val heartbeat = workerHeartbeatStore(context.filesDir).read()
            ?.takeIf { it.jobId == jobId && it.pid > 0 }
            ?: return
        val manager = context.getSystemService(ActivityManager::class.java)
        val verified = manager.runningAppProcesses.orEmpty().any {
            it.pid == heartbeat.pid && it.processName == workerName && it.uid == Process.myUid()
        }
        if (!verified) return
        Process.killProcess(heartbeat.pid)
        repeat(4) {
            Thread.sleep(250L)
            val stillRunning = manager.runningAppProcesses.orEmpty().any { it.pid == heartbeat.pid }
            if (!stillRunning) return
        }
    }

    companion object {
        private const val CANCEL_GRACE_PERIOD_MS = 4_000L
        private const val WATCHDOG_CONFIRMATION_GRACE_MS = 3_000L
        internal fun cancellationFile(context: Context) =
            File(context.filesDir, "transcription-cancelled-job")
        internal fun cpuRetryFile(context: Context) =
            File(context.filesDir, "transcription-cpu-retry-job")
    }
}

/**
 * A watchdog recovery is destructive, so the stale observation is confirmed immediately
 * before stopping the isolated worker. A refreshed heartbeat or a new worker generation wins.
 */
internal fun shouldProceedWithWatchdogRecovery(
    initialHeartbeat: WorkerHeartbeat?,
    confirmedHeartbeat: WorkerHeartbeat?,
    expectedJobId: String,
    cpuRetryAlreadyUsed: Boolean,
    nowEpochMs: Long
): Boolean {
    val initial = initialHeartbeat ?: return false
    if (!shouldRetryUnresponsiveWorkerOnCpu(initial, expectedJobId, cpuRetryAlreadyUsed)) return false
    val confirmed = confirmedHeartbeat ?: initial
    if (confirmed.jobId != expectedJobId) return false
    if (confirmed.workerStartedAtEpochMs != initial.workerStartedAtEpochMs) return false
    if (!shouldRetryUnresponsiveWorkerOnCpu(confirmed, expectedJobId, cpuRetryAlreadyUsed)) return false
    return evaluateWorkerWatchdog(
        heartbeat = confirmed,
        expectedWorkerStartedAtEpochMs = initial.workerStartedAtEpochMs,
        envelopeUpdatedAtEpochMs = 0L,
        nowEpochMs = nowEpochMs
    ) == WorkerWatchdogState.HEARTBEAT_MISSING
}

private fun File.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")

internal const val ACTION_RECOVER_TRANSCRIPTION_ON_CPU =
    "de.matthiasennen.transcript.RECOVER_TRANSCRIPTION_ON_CPU"
