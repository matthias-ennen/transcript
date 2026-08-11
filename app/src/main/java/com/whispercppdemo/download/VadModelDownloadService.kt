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

private const val VAD_CHANNEL_ID = "vad_model_downloads"
private const val VAD_NOTIFICATION_ID = 2110

class VadModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_REDELIVER_INTENT
        val partial = partialFile()
        startForeground(VAD_NOTIFICATION_ID, notification("Download wird vorbereitet …", partial.length(), 0L, true))
        job = scope.launch {
            runCatching(::downloadAndVerify).onSuccess {
                VadModelDownloadCoordinator.update(VadModelDownloadState.Completed)
                finish("Download abgeschlossen", "${SileroVadModel.modelLabel} ist installiert.")
            }.onFailure { throwable ->
                val bytes = partialFile().takeIf(File::isFile)?.length() ?: 0L
                val message = throwable.localizedMessage ?: "Der VAD-Modelldownload ist fehlgeschlagen."
                VadModelDownloadCoordinator.update(VadModelDownloadState.Failed(message, bytes, SileroVadModel.expectedBytes))
                finish("Download unterbrochen", message)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun downloadAndVerify() {
        val destination = modelFile()
        val partial = partialFile()
        if (destination.isFile && destination.length() == SileroVadModel.expectedBytes && sha256(destination) == SileroVadModel.sha256) return
        if (partial.isFile && partial.length() == SileroVadModel.expectedBytes) {
            check(sha256(partial) == SileroVadModel.sha256) { "Die Prüfsumme des VAD-Modells stimmt nicht." }
            replace(partial, destination)
            return
        }
        var existing = partial.takeIf(File::isFile)?.length() ?: 0L
        var connection = openConnection(existing)
        var resumed = existing > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL &&
            contentRangeStart(connection.getHeaderField("Content-Range")) == existing
        if (existing > 0L && !resumed) {
            connection.disconnect(); FileOutputStream(partial, false).use { }; existing = 0L
            connection = openConnection(0L); resumed = false
        }
        try {
            check(connection.responseCode in 200..299) { "VAD-Modelldownload fehlgeschlagen (HTTP ${connection.responseCode})." }
            var downloaded = existing
            publish(downloaded, SileroVadModel.expectedBytes, resumed)
            connection.inputStream.use { input ->
                FileOutputStream(partial, resumed).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); downloaded += count
                        publish(downloaded, SileroVadModel.expectedBytes, resumed)
                    }
                }
            }
        } finally { connection.disconnect() }
        check(partial.length() == SileroVadModel.expectedBytes) { "Das VAD-Modell ist unvollständig." }
        VadModelDownloadCoordinator.update(VadModelDownloadState.Verifying(partial.length()))
        update("Download wird geprüft …", partial.length(), partial.length(), true)
        check(sha256(partial) == SileroVadModel.sha256) { "Die Prüfsumme des VAD-Modells stimmt nicht." }
        replace(partial, destination)
    }

    private fun openConnection(start: Long) = (URL(SileroVadModel.downloadUrl).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000
        if (start > 0L) setRequestProperty("Range", "bytes=$start-"); connect()
    }

    private fun publish(bytes: Long, total: Long, resumed: Boolean) {
        VadModelDownloadCoordinator.update(VadModelDownloadState.Running(bytes, total, resumed))
        update(if (resumed) "Download wird fortgesetzt …" else "Download läuft …", bytes, total, true)
    }

    private fun notification(text: String, bytes: Long, total: Long, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0
        return NotificationCompat.Builder(this, VAD_CHANNEL_ID).setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(SileroVadModel.modelLabel).setContentText(text).setContentIntent(openApp)
            .setOnlyAlertOnce(true).setOngoing(ongoing).setProgress(100, percent, total <= 0L).build()
    }

    private fun update(text: String, bytes: Long, total: Long, ongoing: Boolean) =
        getSystemService(NotificationManager::class.java).notify(VAD_NOTIFICATION_ID, notification(text, bytes, total, ongoing))

    private fun finish(title: String, text: String) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(VAD_NOTIFICATION_ID,
            NotificationCompat.Builder(this, VAD_CHANNEL_ID).setSmallIcon(R.drawable.ic_mic).setContentTitle(title).setContentText(text).setAutoCancel(true).build())
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(VAD_CHANNEL_ID, "Silero-VAD-Download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun directory() = File(filesDir, "vad-models").apply { mkdirs() }
    private fun modelFile() = File(directory(), SileroVadModel.fileName)
    private fun partialFile() = File(directory(), "${SileroVadModel.fileName}.part")
    private fun replace(partial: File, destination: File) { if (destination.exists()) check(destination.delete()); check(partial.renameTo(destination)) }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input -> val buffer = ByteArray(256 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, VadModelDownloadService::class.java))
    }
}
