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
                val heartbeat = workerHeartbeatStore(appContext.filesDir).read()
                val cpuRetryAlreadyUsed = watchdogRecovery && checkpoint != null &&
                    cpuRetryFile(appContext).readTextOrEmpty() == checkpoint.request.jobId
                val cpuRetryAllowed = watchdogRecovery && checkpoint != null &&
                    shouldRetryUnresponsiveWorkerOnCpu(
                        heartbeat = heartbeat,
                        expectedJobId = checkpoint.request.jobId,
                        cpuRetryAlreadyUsed = cpuRetryAlreadyUsed
                    )
                if (!watchdogRecovery) {
                    cancellationFile(appContext).apply {
                        parentFile?.mkdirs()
                        writeText(checkpoint?.request?.jobId.orEmpty())
                    }
                }
                appContext.stopService(Intent(appContext, TranscriptionService::class.java))
                Thread.sleep(CANCEL_GRACE_PERIOD_MS)
                killWorkerIfStillRunning(appContext, checkpoint?.request?.jobId.orEmpty())
                if (cpuRetryAllowed && checkpoint != null) {
                    cpuRetryFile(appContext).writeText(checkpoint.request.jobId)
                    TranscriptionService.resumeCheckpoint(appContext, forceCpu = true)
                } else if (watchdogRecovery && checkpoint != null) {
                    val reason = if (cpuRetryAlreadyUsed) {
                        "Whisper blieb auch beim einmaligen CPU-Sicherheitsversuch stehen."
                    } else {
                        "Der Transkriptionsprozess hat kein Lebenszeichen mehr gesendet. " +
                            "Ein CPU-Neustart ist nur nach einem bestätigten Vulkan-Stillstand zulässig."
                    }
                    TranscriptionCoordinator.publish(
                        appContext,
                        TranscriptionState.Failed(
                            fileName = checkpoint.request.fileName,
                            message = "$reason Der Zwischenstand bleibt erhalten.",
                            canResume = true,
                            committedSegments = checkpoint.segments
                        ),
                        System.currentTimeMillis()
                    )
                } else {
                    appContext.getSystemService(NotificationManager::class.java)
                        .cancel(TRANSCRIPTION_NOTIFICATION_ID)
                    TranscriptionCoordinator.publish(
                        appContext,
                        TranscriptionState.Cancelled(checkpoint?.request?.fileName.orEmpty()),
                        System.currentTimeMillis()
                    )
                }
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
        internal fun cancellationFile(context: Context) =
            File(context.filesDir, "transcription-cancelled-job")
        internal fun cpuRetryFile(context: Context) =
            File(context.filesDir, "transcription-cpu-retry-job")
    }
}

private fun File.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")

internal const val ACTION_RECOVER_TRANSCRIPTION_ON_CPU =
    "de.matthiasennen.transcript.RECOVER_TRANSCRIPTION_ON_CPU"
