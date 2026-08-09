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
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val CHANNEL_ID = "local_ai_postprocessing"
private const val NOTIFICATION_ID = 2111
private const val ACTION_START = "de.matthiasennen.transcript.START_AI_POSTPROCESSING"
private const val ACTION_START_SELF_TEST = "de.matthiasennen.transcript.START_AI_SELF_TEST"
private const val EXTRA_MODEL_ID = "model_id"
private const val REQUEST_FILE_NAME = "active-ai-postprocessing.bin"
private const val TRANSCRIPT_GROUP_DURATION_MS = 5L * 60L * 1_000L
private const val NEIGHBOR_CONTEXT_SEGMENTS = 8

class AiPostProcessingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private var processingJob: Job? = null
    private lateinit var requestStore: AiPostProcessingRequestStore
    private val diagnostics = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        requestStore = AiPostProcessingRequestStore(File(filesDir, REQUEST_FILE_NAME))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (processingJob?.isActive == true) return START_REDELIVER_INTENT
        if (intent?.action == ACTION_START_SELF_TEST) {
            val model = AiModel.fromId(intent.getStringExtra(EXTRA_MODEL_ID))
            stopRequested.set(false)
            diagnostics.clear()
            AiPostProcessingCoordinator.update(AiPostProcessingState.SelfTestStarting(model))
            startForeground(
                NOTIFICATION_ID,
                buildNotification("KI-Selbsttest wird vorbereitet …", 0, true)
            )
            processingJob = serviceScope.launch {
                runSelfTest(model)
                processingJob = null
            }
            return START_REDELIVER_INTENT
        }
        val request = requestStore.read()
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val model = AiModel.fromId(request.modelId)
        stopRequested.set(false)
        diagnostics.clear()
        AiPostProcessingCoordinator.update(AiPostProcessingState.Starting(request.mode, model))
        startForeground(
            NOTIFICATION_ID,
            buildNotification("KI-Nachbearbeitung wird vorbereitet …", 0, true)
        )
        processingJob = serviceScope.launch {
            runProcessing(request)
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

    private fun runSelfTest(model: AiModel) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val modelFile = File(File(filesDir, "ai-models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }
            addDiagnostic("KI-Selbsttest: ${model.modelLabel} wird lokal geladen.")
            AiPostProcessingCoordinator.update(
                AiPostProcessingState.SelfTestRunning(
                    model = model,
                    status = "KI-Modell wird geladen …",
                    activityDetail = "${model.modelLabel} wird für den Selbsttest vorbereitet.",
                    diagnostics = diagnostics.toList()
                )
            )
            LocalAiEngine(modelFile.absolutePath).use { engine ->
                addDiagnostic("KI-Modell geladen. Selbsttest wird an die KI gesendet.")
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.SelfTestRunning(
                        model = model,
                        status = "Selbsttest wird an KI gesendet …",
                        activityDetail = "Frage: Was ist der Mond?",
                        diagnostics = diagnostics.toList()
                    )
                )
                val response = engine.generateSelfTest()
                addDiagnostic("Erstes Antwortzeichen empfangen.")
                addDiagnostic("Antwort vollständig empfangen: ${response.length} Zeichen.")
                val durationSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L)
                    .coerceAtLeast(0L)
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.SelfTestCompleted(
                        model = model,
                        response = response,
                        durationSeconds = durationSeconds,
                        diagnostics = diagnostics.toList()
                    )
                )
                finishWithNotification(
                    "KI-Selbsttest erfolgreich",
                    "Antwort vollständig empfangen: ${response.length} Zeichen."
                )
            }
        } catch (throwable: Throwable) {
            if (!stopRequested.get() && throwable !is CancellationException) {
                val message = throwable.localizedMessage ?: "Der KI-Selbsttest ist fehlgeschlagen."
                addDiagnostic("KI-Selbsttest-Fehler: $message")
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.SelfTestFailed(
                        model = model,
                        message = message,
                        diagnostics = diagnostics.toList()
                    )
                )
                finishWithNotification("KI-Selbsttest fehlgeschlagen", message)
            }
        }
    }

    private fun runProcessing(initialRequest: AiPostProcessingRequest) {
        val startedAt = SystemClock.elapsedRealtime()
        val model = AiModel.fromId(initialRequest.modelId)
        val originalSegments = initialRequest.segments
        try {
            val modelFile = File(File(filesDir, "ai-models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }
            addDiagnostic("Whisper-Speicher wurde freigegeben.")
            addDiagnostic("${model.modelLabel} wird lokal geladen.")

            LocalAiEngine(modelFile.absolutePath).use { engine ->
                addDiagnostic("${model.modelLabel} ist bereit.")
                var correctedSegments = initialRequest.segments
                val groups = targetGroups(initialRequest)
                check(groups.isNotEmpty()) { "Für die KI-Nachbearbeitung wurde kein Text gefunden." }
                var checkedSegments = 0
                var appliedCorrections = 0
                var rejectedCorrections = 0

                groups.forEachIndexed { groupIndex, indexes ->
                    if (groupIndex < initialRequest.nextGroupIndex) return@forEachIndexed
                    ensureContinues()
                    val rangeStartMs = correctedSegments[indexes.first()].startMs
                    val rangeEndMs = correctedSegments[indexes.last()].endMs
                    val indexed = indexes.map { index ->
                        IndexedTranscriptSegment(index, correctedSegments[index])
                    }
                    val contextBefore = neighboringContextBefore(
                        correctedSegments,
                        indexes.first(),
                        NEIGHBOR_CONTEXT_SEGMENTS
                    )
                    val contextAfter = neighboringContextAfter(
                        correctedSegments,
                        indexes.last(),
                        NEIGHBOR_CONTEXT_SEGMENTS
                    )
                    val label = "${formatClock(rangeStartMs)}–${formatClock(rangeEndMs)}"
                    publishRunning(
                        request = initialRequest,
                        model = model,
                        groupNumber = groupIndex + 1,
                        groupCount = groups.size,
                        correctedSegments = correctedSegments,
                        status = "KI prüft Texte …",
                        activity = "Bereich $label: ${indexes.size} Segmente werden geprüft.",
                        checkedSegments = checkedSegments,
                        proposedCorrections = appliedCorrections,
                        rejectedCorrections = rejectedCorrections
                    )
                    addDiagnostic("KI prüft Bereich $label (${indexes.size} Segmente).")
                    addDiagnostic(
                        "${contextBefore.size + contextAfter.size} Nachbarsegmente dienen nur als Kontext."
                    )

                    val response = engine.generate(
                        prompt = buildCorrectionPrompt(indexed, contextBefore, contextAfter),
                        maximumOutputTokens = maximumCorrectionTokens(indexed)
                    )
                    val parsed = parseCorrectedSegments(
                        response = response,
                        expectedIndexes = indexes.map { it + 1 }
                    )
                    correctedSegments = applyCorrections(correctedSegments, parsed.corrections)
                    checkedSegments += indexes.size
                    appliedCorrections += parsed.corrections.size
                    rejectedCorrections += parsed.rejectedEntries
                    addDiagnostic("Bereich $label geprüft: ${parsed.corrections.size} Korrekturen erkannt.")
                    requestStore.write(
                        initialRequest.copy(
                            segments = correctedSegments,
                            nextGroupIndex = groupIndex + 1
                        )
                    )
                    publishRunning(
                        request = initialRequest,
                        model = model,
                        groupNumber = groupIndex + 1,
                        groupCount = groups.size,
                        correctedSegments = correctedSegments,
                        status = "KI-Nachbearbeitung läuft …",
                        activity = "$checkedSegments Segmente geprüft, $appliedCorrections Korrekturen erkannt.",
                        checkedSegments = checkedSegments,
                        proposedCorrections = appliedCorrections,
                        rejectedCorrections = rejectedCorrections
                    )
                }

                requestStore.clear()
                val durationSeconds = ((SystemClock.elapsedRealtime() - startedAt) / 1_000L)
                    .coerceAtLeast(0L)
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.Completed(
                        mode = initialRequest.mode,
                        model = model,
                        segments = correctedSegments,
                        groupStartMs = initialRequest.groupStartMs,
                        durationSeconds = durationSeconds,
                        diagnostics = diagnostics.toList(),
                        checkedSegments = checkedSegments,
                        appliedCorrections = appliedCorrections,
                        rejectedCorrections = rejectedCorrections
                    )
                )
                finishWithNotification(
                    "KI-Nachbearbeitung abgeschlossen",
                    "$checkedSegments Segmente geprüft, $appliedCorrections Korrekturen übernommen."
                )
            }
        } catch (throwable: Throwable) {
            if (!stopRequested.get() && throwable !is CancellationException) {
                requestStore.clear()
                val message = throwable.localizedMessage ?: "Die KI-Nachbearbeitung ist fehlgeschlagen."
                addDiagnostic("Fehler: $message")
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.Failed(
                        mode = initialRequest.mode,
                        model = model,
                        message = message,
                        originalSegments = originalSegments,
                        groupStartMs = initialRequest.groupStartMs,
                        diagnostics = diagnostics.toList()
                    )
                )
                finishWithNotification("KI-Nachbearbeitung fehlgeschlagen", message)
            }
        }
    }

    private fun targetGroups(request: AiPostProcessingRequest): List<List<Int>> {
        val all = request.segments.indices
            .filter { request.segments[it].text.isNotBlank() }
            .groupBy { request.segments[it].startMs / TRANSCRIPT_GROUP_DURATION_MS }
            .toSortedMap()
            .values
            .map(List<Int>::toList)
        return when (request.mode) {
            AiPostProcessingMode.AUTOMATIC -> all
            AiPostProcessingMode.MANUAL_GROUP -> {
                val expected = requireNotNull(request.groupStartMs) / TRANSCRIPT_GROUP_DURATION_MS
                all.filter { indexes ->
                    indexes.isNotEmpty() &&
                        request.segments[indexes.first()].startMs / TRANSCRIPT_GROUP_DURATION_MS == expected
                }
            }
        }
    }

    private fun publishRunning(
        request: AiPostProcessingRequest,
        model: AiModel,
        groupNumber: Int,
        groupCount: Int,
        correctedSegments: List<WhisperSegment>,
        status: String,
        activity: String,
        checkedSegments: Int,
        proposedCorrections: Int,
        rejectedCorrections: Int
    ) {
        val progress = (groupNumber.toFloat() / groupCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        AiPostProcessingCoordinator.update(
            AiPostProcessingState.Running(
                mode = request.mode,
                model = model,
                progress = progress,
                groupNumber = groupNumber,
                groupCount = groupCount,
                status = status,
                activityDetail = activity,
                diagnostics = diagnostics.toList(),
                correctedSegments = correctedSegments,
                groupStartMs = request.groupStartMs,
                checkedSegments = checkedSegments,
                proposedCorrections = proposedCorrections,
                rejectedCorrections = rejectedCorrections
            )
        )
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(activity, (progress * 100).toInt(), false)
        )
    }

    private fun addDiagnostic(message: String) {
        if (diagnostics.size >= 10) diagnostics.removeFirst()
        diagnostics.addLast("${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} · $message")
    }

    private fun ensureContinues() {
        if (stopRequested.get()) throw CancellationException("KI-Nachbearbeitung beendet.")
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Lokale KI-Nachbearbeitung")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
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
                "Lokale KI-Nachbearbeitung",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt den Fortschritt der lokalen Textkorrektur."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        fun startSelfTest(context: Context, model: AiModel) {
            val intent = Intent(context, AiPostProcessingService::class.java).apply {
                action = ACTION_START_SELF_TEST
                putExtra(EXTRA_MODEL_ID, model.id)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startAutomatic(
            context: Context,
            model: AiModel,
            fileName: String,
            segments: List<WhisperSegment>
        ) {
            start(
                context,
                AiPostProcessingRequest(
                    mode = AiPostProcessingMode.AUTOMATIC,
                    modelId = model.id,
                    fileName = fileName,
                    groupStartMs = null,
                    segments = segments
                )
            )
        }

        fun startManualGroup(
            context: Context,
            model: AiModel,
            fileName: String,
            groupStartMs: Long,
            segments: List<WhisperSegment>
        ) {
            start(
                context,
                AiPostProcessingRequest(
                    mode = AiPostProcessingMode.MANUAL_GROUP,
                    modelId = model.id,
                    fileName = fileName,
                    groupStartMs = groupStartMs,
                    segments = segments
                )
            )
        }

        private fun start(context: Context, request: AiPostProcessingRequest) {
            AiPostProcessingRequestStore(File(context.filesDir, REQUEST_FILE_NAME)).write(request)
            val intent = Intent(context, AiPostProcessingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private fun neighboringContextBefore(
    segments: List<WhisperSegment>,
    firstTargetIndex: Int,
    limit: Int
): List<IndexedTranscriptSegment> =
    ((firstTargetIndex - limit).coerceAtLeast(0) until firstTargetIndex)
        .filter { segments[it].text.isNotBlank() }
        .map { IndexedTranscriptSegment(it, segments[it]) }

private fun neighboringContextAfter(
    segments: List<WhisperSegment>,
    lastTargetIndex: Int,
    limit: Int
): List<IndexedTranscriptSegment> =
    ((lastTargetIndex + 1)..(lastTargetIndex + limit).coerceAtMost(segments.lastIndex))
        .filter { it in segments.indices && segments[it].text.isNotBlank() }
        .map { IndexedTranscriptSegment(it, segments[it]) }

private fun formatClock(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
