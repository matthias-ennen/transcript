package de.matthiasennen.transcript.song

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

private const val CHANNEL_ID = "song_model_downloads"
private const val ACTION_DOWNLOAD = "de.matthiasennen.transcript.DOWNLOAD_SONG_MODEL"
private const val EXTRA_MODEL_ID = "song_model_id"
private const val SERVICE_PREFERENCES = "song_model_download_service"
private const val ACTIVE_MODEL_KEY = "active_song_model"

class SongModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
            ?: getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
                .getString(ACTIVE_MODEL_KEY, null)
        if (modelId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val model = SongSeparationModel.fromId(modelId)
        if (downloadJob?.isActive == true) return START_REDELIVER_INTENT

        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_MODEL_KEY, model.id)
            .apply()

        startForeground(
            TranscriptNotifications.SONG_MODEL_DOWNLOAD_ID,
            notification(model, currentStoredBytes(model), model.totalDownloadBytes, "Download wird vorbereitet …", true)
        )

        downloadJob = serviceScope.launch {
            runCatching { downloadAndVerify(model) }
                .onSuccess {
                    clearActiveModel()
                    SongModelDownloadCoordinator.update(SongModelDownloadState.Completed(model))
                    finish(model, "Songmodell installiert", "${model.modelLabel} ist bereit.")
                }
                .onFailure { failure ->
                    clearActiveModel()
                    if (failure is InsufficientDownloadStorageException) {
                        DownloadStorageIssueCoordinator.show(failure.requirement)
                    }
                    val downloaded = currentStoredBytes(model)
                    val message = failure.localizedMessage ?: "Der Songmodelldownload ist fehlgeschlagen."
                    SongModelDownloadCoordinator.update(
                        SongModelDownloadState.Failed(
                            model = model,
                            message = message,
                            downloadedBytes = downloaded,
                            totalBytes = model.totalDownloadBytes
                        )
                    )
                    finish(model, "Songmodell-Download unterbrochen", message)
                }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun downloadAndVerify(model: SongSeparationModel) {
        val directory = File(filesDir, "song-models/${model.id}").apply { mkdirs() }
        var completedBytes = 0L

        model.artifacts.forEach { artifact ->
            val destination = File(directory, artifact.fileName)
            val partial = File(directory, "${artifact.fileName}.part")
            val alreadyComplete = destination.isFile && destination.length() == artifact.expectedBytes
            if (alreadyComplete) {
                completedBytes += artifact.expectedBytes
                return@forEach
            }

            val baseBytes = completedBytes
            VerifiedModelDownloader.downloadAndVerify(
                download = VerifiedModelDownload(
                    modelLabel = model.modelLabel,
                    downloadUrl = artifact.downloadUrl,
                    expectedBytes = artifact.expectedBytes,
                    sha256 = artifact.sha256,
                    destination = destination,
                    partial = partial,
                    failureLabel = "Songmodell"
                ),
                onProgress = { progress: DownloadProgress ->
                    val aggregate = (baseBytes + progress.downloadedBytes)
                        .coerceAtMost(model.totalDownloadBytes)
                    SongModelDownloadCoordinator.update(
                        SongModelDownloadState.Running(
                            model = model,
                            downloadedBytes = aggregate,
                            totalBytes = model.totalDownloadBytes,
                            resumed = progress.resumed
                        )
                    )
                    update(
                        model,
                        aggregate,
                        model.totalDownloadBytes,
                        if (progress.resumed) "Download wird fortgesetzt …" else "Download läuft …"
                    )
                },
                onVerifying = { bytes ->
                    val aggregate = (baseBytes + bytes).coerceAtMost(model.totalDownloadBytes)
                    SongModelDownloadCoordinator.update(
                        SongModelDownloadState.Verifying(
                            model = model,
                            downloadedBytes = aggregate,
                            totalBytes = model.totalDownloadBytes
                        )
                    )
                    update(model, aggregate, model.totalDownloadBytes, "Download wird geprüft …")
                }
            )
            completedBytes += artifact.expectedBytes
        }
    }

    private fun currentStoredBytes(model: SongSeparationModel): Long {
        val directory = File(filesDir, "song-models/${model.id}")
        return model.artifacts.sumOf { artifact ->
            val complete = File(directory, artifact.fileName)
            val partial = File(directory, "${artifact.fileName}.part")
            when {
                complete.isFile -> complete.length()
                partial.isFile -> partial.length()
                else -> 0L
            }
        }
    }

    private fun notification(
        model: SongSeparationModel,
        bytes: Long,
        total: Long,
        text: String,
        ongoing: Boolean
    ): Notification {
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

    private fun update(model: SongSeparationModel, bytes: Long, total: Long, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.SONG_MODEL_DOWNLOAD_ID,
            notification(model, bytes, total, text, true)
        )
    }

    private fun finish(model: SongSeparationModel, title: String, text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.SONG_MODEL_DOWNLOAD_ID,
            notification(model, 0L, 0L, "$title: $text", false)
        )
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Songmodell-Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Zeigt den Fortschritt lokaler Modelle zur Gesangstrennung."
                }
            )
        }
    }

    private fun clearActiveModel() {
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .remove(ACTIVE_MODEL_KEY)
            .apply()
    }

    companion object {
        fun start(context: Context, model: SongSeparationModel) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SongModelDownloadService::class.java).apply {
                    action = ACTION_DOWNLOAD
                    putExtra(EXTRA_MODEL_ID, model.id)
                }
            )
        }
    }
}
