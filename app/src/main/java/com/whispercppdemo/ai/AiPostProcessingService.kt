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
import de.matthiasennen.transcript.download.TranscriptNotifications
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
private const val ACTION_START = "de.matthiasennen.transcript.START_AI_POSTPROCESSING"
private const val ACTION_START_SELF_TEST = "de.matthiasennen.transcript.START_AI_SELF_TEST"
private const val ACTION_PRELOAD_MODEL = "de.matthiasennen.transcript.PRELOAD_AI_MODEL"
private const val EXTRA_MODEL_ID = "model_id"
private const val EXTRA_TEST_PROMPT = "test_prompt"
private const val REQUEST_FILE_NAME = "active-ai-postprocessing.bin"
private const val TRANSCRIPT_GROUP_DURATION_MS = 5L * 60L * 1_000L

class AiPostProcessingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private var processingJob: Job? = null
    private lateinit var requestStore: AiPostProcessingRequestStore
    private val diagnostics = ArrayDeque<String>()
    private var activeConfiguration: LocalAiConfiguration? = null

    override fun onCreate() {
        super.onCreate()
        requestStore = AiPostProcessingRequestStore(File(filesDir, REQUEST_FILE_NAME))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (processingJob?.isActive == true) return START_REDELIVER_INTENT
        if (intent?.action == ACTION_PRELOAD_MODEL) {
            val model = AiModel.fromId(intent.getStringExtra(EXTRA_MODEL_ID))
            stopRequested.set(false)
            diagnostics.clear()
            AiPostProcessingCoordinator.update(AiPostProcessingState.ModelPreloadStarting(model))
            startForeground(
                TranscriptNotifications.AI_PROCESSING_ID,
                buildNotification("KI-Modell wird geladen …", 0, true)
            )
            processingJob = serviceScope.launch {
                runModelPreload(model)
                processingJob = null
            }
            return START_REDELIVER_INTENT
        }
        if (intent?.action == ACTION_START_SELF_TEST) {
            val model = AiModel.fromId(intent.getStringExtra(EXTRA_MODEL_ID))
            val prompt = intent.getStringExtra(EXTRA_TEST_PROMPT).orEmpty()
            stopRequested.set(false)
            diagnostics.clear()
            AiPostProcessingCoordinator.update(AiPostProcessingState.SelfTestStarting(model))
            startForeground(
                TranscriptNotifications.AI_PROCESSING_ID,
                buildNotification("KI-Test wird vorbereitet …", 0, true)
            )
            processingJob = serviceScope.launch {
                runSelfTest(model, prompt)
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
            TranscriptNotifications.AI_PROCESSING_ID,
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

    private fun runModelPreload(model: AiModel) {
        try {
            val modelFile = File(File(filesDir, "ai-models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }
            val configuration = guardedConfiguration(modelFile, model)
            activeConfiguration = configuration
            val alreadyLoaded = AiEngineSessionManager.isLoaded(model, modelFile, configuration)
            addDiagnostic("Vorladen: ausgewähltes Modell ${model.modelLabel}.")
            addDiagnostic(
                if (alreadyLoaded) {
                    "Passende Modell- und Laufzeitkonfiguration ist bereits im RAM."
                } else {
                    "Passende Modell- und Laufzeitkonfiguration wird neu geladen."
                }
            )
            AiPostProcessingCoordinator.update(
                AiPostProcessingState.ModelPreloadRunning(
                    model = model,
                    status = if (alreadyLoaded) {
                        "KI-Modell ist geladen."
                    } else {
                        "KI-Modell wird geladen …"
                    },
                    activityDetail = if (alreadyLoaded) {
                        "${model.modelLabel} wird aus dem RAM wiederverwendet."
                    } else {
                        "${model.modelLabel} wird für die KI-Diagnose vorbereitet."
                    },
                    diagnostics = diagnostics.toList()
                )
            )
            val session = AiEngineSessionManager.withModel(
                model,
                modelFile,
                configuration
            ) { engine, _ -> engine.runtimeReport() }
            val report = session.value
            addDiagnostic(
                if (session.info.modelAlreadyLoaded) {
                    "KI-Modell war bereits im RAM; kein erneutes Laden."
                } else {
                    "KI-Modell in ${session.info.modelLoadMs} ms geladen und bleibt im RAM."
                }
            )
            addDiagnostic(
                "Backend angefordert: ${report.requestedBackend} · aktiv: ${report.activeBackend} · ${report.activeCpuBackend}."
            )
            if (session.info.cpuFallbackUsed || report.fallbackUsed) {
                addDiagnostic("Automatischer CPU-Fallback wurde für die geladene Sitzung verwendet.")
            }
            AiPostProcessingCoordinator.update(
                AiPostProcessingState.ModelPreloadCompleted(
                    model = model,
                    metrics = AiModelPreloadMetrics(
                        modelAlreadyLoaded = session.info.modelAlreadyLoaded,
                        modelLoadMs = session.info.modelLoadMs,
                        cpuFallbackUsed = session.info.cpuFallbackUsed || report.fallbackUsed
                    ),
                    diagnostics = diagnostics.toList()
                )
            )
            finishWithNotification("KI-Modell geladen", "${model.modelLabel} ist einsatzbereit.")
        } catch (throwable: Throwable) {
            if (!stopRequested.get() && throwable !is CancellationException) {
                val message = throwable.localizedMessage ?: "Das KI-Modell konnte nicht geladen werden."
                addDiagnostic("Vorladefehler: $message")
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.ModelPreloadFailed(
                        model = model,
                        message = message,
                        diagnostics = diagnostics.toList()
                    )
                )
                finishWithNotification("KI-Modell nicht geladen", message)
            }
        }
    }

    private fun runSelfTest(model: AiModel, prompt: String) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            check(prompt.isNotBlank()) { "Bitte zuerst eine Frage oder Aufgabe eingeben." }
            val modelFile = File(File(filesDir, "ai-models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }
            val configuration = guardedConfiguration(modelFile, model)
            activeConfiguration = configuration
            val modelAlreadyLoaded = AiEngineSessionManager.isLoaded(
                model,
                modelFile,
                configuration
            )
            val conversationContinued = AiEngineSessionManager.hasTestConversation(
                model,
                modelFile,
                configuration
            )
            addDiagnostic(
                if (conversationContinued) {
                    "KI-Unterhaltung wird mit vorhandenem Gesprächskontext fortgeführt."
                } else if (modelAlreadyLoaded) {
                    "KI-Test: ${model.modelLabel} ist im Arbeitsspeicher; eine neue Unterhaltung beginnt."
                } else {
                    "KI-Test: ${model.modelLabel} wird lokal geladen; eine neue Unterhaltung beginnt."
                }
            )
            AiPostProcessingCoordinator.update(
                AiPostProcessingState.SelfTestRunning(
                    model = model,
                    status = if (modelAlreadyLoaded) {
                        "Geladenes KI-Modell wird verwendet …"
                    } else {
                        "KI-Modell wird geladen …"
                    },
                    activityDetail = if (modelAlreadyLoaded) {
                        "${model.modelLabel} bleibt im RAM und ist sofort ansprechbar."
                    } else {
                        "${model.modelLabel} wird für den freien KI-Test vorbereitet."
                    },
                    diagnostics = diagnostics.toList()
                )
            )
            val sessionResult = AiEngineSessionManager.withModel(
                model,
                modelFile,
                configuration
            ) { engine, sessionInfo ->
                addDiagnostic(
                    if (sessionInfo.modelAlreadyLoaded) {
                        "Bereits geladenes KI-Modell wird wiederverwendet."
                    } else {
                        "KI-Modell in ${sessionInfo.modelLoadMs} ms geladen und bleibt im RAM."
                    }
                )
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.SelfTestRunning(
                        model = model,
                        status = "Anfrage wird verarbeitet …",
                        activityDetail = "Thinking ist technisch deaktiviert · Eingabe: ${prompt.length} Zeichen.",
                        diagnostics = diagnostics.toList()
                    )
                )
                val generation = engine.generateTest(prompt)
                val report = engine.runtimeReport()
                addDiagnostic(
                    "Backend angefordert: ${report.requestedBackend} · aktiv: ${report.activeBackend} · ${report.activeCpuBackend}."
                )
                generation
            }
            val generation = sessionResult.value
            if (sessionResult.info.cpuFallbackUsed) {
                addDiagnostic("Vulkan-Gerät verloren; Anfrage einmalig vollständig über CPU wiederholt.")
            }
            addDiagnostic(
                "Erstes Antwort-Token nach ${generation.metrics.timeToFirstTokenMs} ms."
            )
            addDiagnostic(
                "Antwort vollständig: ${generation.metrics.generatedTokens} Tokens, Ende: ${generation.metrics.finishReason}."
            )
            val metrics = AiSelfTestMetrics(
                modelAlreadyLoaded = sessionResult.info.modelAlreadyLoaded,
                conversationContinued = conversationContinued,
                modelLoadMs = sessionResult.info.modelLoadMs,
                promptTokens = generation.metrics.promptTokens,
                generatedTokens = generation.metrics.generatedTokens,
                promptProcessingMs = generation.metrics.promptProcessingMs,
                timeToFirstTokenMs = generation.metrics.timeToFirstTokenMs,
                answerGenerationMs = generation.metrics.answerGenerationMs,
                totalMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                finishReason = generation.metrics.finishReason,
                thinkingDisabled = generation.metrics.thinkingDisabled
            )
            AiPostProcessingCoordinator.update(
                AiPostProcessingState.SelfTestCompleted(
                    model = model,
                    response = generation.text,
                    metrics = metrics,
                    diagnostics = diagnostics.toList()
                )
            )
            finishWithNotification(
                "KI-Test erfolgreich",
                "Antwort vollständig empfangen: ${generation.text.length} Zeichen."
            )
        } catch (throwable: Throwable) {
            if (!stopRequested.get() && throwable !is CancellationException) {
                val message = throwable.localizedMessage ?: "Der KI-Test ist fehlgeschlagen."
                addDiagnostic("KI-Test-Fehler: $message")
                AiPostProcessingCoordinator.update(
                    AiPostProcessingState.SelfTestFailed(
                        model = model,
                        message = message,
                        diagnostics = diagnostics.toList()
                    )
                )
                finishWithNotification("KI-Test fehlgeschlagen", message)
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
            val configuration = guardedConfiguration(modelFile, model)
            activeConfiguration = configuration
            addDiagnostic("Whisper-Speicher wurde freigegeben.")
            addDiagnostic(
                if (AiEngineSessionManager.isLoaded(model, modelFile, configuration)) {
                    "${model.modelLabel} ist bereits im Arbeitsspeicher."
                } else {
                    "${model.modelLabel} wird lokal geladen."
                }
            )

            AiEngineSessionManager.withModel(model, modelFile, configuration) { engine, sessionInfo ->
                addDiagnostic(
                    if (sessionInfo.modelAlreadyLoaded) {
                        "${model.modelLabel} wird aus dem RAM wiederverwendet."
                    } else {
                        "${model.modelLabel} ist nach ${sessionInfo.modelLoadMs} ms bereit und bleibt im RAM."
                    }
                )
                addDiagnostic("Thinking ist über die Qwen-Chatvorlage technisch deaktiviert.")
                var correctedSegments = initialRequest.segments
                val groups = targetGroups(initialRequest)
                check(groups.isNotEmpty()) { "Für die KI-Nachbearbeitung wurde kein Text gefunden." }
                var checkedSegments = 0
                var appliedCorrections = 0
                var rejectedCorrections = 0
                var latestTrace: AiCorrectionTrace? = null
                val totalSegments = groups.sumOf(List<Int>::size)

                groups.forEachIndexed { groupIndex, indexes ->
                    if (groupIndex < initialRequest.nextGroupIndex) return@forEachIndexed
                    ensureContinues()
                    val rangeStartMs = correctedSegments[indexes.first()].startMs
                    val rangeEndMs = correctedSegments[indexes.last()].endMs
                    val indexed = indexes.map { index ->
                        IndexedTranscriptSegment(index, correctedSegments[index])
                    }
                    val label = "${formatClock(rangeStartMs)}–${formatClock(rangeEndMs)}"
                    addDiagnostic("Bereich $label: gemeinsamer Kontext mit ${indexes.size} Segmenten wird einmal geladen.")
                    publishRunning(
                        request = initialRequest,
                        model = model,
                        groupNumber = checkedSegments,
                        groupCount = totalSegments,
                        correctedSegments = correctedSegments,
                        status = "KI liest Gesprächskontext …",
                        activity = "Bereich $label wird einmal als Zusammenhang vorbereitet.",
                        checkedSegments = checkedSegments,
                        proposedCorrections = appliedCorrections,
                        rejectedCorrections = rejectedCorrections,
                        latestTrace = latestTrace
                    )
                    engine.prepareCorrectionContext(buildCorrectionContext(indexed))
                    addDiagnostic("Gemeinsamer Kontext bereit. Zielsegmente werden einzeln geprüft.")

                    indexes.forEach { index ->
                        ensureContinues()
                        val target = IndexedTranscriptSegment(index, correctedSegments[index])
                        val response = engine.correctSegment(
                            prompt = buildCorrectionTarget(target),
                            maximumOutputTokens = maximumCorrectionTokens(target.segment.text)
                        )
                        val parsed = parseCorrectionResult(response, target.segment.text)
                        if (parsed.changed) {
                            correctedSegments = applyCorrection(correctedSegments, index, parsed.text)
                            appliedCorrections++
                        }
                        if (parsed.retainedOriginal) rejectedCorrections++
                        checkedSegments++
                        latestTrace = AiCorrectionTrace(
                            segmentNumber = index + 1,
                            originalText = target.segment.text,
                            rawResponse = response,
                            resultText = parsed.text,
                            decision = when {
                                parsed.retainedOriginal -> "Leeres oder nicht lesbares Ergebnis: Original beibehalten."
                                parsed.changed -> "Nicht leeres Ergebnis ohne weitere Inhaltsprüfung als Vorschlag übernommen."
                                else -> "Nicht leeres Ergebnis angenommen; Text blieb unverändert."
                            }
                        )
                        addDiagnostic(
                            "Segment ${index + 1}: ${if (parsed.retainedOriginal) "Original beibehalten" else if (parsed.changed) "KI-Vorschlag übernommen" else "unverändert"}."
                        )
                        publishRunning(
                            request = initialRequest,
                            model = model,
                            groupNumber = checkedSegments,
                            groupCount = totalSegments,
                            correctedSegments = correctedSegments,
                            status = "KI-Nachbearbeitung läuft …",
                            activity = "$checkedSegments von $totalSegments Segmenten geprüft, $appliedCorrections Korrekturen erkannt.",
                            checkedSegments = checkedSegments,
                            proposedCorrections = appliedCorrections,
                            rejectedCorrections = rejectedCorrections,
                            latestTrace = latestTrace
                        )
                    }
                    requestStore.write(
                        initialRequest.copy(
                            segments = correctedSegments,
                            nextGroupIndex = groupIndex + 1
                        )
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
                        rejectedCorrections = rejectedCorrections,
                        latestTrace = latestTrace
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
        rejectedCorrections: Int,
        latestTrace: AiCorrectionTrace?
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
                rejectedCorrections = rejectedCorrections,
                latestTrace = latestTrace
            )
        )
        getSystemService(NotificationManager::class.java).notify(
            TranscriptNotifications.AI_PROCESSING_ID,
            buildNotification(activity, (progress * 100).toInt(), false)
        )
    }

    private fun addDiagnostic(message: String) {
        if (diagnostics.size >= 10) diagnostics.removeFirst()
        diagnostics.addLast("${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} · $message")
    }

    private fun ensureContinues() {
        if (stopRequested.get()) throw CancellationException("KI-Nachbearbeitung beendet.")
        val configuration = activeConfiguration ?: return
        val thermalStatus = AiHardwareProbe.read(this).thermalStatus
        check(thermalStatus < configuration.thermalStopStatus) {
            "Wärmeschutz hat die KI bei Status ${thermalStatusLabel(thermalStatus)} beendet."
        }
    }

    private fun guardedConfiguration(
        modelFile: File,
        model: AiModel
    ): LocalAiConfiguration {
        val stored = AiPerformancePreferences(this).load(model)
        val hardware = AiHardwareProbe.read(this)
        check(hardware.thermalStatus < stored.thermalStopStatus) {
            "Das Gerät ist für den KI-Start zu warm (${thermalStatusLabel(hardware.thermalStatus)})."
        }
        val effective = if (hardware.thermalStatus >= stored.thermalThrottleStatus) {
            if (stored.coolingPauseSeconds > 0) {
                addDiagnostic("Wärmeschutz: ${stored.coolingPauseSeconds} s Abkühlpause vor dem KI-Start.")
                SystemClock.sleep(stored.coolingPauseSeconds * 1_000L)
                if (stopRequested.get()) throw CancellationException("KI-Nachbearbeitung beendet.")
            }
            val reducedGpuLayers = if (stored.backend == LocalAiBackend.HYBRID) {
                (stored.gpuLayers - stored.gpuLayersReducedPerStep).coerceAtLeast(0)
            } else {
                0
            }
            val reducedBackend = if (reducedGpuLayers > 0) LocalAiBackend.HYBRID else LocalAiBackend.CPU
            addDiagnostic(
                "Wärmeschutz aktiv: CPU-Threads reduziert; GPU-Schichten ${stored.gpuLayers} → $reducedGpuLayers."
            )
            stored.copy(
                generationThreads = stored.throttledThreads,
                promptThreads = stored.throttledThreads,
                backend = reducedBackend,
                gpuLayers = reducedGpuLayers,
                gpuLayerPercent = 0
            ).normalized()
        } else {
            stored
        }
        if (hardware.thermalStatus >= stored.thermalWarningStatus) {
            addDiagnostic("Wärmehinweis: ${thermalStatusLabel(hardware.thermalStatus)}.")
        }
        AiHardwareProbe.checkMemory(this, modelFile, effective)
        return effective
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
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
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
            .setSmallIcon(TranscriptNotifications.SMALL_ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(TranscriptNotifications.AI_PROCESSING_ID, notification)
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
        fun preloadModel(context: Context, model: AiModel) {
            val intent = Intent(context, AiPostProcessingService::class.java).apply {
                action = ACTION_PRELOAD_MODEL
                putExtra(EXTRA_MODEL_ID, model.id)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startSelfTest(context: Context, model: AiModel, prompt: String) {
            val intent = Intent(context, AiPostProcessingService::class.java).apply {
                action = ACTION_START_SELF_TEST
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_TEST_PROMPT, prompt)
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

private fun formatClock(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}
