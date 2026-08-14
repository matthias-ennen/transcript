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
                val cpuRetryAlreadyUsed = watchdogRecovery && checkpoint != null &&
                    cpuRetryFile(appContext).readTextOrEmpty() == checkpoint.request.jobId
                if (!watchdogRecovery) {
                    cancellationFile(appContext).apply {
                        parentFile?.mkdirs()
                        writeText(checkpoint?.request?.jobId.orEmpty())
                    }
                }
                appContext.stopService(Intent(appContext, TranscriptionService::class.java))
                Thread.sleep(CANCEL_GRACE_PERIOD_MS)
                killWorkerIfStillRunning(appContext)
                if (watchdogRecovery && checkpoint != null && !cpuRetryAlreadyUsed) {
                    cpuRetryFile(appContext).writeText(checkpoint.request.jobId)
                    TranscriptionService.resumeCheckpoint(appContext, forceCpu = true)
                } else if (watchdogRecovery && checkpoint != null) {
                    TranscriptionCoordinator.publish(
                        appContext,
                        TranscriptionState.Failed(
                            fileName = checkpoint.request.fileName,
                            message = "Whisper blieb auch beim einmaligen CPU-Sicherheitsversuch stehen. " +
                                "Der Zwischenstand bleibt erhalten.",
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

    private fun killWorkerIfStillRunning(context: Context) {
        val workerName = "${context.packageName}:transcription"
        context.getSystemService(ActivityManager::class.java).runningAppProcesses
            .orEmpty()
            .filter { it.processName == workerName && it.uid == Process.myUid() }
            .forEach { Process.killProcess(it.pid) }
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
