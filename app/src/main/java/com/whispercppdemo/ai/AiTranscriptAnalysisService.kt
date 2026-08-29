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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.download.TranscriptNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val ANALYSIS_CHANNEL_ID = "local_ai_transcript_analysis"
private const val ACTION_START_ANALYSIS = "de.matthiasennen.transcript.START_AI_TRANSCRIPT_ANALYSIS"
private const val ACTION_CANCEL_ANALYSIS = "de.matthiasennen.transcript.CANCEL_AI_TRANSCRIPT_ANALYSIS"
private const val ANALYSIS_REQUEST_FILE = "active-ai-transcript-analysis.bin"

class AiTranscriptAnalysisService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private var processingJob: Job? = null
    private lateinit var requestStore: AiTranscriptAnalysisRequestStore
    private var activeConfiguration: LocalAiConfiguration? = null

    override fun onCreate() {
        super.onCreate()
        requestStore = AiTranscriptAnalysisRequestStore(File(filesDir, ANALYSIS_REQUEST_FILE))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ANALYSIS) {
            stopRequested.set(true)
            val current = AiTranscriptAnalysisCoordinator.state.value
            when (current) {
                is AiTranscriptAnalysisState.Starting -> AiTranscriptAnalysisCoordinator.update(
                    AiTranscriptAnalysisState.CancellationRequested(
                        current.action,
                        current.model,
                        current.sourceFingerprint
                    )
                )
                is AiTranscriptAnalysisState.Running -> AiTranscriptAnalysisCoordinator.update(
                    AiTranscriptAnalysisState.CancellationRequested(
                        current.action,
                        current.model,
                        current.sourceFingerprint
                    )
                )
                else -> Unit
            }
            if (processingJob?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }

        if (processingJob?.isActive == true) return START_REDELIVER_INTENT
        val request = requestStore.read()
        if (intent?.action != ACTION_START_ANALYSIS || request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val model = AiModel.fromId(request.modelId)
        stopRequested.set(false)
        AiTranscriptAnalysisCoordinator.update(
            AiTranscriptAnalysisState.Starting(
                action = request.action,
                model = model,
                sourceFingerprint = request.sourceFingerprint
            )
        )
        startForeground(
            TranscriptNotifications.AI_PROCESSING_ID,
            buildNotification("${request.action.displayLabel} wird vorbereitet …", 0, true)
        )
        processingJob = serviceScope.launch {
            runAnalysis(request, model)
            processingJob = null
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested.set(true)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun runAnalysis(request: AiTranscriptAnalysisRequest, model: AiModel) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val modelFile = File(File(filesDir, "ai-models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }
            val configuration = guardedAiConfiguration(
                context = this,
                modelFile = modelFile,
                model = model,
                cancellationRequested = stopRequested::get
            )
            activeConfiguration = configuration
            ensureContinues()

            val analyzer = AiTranscriptAnalyzer(
                configuration = configuration,
                ensureContinues = ::ensureContinues,
                onProgress = { progress ->
                    AiTranscriptAnalysisCoordinator.update(
                        AiTranscriptAnalysisState.Running(
                            action = request.action,
                            model = model,
                            sourceFingerprint = request.sourceFingerprint,
                            progress = progress.progress,
                            status = progress.status,
                            activityDetail = progress.activityDetail
                        )
                    )
                    getSystemService(NotificationManager::class.java).notify(
                        TranscriptNotifications.AI_PROCESSING_ID,
                        buildNotification(
                            progress.activityDetail,
                            (progress.progress * 100f).toInt(),
                            false
                        )
                    )
                }
            )

            val session = AiEngineSessionManager.withModel(
                model = model,
                file = modelFile,
                configuration = configuration
            ) { engine, _ ->
                analyzer.analyze(
                    engine = engine,
                    action = request.action,
                    source = request.sourceText
                )
            }
            ensureContinues()
            val execution = session.value
            val result = AiTranscriptAnalysisResult(
                action = request.action,
                model = model,
                text = execution.text,
                sourceFileName = request.fileName,
                sourceFingerprint = request.sourceFingerprint,
                sourceChunkCount = execution.sourceChunkCount,
                generationCount = execution.generationCount,
                modelLoadMs = session.info.modelLoadMs,
                totalInferenceMs = execution.totalInferenceMs,
                totalDurationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                cpuFallbackUsed = session.info.cpuFallbackUsed
            )
            requestStore.clear()
            AiTranscriptAnalysisCoordinator.update(AiTranscriptAnalysisState.Completed(result))
            finishWithNotification(
                title = "KI-Auswertung abgeschlossen",
                text = "${request.action.resultTitle} ist bereit."
            )
        } catch (cancelled: CancellationException) {
            requestStore.clear()
            AiTranscriptAnalysisCoordinator.update(
                AiTranscriptAnalysisState.Cancelled(
                    action = request.action,
                    model = model,
                    sourceFingerprint = request.sourceFingerprint
                )
            )
            finishWithNotification("KI-Auswertung abgebrochen", "Es wurde kein Ergebnis übernommen.")
        } catch (failure: Throwable) {
            requestStore.clear()
            if (stopRequested.get()) {
                AiTranscriptAnalysisCoordinator.update(
                    AiTranscriptAnalysisState.Cancelled(
                        action = request.action,
                        model = model,
                        sourceFingerprint = request.sourceFingerprint
                    )
                )
                finishWithNotification("KI-Auswertung abgebrochen", "Es wurde kein Ergebnis übernommen.")
            } else {
                val message = failure.localizedMessage ?: "Die lokale KI-Auswertung ist fehlgeschlagen."
                AiTranscriptAnalysisCoordinator.update(
                    AiTranscriptAnalysisState.Failed(
                        action = request.action,
                        model = model,
                        sourceFingerprint = request.sourceFingerprint,
                        message = message
                    )
                )
                finishWithNotification("KI-Auswertung fehlgeschlagen", message)
            }
        } finally {
            activeConfiguration = null
        }
    }

    private fun ensureContinues() {
        if (stopRequested.get()) throw CancellationException("KI-Auswertung wurde abgebrochen.")
        activeConfiguration?.let { ensureAiRuntimeCanContinue(this, it) }
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ANALYSIS_CHANNEL_ID)
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
            .setContentTitle("Lokale KI-Auswertung")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun finishWithNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, ANALYSIS_CHANNEL_ID)
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.AI_PROCESSING_ID,
            notification
        )
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ANALYSIS_CHANNEL_ID,
                "Lokale KI-Auswertung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Fortschritt lokaler Auswertungen fertiger Transkripte."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        fun start(
            context: Context,
            model: AiModel,
            fileName: String,
            action: AiTranscriptAnalysisAction,
            sourceText: String
        ) {
            val fingerprint = aiTranscriptSourceFingerprint(sourceText)
            AiTranscriptAnalysisRequestStore(File(context.filesDir, ANALYSIS_REQUEST_FILE)).write(
                AiTranscriptAnalysisRequest(
                    action = action,
                    modelId = model.id,
                    fileName = fileName,
                    sourceText = sourceText,
                    sourceFingerprint = fingerprint
                )
            )
            AiTranscriptAnalysisCoordinator.update(
                AiTranscriptAnalysisState.Starting(action, model, fingerprint)
            )
            val intent = Intent(context, AiTranscriptAnalysisService::class.java).apply {
                this.action = ACTION_START_ANALYSIS
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, AiTranscriptAnalysisService::class.java).apply {
                    action = ACTION_CANCEL_ANALYSIS
                }
            )
        }
    }
}
