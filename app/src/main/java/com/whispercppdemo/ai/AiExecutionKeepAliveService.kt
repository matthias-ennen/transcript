package de.matthiasennen.transcript.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.matthiasennen.transcript.download.TranscriptNotifications

private const val CHANNEL_ID = "local_ai_execution"
private const val EXTRA_STATUS_TEXT = "status_text"
private const val DEFAULT_STATUS_TEXT = "Lokale KI arbeitet …"
private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L

/**
 * Keeps UI-bound local AI work alive while the display is off.
 *
 * Long-running transcript post-processing already owns its own foreground service. This
 * companion service is used by diagnostics/benchmarks that intentionally remain in the app
 * process instead of moving the complete benchmark state machine into a second process.
 */
class AiExecutionKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = intent?.getStringExtra(EXTRA_STATUS_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STATUS_TEXT

        startForeground(
            TranscriptNotifications.AI_EXECUTION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(TranscriptNotifications.SMALL_ICON)
                .setContentTitle("Lokale KI arbeitet")
                .setContentText(statusText)
                .setContentIntent(TranscriptNotifications.openAppIntent(this))
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()
        )
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:local-ai-execution")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lokale KI-Ausführung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hält lokale KI-Aufgaben bei ausgeschaltetem Bildschirm aktiv."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        fun start(context: Context, statusText: String = DEFAULT_STATUS_TEXT) {
            val intent = Intent(context, AiExecutionKeepAliveService::class.java).apply {
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AiExecutionKeepAliveService::class.java))
        }
    }
}
