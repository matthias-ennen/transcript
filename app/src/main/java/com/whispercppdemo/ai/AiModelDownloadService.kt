package de.matthiasennen.transcript.ai

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
import de.matthiasennen.transcript.download.DownloadProgress
import de.matthiasennen.transcript.download.DownloadStorageIssueCoordinator
import de.matthiasennen.transcript.download.InsufficientDownloadStorageException
import de.matthiasennen.transcript.download.TranscriptNotifications
import de.matthiasennen.transcript.download.VerifiedModelDownload
import de.matthiasennen.transcript.download.VerifiedModelDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

private const val CHANNEL_ID = "local_ai_model_downloads"
private const val ACTION_DOWNLOAD = "de.matthiasennen.transcript.DOWNLOAD_AI_MODEL"
private const val EXTRA_MODEL_ID = "ai_model_id"
private const val SERVICE_PREFERENCES = "ai_model_download_service"
private const val ACTIVE_MODEL_KEY = "active_ai_model"

class AiModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
            ?: getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).getString(ACTIVE_MODEL_KEY, null)
        if (modelId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val model = AiModel.fromId(modelId)
        if (downloadJob?.isActive == true) return START_REDELIVER_INTENT
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit().putString(ACTIVE_MODEL_KEY, model.id).apply()
        val partial = partialFile(model)
        startForeground(
            TranscriptNotifications.AI_MODEL_DOWNLOAD_ID,
            notification(model, partial.takeIf(File::isFile)?.length() ?: 0L, 0L, "Download wird vorbereitet …", true)
        )
        downloadJob = serviceScope.launch {
            runCatching { downloadAndVerify(model) }.onSuccess {
                clearActiveModel()
                AiModelDownloadCoordinator.update(AiModelDownloadState.Completed(model))
                finish(model, "KI-Modell installiert", "${model.modelLabel} ist bereit.")
            }.onFailure { failure ->
                clearActiveModel()
                if (failure is InsufficientDownloadStorageException) DownloadStorageIssueCoordinator.show(failure.requirement)
                val previous = AiModelDownloadCoordinator.state.value
                val total = (previous as? AiModelDownloadState.Running)?.totalBytes ?: 0L
                val partialBytes = partialFile(model).takeIf(File::isFile)?.length() ?: 0L
                val message = failure.localizedMessage ?: "Der KI-Modelldownload ist fehlgeschlagen."
                AiModelDownloadCoordinator.update(AiModelDownloadState.Failed(model, message, partialBytes, total))
                finish(model, "KI-Download unterbrochen", message)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { serviceScope.cancel(); super.onDestroy() }

    private fun downloadAndVerify(model: AiModel) {
        val directory = File(filesDir, "ai-models").apply { mkdirs() }
        VerifiedModelDownloader.downloadAndVerify(
            download = VerifiedModelDownload(
                modelLabel = model.modelLabel,
                downloadUrl = model.downloadUrl,
                expectedBytes = model.minimumBytes,
                sha256 = model.sha256,
                destination = File(directory, model.fileName),
                partial = partialFile(model),
                failureLabel = "KI-Modell"
            ),
            onProgress = { progress: DownloadProgress ->
                AiModelDownloadCoordinator.update(AiModelDownloadState.Running(model, progress.downloadedBytes, progress.totalBytes, progress.resumed))
                update(model, progress.downloadedBytes, progress.totalBytes, if (progress.resumed) "Download wird fortgesetzt …" else "Download läuft …")
            },
            onVerifying = { bytes ->
                AiModelDownloadCoordinator.update(AiModelDownloadState.Verifying(model, bytes))
                update(model, bytes, bytes, "Download wird geprüft …")
            }
        )
    }

    private fun notification(model: AiModel, bytes: Long, total: Long, text: String, ongoing: Boolean): Notification {
        val percent = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
            .setContentTitle(model.modelLabel)
            .setContentText(text)
            .setContentIntent(TranscriptNotifications.openAppIntent(this))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, percent, total <= 0L)
            .build()
    }

    private fun update(model: AiModel, bytes: Long, total: Long, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.AI_MODEL_DOWNLOAD_ID, notification(model, bytes, total, text, true)
        )
    }

    private fun finish(model: AiModel, title: String, text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.AI_MODEL_DOWNLOAD_ID, notification(model, 0L, 0L, "$title: $text", false)
        )
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Lokale KI-Modelldownloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Zeigt den Fortschritt lokaler KI-Modelldownloads."
                }
            )
        }
    }

    private fun partialFile(model: AiModel) = File(File(filesDir, "ai-models").apply { mkdirs() }, "${model.fileName}.part")
    private fun clearActiveModel() { getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit().remove(ACTIVE_MODEL_KEY).apply() }

    companion object {
        fun start(context: Context, model: AiModel) {
            ContextCompat.startForegroundService(context, Intent(context, AiModelDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, model.id)
            })
        }
    }
}
