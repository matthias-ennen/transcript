package de.matthiasennen.transcript.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.R
import de.matthiasennen.transcript.download.contentRangeStart
import de.matthiasennen.transcript.download.totalDownloadBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val CHANNEL_ID = "local_ai_model_downloads"
private const val NOTIFICATION_ID = 2110
private const val ACTION_DOWNLOAD = "de.matthiasennen.transcript.DOWNLOAD_AI_MODEL"
private const val EXTRA_MODEL_ID = "ai_model_id"
private const val SERVICE_PREFERENCES = "ai_model_download_service"
private const val ACTIVE_MODEL_KEY = "active_ai_model"

class AiModelDownloadService : Service() {
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
        val model = AiModel.fromId(modelId)
        if (downloadJob?.isActive == true) return START_REDELIVER_INTENT

        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit().putString(ACTIVE_MODEL_KEY, model.id).apply()

        val partial = partialFile(model)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                model,
                partial.takeIf(File::isFile)?.length() ?: 0L,
                0L,
                "Download wird vorbereitet …"
            )
        )
        downloadJob = serviceScope.launch {
            runCatching { downloadAndVerify(model) }
                .onSuccess {
                    clearActiveModel()
                    AiModelDownloadCoordinator.update(AiModelDownloadState.Completed(model))
                    finishWithNotification("KI-Modell installiert", "${model.modelLabel} ist bereit.")
                }
                .onFailure { throwable ->
                    clearActiveModel()
                    val partialBytes = partialFile(model).takeIf(File::isFile)?.length() ?: 0L
                    val previous = AiModelDownloadCoordinator.state.value
                    val total = (previous as? AiModelDownloadState.Running)?.totalBytes ?: 0L
                    val message = throwable.localizedMessage ?: "Der KI-Modelldownload ist fehlgeschlagen."
                    AiModelDownloadCoordinator.update(
                        AiModelDownloadState.Failed(model, message, partialBytes, total)
                    )
                    finishWithNotification("KI-Download unterbrochen", message)
                }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun downloadAndVerify(model: AiModel) {
        val destination = modelFile(model)
        val partial = partialFile(model)
        if (destination.isFile && destination.length() >= model.minimumBytes) return

        if (partial.isFile && partial.length() >= model.minimumBytes) {
            if (sha256(partial) == model.sha256) {
                replaceDestination(partial, destination)
                return
            }
            check(partial.delete()) { "Der beschädigte KI-Zwischendownload konnte nicht gelöscht werden." }
        }

        var existingBytes = partial.takeIf(File::isFile)?.length() ?: 0L
        var connection = openConnection(model, existingBytes)
        var responseCode = connection.responseCode
        var resumed = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL &&
            contentRangeStart(connection.getHeaderField("Content-Range")) == existingBytes

        if (existingBytes > 0L && !resumed) {
            connection.disconnect()
            FileOutputStream(partial, false).use { }
            existingBytes = 0L
            connection = openConnection(model, 0L)
            responseCode = connection.responseCode
        }

        try {
            check(responseCode in 200..299) { "KI-Modelldownload fehlgeschlagen (HTTP $responseCode)." }
            val remainingBytes = connection.contentLengthLong.coerceAtLeast(0L)
            val totalBytes = totalDownloadBytes(existingBytes, remainingBytes)
            var downloadedBytes = existingBytes
            publishProgress(model, downloadedBytes, totalBytes, resumed)

            connection.inputStream.use { input ->
                FileOutputStream(partial, resumed).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var lastPublishedBytes = downloadedBytes
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        if (downloadedBytes - lastPublishedBytes >= 4 * 1024 * 1024L) {
                            publishProgress(model, downloadedBytes, totalBytes, resumed)
                            lastPublishedBytes = downloadedBytes
                        }
                    }
                }
            }
            publishProgress(model, downloadedBytes, totalBytes, resumed)
        } finally {
            connection.disconnect()
        }

        check(partial.length() >= model.minimumBytes) { "Das heruntergeladene KI-Modell ist unvollständig." }
        AiModelDownloadCoordinator.update(AiModelDownloadState.Verifying(model, partial.length()))
        updateNotification(model, partial.length(), partial.length(), "Download wird geprüft …")
        check(sha256(partial) == model.sha256) {
            "Die Prüfsumme des KI-Modells stimmt nicht."
        }
        replaceDestination(partial, destination)
    }

    private fun openConnection(model: AiModel, startByte: Long): HttpURLConnection =
        (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (startByte > 0L) setRequestProperty("Range", "bytes=$startByte-")
            connect()
        }

    private fun publishProgress(model: AiModel, downloaded: Long, total: Long, resumed: Boolean) {
        AiModelDownloadCoordinator.update(AiModelDownloadState.Running(model, downloaded, total, resumed))
        updateNotification(
            model,
            downloaded,
            total,
            if (resumed) "Download wird fortgesetzt …" else "Download läuft …"
        )
    }

    private fun updateNotification(model: AiModel, downloaded: Long, total: Long, text: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(model, downloaded, total, text)
        )
    }

    private fun buildNotification(
        model: AiModel,
        downloaded: Long,
        total: Long,
        text: String
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(model.modelLabel)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0L)
            .build()
    }

    private fun finishWithNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lokale KI-Modelldownloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Fortschritt lokaler KI-Modelldownloads."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun modelsDirectory(): File = File(filesDir, "ai-models").apply { mkdirs() }
    private fun modelFile(model: AiModel): File = File(modelsDirectory(), model.fileName)
    private fun partialFile(model: AiModel): File = File(modelsDirectory(), "${model.fileName}.part")

    private fun replaceDestination(partial: File, destination: File) {
        if (destination.exists()) check(destination.delete()) { "Das alte KI-Modell konnte nicht ersetzt werden." }
        check(partial.renameTo(destination)) { "Das KI-Modell konnte nicht gespeichert werden." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun clearActiveModel() {
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit().remove(ACTIVE_MODEL_KEY).apply()
    }

    companion object {
        fun start(context: Context, model: AiModel) {
            val intent = Intent(context, AiModelDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, model.id)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
