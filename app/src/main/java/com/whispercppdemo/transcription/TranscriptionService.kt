package de.matthiasennen.transcript.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperVadContext
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.R
import de.matthiasennen.transcript.media.AudioDecoderOutputOverflowException
import de.matthiasennen.transcript.media.AudioDecoderStallException
import de.matthiasennen.transcript.media.decodeAudioChunk
import de.matthiasennen.transcript.media.inspectAudioTrack
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.WhisperSettings
import de.matthiasennen.transcript.ui.main.WhisperSettingsPreferences
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import de.matthiasennen.transcript.download.SileroVadModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val CHANNEL_ID = "offline_transcription"
private const val NOTIFICATION_ID = 2108
private const val ACTION_START = "de.matthiasennen.transcript.START_TRANSCRIPTION"
private const val ACTION_CANCEL = "de.matthiasennen.transcript.CANCEL_TRANSCRIPTION"
private const val EXTRA_URI = "uri"
private const val EXTRA_FILE_NAME = "file_name"
private const val EXTRA_MODEL_ID = "model_id"
private const val EXTRA_LANGUAGE = "language"
private const val EXTRA_SETTINGS_SIGNATURE = "whisper_settings_signature"
private const val CHECKPOINT_FILE_NAME = "active-transcription.bin"

class TranscriptionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val userCancellationRequested = AtomicBoolean(false)
    private var transcriptionJob: Job? = null
    private var activeWhisperContext: WhisperContext? = null
    private var activeVadModelPath: String? = null
    private lateinit var checkpointStore: TranscriptionCheckpointStore
    private val diagnostics = ArrayDeque<String>()
    private var startedAtEpochMs = 0L
    private var lastUiPublishAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        checkpointStore = TranscriptionCheckpointStore(File(filesDir, CHECKPOINT_FILE_NAME))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            requestUserCancellation()
            return START_NOT_STICKY
        }
        if (transcriptionJob?.isActive == true) return START_REDELIVER_INTENT

        val request = intent?.toRequest() ?: checkpointStore.read()?.request
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopRequested.set(false)
        userCancellationRequested.set(false)
        TranscriptionCoordinator.update(TranscriptionState.Starting(request.fileName))
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Transkription wird vorbereitet …", 0, indeterminate = true)
        )
        transcriptionJob = serviceScope.launch {
            runTranscription(request)
            transcriptionJob = null
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested.set(true)
        activeWhisperContext?.requestAbort()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runTranscription(request: TranscriptionRequest) {
        var latestCheckpoint: TranscriptionCheckpoint? = null
        try {
            val uri = Uri.parse(request.uri)
            val model = WhisperModel.fromId(request.modelId)
            val modelFile = File(File(filesDir, "models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }

            addDiagnostic("Audiospur wird geprüft.")
            updateNotification("Audiospur wird geprüft …", 0, indeterminate = true)
            val audioInfo = inspectAudioTrack(this, uri)
            val whisperSettings = WhisperSettingsPreferences(this).load()
            val vadFile = File(File(filesDir, "vad-models"), SileroVadModel.fileName)
            val installedVadModelPath = vadFile.absolutePath.takeIf {
                vadFile.isFile && vadFile.length() == SileroVadModel.expectedBytes
            }
            val saved = checkpointStore.read()?.takeIf {
                it.isCompatibleWith(request, audioInfo.durationMs) &&
                    request.settingsSignature == whisperSettings.normalized().toString() &&
                    it.hasMeaningfulProgress()
            }
            if (saved == null) checkpointStore.clear()
            var checkpoint = saved ?: TranscriptionCheckpoint(
                request = request,
                durationMs = audioInfo.durationMs,
                nextStartMs = 0L,
                detectedLanguage = null,
                startedAtEpochMs = System.currentTimeMillis(),
                segments = emptyList()
            ).also(checkpointStore::write)
            latestCheckpoint = checkpoint
            startedAtEpochMs = checkpoint.startedAtEpochMs
            lastUiPublishAtMs = 0L

            val resumed = checkpoint.nextStartMs > 0L
            addDiagnostic(
                if (resumed) {
                    "Zwischenstand bei ${formatClock(checkpoint.nextStartMs / 1_000L)} fortgesetzt."
                } else {
                    "Audiospur: ${formatClock(audioInfo.durationMs / 1_000L)}, " +
                        "${audioInfo.sourceSampleRate} Hz, ${audioInfo.sourceChannelCount} Kanal/Kanäle."
                }
            )

            val sections = planTranscriptionSections(
                durationMs = audioInfo.durationMs,
                startAtMs = checkpoint.nextStartMs,
                sectionDurationMs = whisperSettings.sectionMinutes * 60_000L
            ).toMutableList()
            var sectionIndex = 0
            val previouslyCompletedSections = completedSectionCount(
                checkpoint.nextStartMs,
                whisperSettings.sectionMinutes * 60_000L
            )
            var sectionCount = sections.size + previouslyCompletedSections
            activeVadModelPath = resolveVadModelPath(
                request = request,
                model = model,
                uri = uri,
                audioDurationMs = audioInfo.durationMs,
                checkpoint = checkpoint,
                settings = whisperSettings,
                installedModelPath = installedVadModelPath
            )
            activeWhisperContext = WhisperContext.createContextFromFile(
                modelFile.absolutePath,
                useGpu = whisperSettings.toNativeConfiguration().useGpu
            )
            val runtimeBackend = checkNotNull(activeWhisperContext).runtimeBackend
            addDiagnostic(
                "${model.modelLabel} wurde mit Backend ${runtimeBackend.name} geladen." +
                    if (runtimeBackend.fellBackToCpu) " Vulkan war nicht nutzbar; CPU-Rückfall aktiv." else ""
            )

            while (sectionIndex < sections.size) {
                ensureContinues()
                val section = sections[sectionIndex]
                val absoluteSectionNumber = previouslyCompletedSections + sectionIndex + 1
                try {
                    checkpoint = processSection(
                        request = request,
                        model = model,
                        uri = uri,
                        section = section,
                        sectionNumber = absoluteSectionNumber.coerceAtMost(sectionCount),
                        sectionCount = sectionCount,
                        checkpoint = checkpoint,
                        whisperSettings = whisperSettings
                    )
                    latestCheckpoint = checkpoint
                    sectionIndex++
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException || stopRequested.get()) throw throwable
                    if (section.usedFallbackSize ||
                        throwable is AudioDecoderOutputOverflowException
                    ) {
                        throw throwable
                    }

                    val fallbackSections = splitIntoFallbackSections(section, audioInfo.durationMs)
                    check(fallbackSections.isNotEmpty()) { "Der Abschnitt konnte nicht aufgeteilt werden." }
                    sections.removeAt(sectionIndex)
                    sections.addAll(sectionIndex, fallbackSections)
                    sectionCount += fallbackSections.size - 1
                    addDiagnostic(
                        "Abschnitt ab ${formatClock(section.mainStartMs / 1_000L)} wird nach einem " +
                            "Fehler mit 2,5 Minuten wiederholt."
                    )
                    publishRunning(
                        request, model, section, absoluteSectionNumber.coerceAtMost(sectionCount),
                        sectionCount, checkpoint, 0f,
                        "Audioaufbereitung wird mit 2,5-Minuten-Sicherheitsabschnitten fortgesetzt",
                        "Der ursprüngliche Abschnitt ist endgültig fehlgeschlagen; " +
                            "die begrenzte Sicherheitsaufteilung wird einmal verwendet."
                    )
                    System.gc()
                }
            }

            ensureContinues()
            checkpointStore.clear()
            val elapsedSeconds = ((System.currentTimeMillis() - checkpoint.startedAtEpochMs) / 1_000L)
                .coerceAtLeast(0L)
            val completed = TranscriptionState.Completed(
                fileName = request.fileName,
                model = model,
                segments = checkpoint.segments,
                detectedLanguage = checkpoint.detectedLanguage.orEmpty(),
                transcriptionDurationSeconds = elapsedSeconds
            )
            // The local correction model can require several additional GB.
            // Release Whisper before announcing completion so the ViewModel can
            // safely start automatic AI post-processing without both models
            // overlapping in memory.
            val completedWhisperContext = activeWhisperContext
            activeWhisperContext = null
            completedWhisperContext?.release()
            System.gc()
            TranscriptionCoordinator.update(completed)
            finishWithNotification(
                title = "Transkription abgeschlossen",
                text = "${checkpoint.segments.size} Textabschnitte erkannt."
            )
        } catch (throwable: Throwable) {
            val checkpoint = latestCheckpoint ?: checkpointStore.read()
            if (userCancellationRequested.get()) {
                checkpointStore.clear()
                TranscriptionCoordinator.update(TranscriptionState.Cancelled(request.fileName))
                finishWithNotification("Transkription abgebrochen", request.fileName)
            } else if (!stopRequested.get()) {
                val canResume = checkpoint?.hasMeaningfulProgress() == true
                if (!canResume) checkpointStore.clear()
                val message = userFacingError(throwable, canResume)
                TranscriptionCoordinator.update(
                    TranscriptionState.Failed(
                        fileName = request.fileName,
                        message = message,
                        canResume = canResume,
                        committedSegments = checkpoint?.segments.orEmpty()
                    )
                )
                finishWithNotification("Transkription unterbrochen", message)
            }
        } finally {
            activeVadModelPath = null
            val context = activeWhisperContext
            activeWhisperContext = null
            runCatching { context?.release() }
        }
    }

    private suspend fun processSection(
        request: TranscriptionRequest,
        model: WhisperModel,
        uri: Uri,
        section: TranscriptionSection,
        sectionNumber: Int,
        sectionCount: Int,
        checkpoint: TranscriptionCheckpoint,
        whisperSettings: WhisperSettings
    ): TranscriptionCheckpoint {
        val fallbackLabel = if (section.usedFallbackSize) " · Sicherheitsgröße 2,5 min" else ""
        publishRunning(
            request,
            model,
            section,
            sectionNumber,
            sectionCount,
            checkpoint,
            progressWithinSection = 0f,
            status = "Abschnitt $sectionNumber von $sectionCount wird dekodiert$fallbackLabel",
            detail = "Position ${formatClock(section.mainStartMs / 1_000L)} der Audiodatei."
        )

        val chunk = decodeAudioChunk(
            context = this,
            uri = uri,
            startMs = section.decodeStartMs,
            endMs = section.decodeEndMs,
            shouldCancel = stopRequested::get,
            onDecoderRestart = { stall ->
                addDiagnostic(
                    "Decoder-Stillstand in Abschnitt $sectionNumber erkannt; " +
                        "Decoder wird genau einmal vollständig neu gestartet. ${stall.message}"
                )
                publishRunning(
                    request, model, section, sectionNumber, sectionCount, checkpoint,
                    progressWithinSection = 0f,
                    status = "Decoder-Neustart · Abschnitt $sectionNumber von $sectionCount",
                    detail = "Die Audioaufbereitung war ohne Fortschritt und wird einmal sauber neu gestartet."
                )
            }
        ) { decoderProgress ->
            publishRunning(
                request,
                model,
                section,
                sectionNumber,
                sectionCount,
                checkpoint,
                progressWithinSection = decoderProgress * 0.15f,
                status = "Abschnitt $sectionNumber von $sectionCount wird dekodiert",
                detail = "Audio wird speicherschonend auf 16 kHz vorbereitet."
            )
        }
        ensureContinues()
        addDiagnostic(
            "Abschnitt $sectionNumber dekodiert: ${chunk.samples.size} 16-kHz-Samples aus " +
                "${chunk.mimeType}, ${chunk.sourceSampleRate} Hz, " +
                "${chunk.sourceChannelCount} Kanal/Kanäle."
        )
        if (chunk.discardedTrailingSamples > 0) {
            addDiagnostic(
                "Decoder-Überhang sicher entfernt: " +
                    "${chunk.discardedTrailingSamples} zusätzliche Samples."
            )
        }

        val language = if (request.language == "auto") {
            checkpoint.detectedLanguage?.takeIf(String::isNotBlank) ?: "auto"
        } else {
            request.language
        }
        val vadModelPath = activeVadModelPath
        if (vadModelPath != null) addDiagnostic("Abschnitt $sectionNumber wird mit Silero VAD verarbeitet.")
        val result = runCatching {
            transcribeChunk(request, model, section, sectionNumber, sectionCount, checkpoint, chunk.samples,
                language, whisperSettings, vadModelPath)
        }.recoverCatching { throwable ->
            if (vadModelPath == null || throwable is CancellationException || stopRequested.get()) throw throwable
            activeVadModelPath = null
            addDiagnostic("VAD-Fehler: Abschnitt $sectionNumber wird automatisch ohne VAD wiederholt.")
            publishRunning(request, model, section, sectionNumber, sectionCount, checkpoint, 0.15f,
                "VAD-Fehler · Abschnitt $sectionNumber wird ohne VAD fortgesetzt",
                "Whisper erhält den vollständig dekodierten Audioabschnitt.")
            transcribeChunk(request, model, section, sectionNumber, sectionCount, checkpoint, chunk.samples,
                language, whisperSettings, null)
        }.getOrThrow()
        ensureContinues()

        val absoluteSegments = selectAbsoluteSegments(
            localSegments = result.segments,
            section = section,
            totalDurationMs = checkpoint.durationMs
        )
        val detectedLanguage = checkpoint.detectedLanguage ?: result.detectedLanguage.takeIf {
            it.isNotBlank() && result.segments.any { segment -> segment.text.isNotBlank() }
        }
        val updated = checkpoint.copy(
            nextStartMs = section.mainEndMs,
            detectedLanguage = detectedLanguage,
            segments = mergeCommittedSegments(checkpoint.segments, absoluteSegments)
        )
        checkpointStore.write(updated)
        addDiagnostic("Abschnitt $sectionNumber gesichert: ${absoluteSegments.size} neue Textabschnitte.")
        publishRunning(request, model, section, sectionNumber, sectionCount, updated, 1f,
            "Abschnitt $sectionNumber von $sectionCount abgeschlossen und gesichert",
            "Nächste Position: ${formatClock(updated.nextStartMs / 1_000L)}.")
        return updated
    }

    private suspend fun transcribeChunk(
        request: TranscriptionRequest,
        model: WhisperModel,
        section: TranscriptionSection,
        sectionNumber: Int,
        sectionCount: Int,
        checkpoint: TranscriptionCheckpoint,
        samples: FloatArray,
        language: String,
        settings: WhisperSettings,
        vadModelPath: String?
    ) = checkNotNull(activeWhisperContext).transcribeSegments(
            data = samples,
            language = language,
            configuration = settings.toNativeConfiguration(vadModelPath),
            shouldCancel = stopRequested::get
        ) { nativePercent ->
            publishRunning(
                request,
                model,
                section,
                sectionNumber,
                sectionCount,
                checkpoint,
                progressWithinSection = 0.15f + nativePercent / 100f * 0.85f,
                status = "Abschnitt $sectionNumber von $sectionCount wird transkribiert · $nativePercent %",
                detail = if (vadModelPath != null) {
                    "Silero VAD filtert klare Pausen; Whisper verarbeitet anschließend den Sprachbereich."
                } else {
                    "Whisper verarbeitet ${formatClock(section.mainStartMs / 1_000L)} bis " +
                        "${formatClock(section.mainEndMs / 1_000L)}."
                }
            )
        }

    private suspend fun resolveVadModelPath(
        request: TranscriptionRequest,
        model: WhisperModel,
        uri: Uri,
        audioDurationMs: Long,
        checkpoint: TranscriptionCheckpoint,
        settings: WhisperSettings,
        installedModelPath: String?
    ): String? {
        if (settings.vadMode == WhisperVadMode.OFF) {
            addDiagnostic("Silero VAD ist ausgeschaltet.")
            return null
        }
        if (installedModelPath == null) {
            addDiagnostic("Silero VAD ist nicht installiert; Whisper arbeitet ohne VAD.")
            return null
        }
        if (settings.vadMode == WhisperVadMode.ON) {
            addDiagnostic("Silero VAD ist eingeschaltet und wird verwendet.")
            return installedModelPath
        }

        val analyzer = VadAutomaticAnalyzer()
        val analysisSections = planTranscriptionSections(audioDurationMs, 0L, settings.sectionMinutes * 60_000L)
        addDiagnostic("VAD-Automatik analysiert die Audiospur speicherschonend mit Silero.")
        val vadContext = runCatching {
            WhisperVadContext.createContextFromFile(installedModelPath)
        }.getOrElse { failure ->
            addDiagnostic("VAD-Automatik konnte Silero nicht laden; Whisper arbeitet ohne VAD: ${failure.message}")
            return null
        }
        val decision = try {
            analysisSections.forEachIndexed { index, section ->
                ensureContinues()
                publishRunning(request, model, section, index + 1, analysisSections.size, checkpoint,
                    0f, "VAD-Automatik analysiert Abschnitt ${index + 1} von ${analysisSections.size}",
                    "Silero prüft reale Sprachbereiche; die Datei bleibt abschnittsweise im Speicher.")
                val chunk = decodeAudioChunk(
                    this,
                    uri,
                    section.mainStartMs,
                    section.mainEndMs,
                    stopRequested::get,
                    onDecoderRestart = { stall ->
                        addDiagnostic(
                            "Decoder-Stillstand während der VAD-Analyse; Decoder wird genau " +
                                "einmal neu gestartet. ${stall.message}"
                        )
                        publishRunning(
                            request, model, section, index + 1, analysisSections.size, checkpoint,
                            0f, "VAD-Automatik: Decoder wird neu gestartet",
                            "Die vollständige VAD-Analyse wird für diesen Abschnitt einmal neu begonnen."
                        )
                    }
                ) { progress ->
                    publishRunning(request, model, section, index + 1, analysisSections.size, checkpoint,
                        progress, "VAD-Automatik analysiert Abschnitt ${index + 1} von ${analysisSections.size}",
                        "Silero bewertet Sprachanteil, Pausenlänge und Zerstückelungsrisiko.")
                }
                ensureContinues()
                val speechSegments = vadContext.detectSegments(
                    samples = chunk.samples,
                    threshold = settings.vadThresholdPercent / 100f,
                    minimumSpeechDurationMs = settings.vadMinSpeechDurationMs,
                    minimumSilenceDurationMs = settings.vadMinSilenceDurationMs,
                    maximumSpeechDurationSeconds = settings.vadMaxSpeechDurationSeconds.toFloat(),
                    speechPadMs = settings.vadSpeechPadMs,
                    overlapSeconds = settings.vadOverlapMs / 1_000f
                )
                analyzer.add(
                    chunkDurationMs = chunk.samples.size * 1_000L / 16_000L,
                    segments = speechSegments,
                    chunkSampleCount = chunk.samples.size
                )
            }
            analyzer.decide()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            addDiagnostic(
                "VAD-Automatik fehlgeschlagen; Whisper arbeitet sicher ohne VAD: " +
                    (failure.localizedMessage ?: failure.javaClass.simpleName)
            )
            analysisSections.lastOrNull()?.let { finalSection ->
                publishRunning(request, model, finalSection, analysisSections.size, analysisSections.size,
                    checkpoint, 1f, "VAD-Automatik: Whisper arbeitet ohne VAD",
                    "Silero-Analyse war nicht zuverlässig; der vollständige Ton bleibt erhalten.")
            }
            return null
        } finally {
            vadContext.release()
        }
        val summary = "${decision.silencePercent} % Pause, ${decision.speechPercent} % Sprache, " +
            "${decision.speechSegmentCount} Sprachbereiche, längste Pause ${decision.longestSilenceMs / 1_000.0} s, " +
            "${decision.detectedSpeechSampleCount} von ${decision.analyzedSampleCount} Samples in Sprachbereichen"
        if (decision.useVad) {
            addDiagnostic("VAD-Automatik: VAD wird verwendet ($summary; ${decision.reason}).")
            analysisSections.lastOrNull()?.let { finalSection ->
                publishRunning(request, model, finalSection, analysisSections.size, analysisSections.size,
                    checkpoint, 1f, "VAD-Automatik: VAD wird verwendet", "$summary · ${decision.reason}.")
            }
            updateNotification("VAD-Automatik: VAD wird verwendet", 0, true)
            return installedModelPath
        }
        addDiagnostic("VAD-Automatik: ohne VAD ($summary; ${decision.reason}).")
        analysisSections.lastOrNull()?.let { finalSection ->
            publishRunning(request, model, finalSection, analysisSections.size, analysisSections.size,
                checkpoint, 1f, "VAD-Automatik: Whisper arbeitet ohne VAD", "$summary · ${decision.reason}.")
        }
        updateNotification("VAD-Automatik: Whisper arbeitet ohne VAD", 0, true)
        return null
    }

    private fun publishRunning(
        request: TranscriptionRequest,
        model: WhisperModel,
        section: TranscriptionSection,
        sectionNumber: Int,
        sectionCount: Int,
        checkpoint: TranscriptionCheckpoint,
        progressWithinSection: Float,
        status: String,
        detail: String
    ) {
        val now = SystemClock.elapsedRealtime()
        val mustPublish = progressWithinSection <= 0f || progressWithinSection >= 1f ||
            now - lastUiPublishAtMs >= 250L
        if (!mustPublish) return
        lastUiPublishAtMs = now
        val completedMs = section.mainStartMs +
            (section.mainDurationMs * progressWithinSection.coerceIn(0f, 1f)).toLong()
        val progress = (completedMs.toFloat() / checkpoint.durationMs.toFloat()).coerceIn(0f, 1f)
        TranscriptionCoordinator.update(
            TranscriptionState.Running(
                fileName = request.fileName,
                model = model,
                progress = progress,
                sectionNumber = sectionNumber,
                sectionCount = sectionCount,
                startedAtEpochMs = startedAtEpochMs,
                elapsedSeconds = ((System.currentTimeMillis() - startedAtEpochMs) / 1_000L)
                    .coerceAtLeast(0L),
                status = status,
                activityDetail = detail,
                diagnostics = diagnostics.toList(),
                committedSegments = checkpoint.segments,
                detectedLanguage = checkpoint.detectedLanguage
            )
        )
        updateNotification(status, (progress * 100).toInt(), indeterminate = false)
    }

    private fun requestUserCancellation() {
        userCancellationRequested.set(true)
        stopRequested.set(true)
        activeWhisperContext?.requestAbort()
        updateNotification("Transkription wird abgebrochen …", 0, indeterminate = true)
    }

    private fun ensureContinues() {
        if (stopRequested.get()) throw CancellationException("Transkription abgebrochen.")
    }

    private fun addDiagnostic(message: String) {
        val elapsed = if (startedAtEpochMs == 0L) 0L else {
            (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0L) / 1_000L
        }
        if (diagnostics.size >= 12) diagnostics.removeFirst()
        diagnostics.addLast("${formatClock(elapsed)} · $message")
    }

    private fun updateNotification(text: String, percent: Int, indeterminate: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(text, percent, indeterminate)
        )
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, TranscriptionService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Simple Transcript")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .addAction(0, "Abbrechen", cancel)
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
                "Offline-Transkription",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt Fortschritt und Abbruchmöglichkeit langer Transkriptionen."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun Intent.toRequest(): TranscriptionRequest? {
        val uri = getStringExtra(EXTRA_URI) ?: return null
        val fileName = getStringExtra(EXTRA_FILE_NAME) ?: return null
        val modelId = getStringExtra(EXTRA_MODEL_ID) ?: return null
        val language = getStringExtra(EXTRA_LANGUAGE) ?: return null
        val settingsSignature = getStringExtra(EXTRA_SETTINGS_SIGNATURE) ?: return null
        return TranscriptionRequest(uri, fileName, modelId, language, settingsSignature)
    }

    companion object {
        fun start(
            context: Context,
            uri: Uri,
            fileName: String,
            model: WhisperModel,
            language: String,
            settings: WhisperSettings
        ) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MODEL_ID, model.id)
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_SETTINGS_SIGNATURE, settings.normalized().toString())
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, TranscriptionService::class.java).apply { action = ACTION_CANCEL }
            )
        }
    }
}

