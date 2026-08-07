package de.matthiasennen.transcript.download

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
import de.matthiasennen.transcript.ui.main.WhisperModel
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

private const val CHANNEL_ID = "whisper_model_downloads"
private const val NOTIFICATION_ID = 2107
private const val ACTION_DOWNLOAD = "de.matthiasennen.transcript.DOWNLOAD_MODEL"
private const val EXTRA_MODEL_ID = "model_id"
private const val SERVICE_PREFERENCES = "model_download_service"
private const val ACTIVE_MODEL_KEY = "active_model"

class ModelDownloadService : Service() {
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
        val model = WhisperModel.fromId(modelId)

        if (downloadJob?.isActive == true) return START_REDELIVER_INTENT

        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_MODEL_KEY, model.id)
            .apply()

        val partial = partialFile(model)
        val existingBytes = partial.takeIf(File::isFile)?.length() ?: 0L
        startForeground(
            NOTIFICATION_ID,
            buildNotification(model, existingBytes, 0L, "Download wird vorbereitet …")
        )

        downloadJob = serviceScope.launch {
            runCatching { downloadAndVerify(model) }
                .onSuccess {
                    clearActiveModel()
                    ModelDownloadCoordinator.update(ModelDownloadState.Completed(model))
                    finishWithNotification(
                        model,
                        "Download abgeschlossen",
                        "${model.modelLabel} ist installiert."
                    )
                }
                .onFailure { throwable ->
                    clearActiveModel()
                    val partialBytes = partialFile(model).takeIf(File::isFile)?.length() ?: 0L
                    val message = throwable.localizedMessage ?: "Der Modelldownload ist fehlgeschlagen."
                    val previous = ModelDownloadCoordinator.state.value
                    val total = (previous as? ModelDownloadState.Running)?.totalBytes ?: 0L
                    ModelDownloadCoordinator.update(
                        ModelDownloadState.Failed(model, message, partialBytes, total)
                    )
                    finishWithNotification(model, "Download unterbrochen", message)
                }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun downloadAndVerify(model: WhisperModel) {
        val modelsDirectory = File(filesDir, "models").apply { mkdirs() }
        val destination = File(modelsDirectory, model.fileName)
        val partial = partialFile(model)

        if (destination.isFile && destination.length() >= model.minimumBytes) return

        if (partial.isFile && partial.length() >= model.minimumBytes) {
            if (sha256(partial) == model.sha256) {
                replaceDestination(partial, destination)
                return
            }
            check(partial.delete()) { "Der beschädigte Zwischendownload konnte nicht gelöscht werden." }
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
            check(responseCode in 200..299) {
                "Modelldownload fehlgeschlagen (HTTP $responseCode)."
            }
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
                        if (downloadedBytes - lastPublishedBytes >= 2 * 1024 * 1024L) {
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

        check(partial.length() >= model.minimumBytes) {
            "Das heruntergeladene Modell ist unvollständig."
        }
        ModelDownloadCoordinator.update(ModelDownloadState.Verifying(model, partial.length()))
        updateNotification(model, partial.length(), partial.length(), "Download wird geprüft …")
        check(sha256(partial) == model.sha256) {
            "Die Prüfsumme stimmt nicht. Der Zwischendownload bleibt für eine erneute Prüfung erhalten."
        }
        replaceDestination(partial, destination)
    }

    private fun openConnection(model: WhisperModel, startByte: Long): HttpURLConnection =
        (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (startByte > 0L) setRequestProperty("Range", "bytes=$startByte-")
            connect()
        }

    private fun publishProgress(
        model: WhisperModel,
        downloadedBytes: Long,
        totalBytes: Long,
        resumed: Boolean
    ) {
        ModelDownloadCoordinator.update(
            ModelDownloadState.Running(model, downloadedBytes, totalBytes, resumed)
        )
        val text = if (resumed) "Download wird fortgesetzt …" else "Download läuft …"
        updateNotification(model, downloadedBytes, totalBytes, text)
    }

    private fun updateNotification(
        model: WhisperModel,
        downloadedBytes: Long,
        totalBytes: Long,
        text: String
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(model, downloadedBytes, totalBytes, text)
        )
    }

    private fun buildNotification(
        model: WhisperModel,
        downloadedBytes: Long,
        totalBytes: Long,
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
        val percent = if (totalBytes > 0L) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(model.modelLabel)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, totalBytes <= 0L)
            .build()
    }

    private fun finishWithNotification(model: WhisperModel, title: String, text: String) {
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
                "Whisper-Modelldownloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Fortschritt laufender Whisper-Modelldownloads."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun partialFile(model: WhisperModel): File =
        File(File(filesDir, "models").apply { mkdirs() }, "${model.fileName}.part")

    private fun replaceDestination(partial: File, destination: File) {
        if (destination.exists()) check(destination.delete()) { "Das alte Modell konnte nicht ersetzt werden." }
        check(partial.renameTo(destination)) { "Das Modell konnte nicht gespeichert werden." }
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
            .edit()
            .remove(ACTIVE_MODEL_KEY)
            .apply()
    }

    companion object {
        fun start(context: Context, model: WhisperModel) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL_ID, model.id)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

internal fun contentRangeStart(value: String?): Long? {
    if (value == null) return null
    return Regex("bytes\\s+(\\d+)-\\d+/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
        .matchEntire(value.trim())
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
}

internal fun totalDownloadBytes(existingBytes: Long, remainingBytes: Long): Long =
    if (remainingBytes > 0L) existingBytes + remainingBytes else 0L
