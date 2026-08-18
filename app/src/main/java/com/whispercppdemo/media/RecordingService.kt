package de.matthiasennen.transcript.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.R
import de.matthiasennen.transcript.download.TranscriptNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val RECORDING_CHANNEL_ID = "audio_recording"
private const val ACTION_START_RECORDING = "de.matthiasennen.transcript.START_RECORDING"
private const val ACTION_STOP_RECORDING = "de.matthiasennen.transcript.STOP_RECORDING"

class RecordingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var recorder: AudioRecorder
    private var meterJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedAtEpochMs = 0L
    private val stopStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        recorder = AudioRecorder(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_START_RECORDING -> if (meterJob?.isActive != true) startRecording(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording(startId: Int) {
        stopStarted.set(false)
        RecordingCoordinator.update(RecordingState.Starting)
        startForeground(TranscriptNotifications.RECORDING_ID, buildNotification("Aufnahme wird gestartet …"))
        runCatching { recorder.start() }
            .onSuccess { output ->
                startedAtEpochMs = System.currentTimeMillis()
                acquireWakeLock()
                meterJob = serviceScope.launch {
                    while (isActive) {
                        val elapsed = ((System.currentTimeMillis() - startedAtEpochMs) / 1_000L)
                            .coerceAtLeast(0L)
                        val accepted = RecordingCoordinator.updateRunning(
                            RecordingState.Running(
                                output = output,
                                startedAtEpochMs = startedAtEpochMs,
                                elapsedSeconds = elapsed,
                                amplitude = recorder.currentAmplitude()
                            )
                        )
                        if (!accepted) break
                        delay(250L)
                    }
                }
                getSystemService(NotificationManager::class.java).notify(
                    TranscriptNotifications.RECORDING_ID,
                    buildNotification("Aufnahme läuft")
                )
            }
            .onFailure { failure ->
                RecordingCoordinator.update(
                    RecordingState.Failed(
                        failure.localizedMessage ?: "Die Aufnahme konnte nicht gestartet werden."
                    )
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
    }

    private fun stopRecording() {
        if (!stopStarted.compareAndSet(false, true)) return
        if (!RecordingCoordinator.beginStopping()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        meterJob?.cancel()
        meterJob = null
        val output = recorder.stop()
        releaseWakeLock()
        if (output != null) {
            RecordingCoordinator.update(RecordingState.Completed(output))
        } else {
            RecordingCoordinator.update(
                RecordingState.Failed("Die Aufnahme war zu kurz oder konnte nicht gespeichert werden.")
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val wasRecording = meterJob?.isActive == true && !stopStarted.get()
        meterJob?.cancel()
        meterJob = null
        recorder.release()
        releaseWakeLock()
        serviceScope.cancel()
        if (wasRecording) {
            RecordingCoordinator.update(
                RecordingState.Failed("Die laufende Aufnahme wurde vom Android-System beendet.")
            )
        }
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_RECORDING },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RECORDING_CHANNEL_ID)
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
            .setContentTitle("Simple Transcript")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Beenden", stop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    RECORDING_CHANNEL_ID,
                    "Audioaufnahme",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Zeigt eine laufende Mikrofonaufnahme und deren Beenden-Aktion."
                }
            )
        }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:audio-recording"
        ).apply { acquire(12L * 60L * 60L * 1_000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START_RECORDING
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_STOP_RECORDING
                }
            )
        }
    }
}
