package de.matthiasennen.transcript.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

private const val VAD_CHANNEL_ID = "vad_model_downloads"

class VadModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_REDELIVER_INTENT
        val partial = partialFile()
        startForeground(
            TranscriptNotifications.VAD_MODEL_DOWNLOAD_ID,
            notification("Download wird vorbereitet …", partial.takeIf(File::isFile)?.length() ?: 0L, 0L, true)
        )
        job = scope.launch {
            runCatching(::downloadAndVerify).onSuccess {
                VadModelDownloadCoordinator.update(VadModelDownloadState.Completed)
                finish("Download abgeschlossen", "${SileroVadModel.modelLabel} ist installiert.")
            }.onFailure { failure ->
                if (failure is InsufficientDownloadStorageException) DownloadStorageIssueCoordinator.show(failure.requirement)
                val bytes = partialFile().takeIf(File::isFile)?.length() ?: 0L
                val message = failure.localizedMessage ?: "Der VAD-Modelldownload ist fehlgeschlagen."
                VadModelDownloadCoordinator.update(VadModelDownloadState.Failed(message, bytes, SileroVadModel.expectedBytes))
                finish("Download unterbrochen", message)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun downloadAndVerify() {
        val directory = File(filesDir, "vad-models").apply { mkdirs() }
        VerifiedModelDownloader.downloadAndVerify(
            download = VerifiedModelDownload(
                modelLabel = SileroVadModel.modelLabel,
                downloadUrl = SileroVadModel.downloadUrl,
                expectedBytes = SileroVadModel.expectedBytes,
                sha256 = SileroVadModel.sha256,
                destination = File(directory, SileroVadModel.fileName),
                partial = partialFile(),
                failureLabel = "VAD-Modell"
            ),
            onProgress = { progress ->
                VadModelDownloadCoordinator.update(VadModelDownloadState.Running(progress.downloadedBytes, progress.totalBytes, progress.resumed))
                update(if (progress.resumed) "Download wird fortgesetzt …" else "Download läuft …", progress.downloadedBytes, progress.totalBytes, true)
            },
            onVerifying = { bytes ->
                VadModelDownloadCoordinator.update(VadModelDownloadState.Verifying(bytes))
                update("Download wird geprüft …", bytes, bytes, true)
            }
        )
    }

    private fun notification(text: String, bytes: Long, total: Long, ongoing: Boolean): Notification {
        val percent = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, VAD_CHANNEL_ID)
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
            .setContentTitle(SileroVadModel.modelLabel)
            .setContentText(text)
            .setContentIntent(TranscriptNotifications.openAppIntent(this))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, percent, total <= 0L)
            .build()
    }

    private fun update(text: String, bytes: Long, total: Long, ongoing: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.VAD_MODEL_DOWNLOAD_ID, notification(text, bytes, total, ongoing)
        )
    }

    private fun finish(title: String, text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.VAD_MODEL_DOWNLOAD_ID, notification("$title: $text", 0L, 0L, false)
        )
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(VAD_CHANNEL_ID, "Silero-VAD-Download", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun partialFile() = File(File(filesDir, "vad-models").apply { mkdirs() }, "${SileroVadModel.fileName}.part")

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, VadModelDownloadService::class.java))
    }
}