private fun completedSectionCount(positionMs: Long, sectionDurationMs: Long): Int =
    if (positionMs <= 0L) 0 else {
        ((positionMs + sectionDurationMs - 1L) / sectionDurationMs).toInt()
    }

private fun userFacingError(throwable: Throwable, canResume: Boolean): String {
    val checkpointSuffix = if (canResume) " Der Zwischenstand bleibt erhalten." else ""
    return when (throwable) {
        is AudioDecoderOutputOverflowException ->
            "Der Audiodecoder hat ungewöhnlich viele zusätzliche Audiodaten geliefert." +
                checkpointSuffix
        is AudioDecoderStallException ->
            "Die Audioaufbereitung blieb auch nach einem kontrollierten Decoder-Neustart stehen." +
                checkpointSuffix
        is OutOfMemoryError ->
            "Der Sicherheitsabschnitt war für dieses Gerät noch zu groß.$checkpointSuffix"
        else -> throwable.localizedMessage?.takeIf(String::isNotBlank)?.let { message ->
            if (message.contains("allocate", ignoreCase = true) ||
                message.contains("outofmemory", ignoreCase = true)
            ) {
                "Der Sicherheitsabschnitt war für dieses Gerät noch zu groß.$checkpointSuffix"
            } else {
                message
            }
        } ?: "Die Transkription wurde unerwartet unterbrochen.$checkpointSuffix"
    }
}

private fun formatClock(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3_600L
    val minutes = (safeSeconds % 3_600L) / 60L
    val seconds = safeSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
