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
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperTranscriptionException
import com.whispercpp.whisper.WhisperVadContext
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.R
import de.matthiasennen.transcript.media.AudioDecoderOutputOverflowException
import de.matthiasennen.transcript.media.AudioDecoderStallException
import de.matthiasennen.transcript.media.UnusableAudioSamplesException
import de.matthiasennen.transcript.media.decodeAudioChunk
import de.matthiasennen.transcript.media.inspectAudioTrack
import de.matthiasennen.transcript.media.CachedWaveform
import de.matthiasennen.transcript.media.WaveformCache
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.StatusMessageKind
import de.matthiasennen.transcript.ui.main.WhisperComputeBackend
import de.matthiasennen.transcript.ui.main.WhisperSettings
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val RUNNING_CHANNEL_ID = "offline_transcription"
private const val COMPLETION_CHANNEL_ID = "transcription_results_v1"
private const val NOTIFICATION_PREFERENCES = "transcription_notifications"
private const val LAST_COMPLETED_JOB_ID = "last_completed_job_id"
internal const val TRANSCRIPTION_NOTIFICATION_ID = 2108
internal const val TRANSCRIPTION_COMPLETION_NOTIFICATION_ID = 2109
private const val ACTION_START = "de.matthiasennen.transcript.START_TRANSCRIPTION"
internal const val ACTION_CANCEL_TRANSCRIPTION = "de.matthiasennen.transcript.CANCEL_TRANSCRIPTION"
private const val EXTRA_URI = "uri"
private const val EXTRA_FILE_NAME = "file_name"
private const val EXTRA_JOB_CONFIGURATION = "job_configuration"
private const val EXTRA_JOB_ID = "job_id"
private const val EXTRA_FORCE_CPU = "force_cpu"
private const val CHECKPOINT_FILE_NAME = "active-transcription.bin"
private const val PREPARED_AUDIO_DIRECTORY = "prepared-transcription-audio"

class TranscriptionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopRequested = AtomicBoolean(false)
    private val userCancellationRequested = AtomicBoolean(false)
    private var transcriptionJob: Job? = null
    private var activeWhisperContext: WhisperContext? = null
    private var activeVadModelPath: String? = null
    private var activeVadSummary: VadProcessingSummary? = null
    private lateinit var checkpointStore: TranscriptionCheckpointStore
    private lateinit var preparedAudioStore: PreparedAudioStore
    private val diagnostics = ArrayDeque<String>()
    private var startedAtEpochMs = 0L
    private val uiUpdateThrottle = ProgressUpdateThrottle(500L)
    private val notificationUpdateThrottle = ProgressUpdateThrottle(2_000L)
    private var processStartedAtEpochMs = 0L
    private var forceCpuForRun = false
    private var heartbeatScheduler: ScheduledExecutorService? = null
    private var wakeLockRenewalScheduler: ScheduledExecutorService? = null
    private lateinit var wakeLockGuard: TranscriptionWakeLockGuard
    @Volatile private var activeJobId = ""
    @Volatile private var activePhase = "idle"
    @Volatile private var activeBackend = "none"
    @Volatile private var activeSectionNumber = 0
    @Volatile private var lastProgressAtEpochMs = 0L

    override fun onCreate() {
        super.onCreate()
        processStartedAtEpochMs = System.currentTimeMillis()
        checkpointStore = TranscriptionCheckpointStore(File(filesDir, CHECKPOINT_FILE_NAME))
        preparedAudioStore = PreparedAudioStore(File(filesDir, PREPARED_AUDIO_DIRECTORY))
        val platformWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:offline-transcription"
        ).apply {
            setReferenceCounted(false)
        }
        wakeLockGuard = TranscriptionWakeLockGuard(
            object : WakeLockHandle {
                override val isHeld: Boolean
                    get() = platformWakeLock.isHeld

                override fun acquire(timeoutMs: Long) {
                    platformWakeLock.acquire(timeoutMs)
                }

                override fun release() {
                    platformWakeLock.release()
                }
            }
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSCRIPTION) {
            requestUserCancellation()
            return START_NOT_STICKY
        }
        if (transcriptionJob?.isActive == true) return START_NOT_STICKY

        val request = intent?.toRequest() ?: checkpointStore.read()?.request
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        stopRequested.set(false)
        userCancellationRequested.set(false)
        forceCpuForRun = intent?.getBooleanExtra(EXTRA_FORCE_CPU, false) == true
        publishState(TranscriptionState.Starting(request.fileName))
        startForeground(
            TRANSCRIPTION_NOTIFICATION_ID,
            buildNotification("Transkription wird vorbereitet …", 0, indeterminate = true)
        )
        try {
            acquireTranscriptionWakeLock()
        } catch (throwable: Throwable) {
            val message = "Der Prozessor konnte für die Transkription nicht aktiv gehalten werden."
            publishState(
                TranscriptionState.Failed(
                    fileName = request.fileName,
                    message = message,
                    canResume = checkpointStore.read() != null
                )
            )
            finishWithNotification("Transkription unterbrochen", message)
            return START_NOT_STICKY
        }
        transcriptionJob = serviceScope.launch {
            runTranscription(request)
            transcriptionJob = null
        }
        return START_NOT_STICKY
    }

    private fun acquireTranscriptionWakeLock() {
        wakeLockGuard.acquire()
        wakeLockRenewalScheduler?.shutdownNow()
        wakeLockRenewalScheduler = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleAtFixedRate(
                { runCatching(wakeLockGuard::renew) },
                WAKE_LOCK_RENEW_INTERVAL_MS,
                WAKE_LOCK_RENEW_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun releaseTranscriptionWakeLock() {
        wakeLockRenewalScheduler?.shutdownNow()
        wakeLockRenewalScheduler = null
        runCatching(wakeLockGuard::release)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRequested.set(true)
        activeWhisperContext?.requestAbort()
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = null
        releaseTranscriptionWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runTranscription(request: TranscriptionRequest) {
        var latestCheckpoint: TranscriptionCheckpoint? = null
        try {
            val uri = Uri.parse(request.uri)
            val jobConfiguration = request.configuration.normalized()
            val model = WhisperModel.fromId(jobConfiguration.modelId)
            val modelFile = File(File(filesDir, "models"), model.fileName)
            check(modelFile.isFile && modelFile.length() >= model.minimumBytes) {
                "${model.modelLabel} ist nicht vollständig installiert."
            }

            addDiagnostic("Audiospur wird geprüft.")
            updateNotification("Audiospur wird geprüft …", 0, indeterminate = true)
            val audioInfo = inspectAudioTrack(this, uri)
            addDiagnostic(
                "Eingangsaudio: ${audioInfo.mimeType}, ${audioInfo.sourceSampleRate} Hz, " +
                    "${audioInfo.sourceChannelCount} Kanal/Kanäle, " +
                    "${formatClock(audioInfo.durationMs / 1_000L)}."
            )
            val whisperSettings = if (forceCpuForRun) {
                jobConfiguration.whisperSettings.copy(backend = WhisperComputeBackend.CPU)
            } else {
                jobConfiguration.whisperSettings
            }
            addDiagnostic(
                "Auftragskonfiguration: Abschnitt ${whisperSettings.sectionMinutes} min, " +
                    "VAD ${whisperSettings.vadMode.name}, Sprache ${jobConfiguration.language}."
            )
            val vadFile = File(File(filesDir, "vad-models"), SileroVadModel.fileName)
            val installedVadModelPath = vadFile.absolutePath.takeIf {
                vadFile.isFile && vadFile.length() == SileroVadModel.expectedBytes
            }
            val saved = checkpointStore.read()?.takeIf {
                it.isCompatibleWith(request, audioInfo.durationMs)
            }
            if (saved == null) {
                checkpointStore.clear()
                preparedAudioStore.clear()
            }
            val effectiveRequest = saved?.request ?: request.copy(
                jobId = request.jobId.ifBlank { UUID.randomUUID().toString() }
            )
            startHeartbeat(effectiveRequest.jobId)
            TranscriptionControlReceiver.cancellationFile(this).delete()
            TranscriptionControlReceiver.cpuRetryFile(this).let { retryFile ->
                val retryJob = runCatching { retryFile.readText() }.getOrDefault("")
                if (retryJob.isNotBlank() && retryJob != effectiveRequest.jobId) retryFile.delete()
            }
            var checkpoint = saved ?: TranscriptionCheckpoint(
                request = effectiveRequest,
                durationMs = audioInfo.durationMs,
                nextStartMs = 0L,
                detectedLanguage = null,
                startedAtEpochMs = System.currentTimeMillis(),
                segments = emptyList()
            ).also(checkpointStore::write)
            latestCheckpoint = checkpoint
            startedAtEpochMs = checkpoint.startedAtEpochMs

            val resumed = checkpoint.nextStartMs > 0L
            addDiagnostic(
                if (resumed) {
                    "Zwischenstand bei ${formatClock(checkpoint.nextStartMs / 1_000L)} fortgesetzt."
                } else {
                    "Audiospur: ${formatClock(audioInfo.durationMs / 1_000L)}, " +
                        "${audioInfo.sourceSampleRate} Hz, ${audioInfo.sourceChannelCount} Kanal/Kanäle."
                }
            )

            val plannedSections = planTranscriptionSections(
                durationMs = audioInfo.durationMs,
                startAtMs = checkpoint.nextStartMs,
                sectionDurationMs = whisperSettings.sectionMinutes * 60_000L
            )
            val previouslyCompletedSections = completedSectionCount(
                checkpoint.nextStartMs,
                whisperSettings.sectionMinutes * 60_000L
            )
            val sectionCount = plannedSections.size + previouslyCompletedSections
            val manifest = prepareAudio(
                request = effectiveRequest,
                model = model,
                uri = uri,
                durationMs = audioInfo.durationMs,
                checkpoint = checkpoint,
                sections = plannedSections,
                sectionOffset = previouslyCompletedSections,
                totalSectionCount = sectionCount,
                sectionDurationMs = whisperSettings.sectionMinutes * 60_000L
            )
            markWorkerProgress("vad", 0)
            activeVadModelPath = resolveVadModelPath(
                request = effectiveRequest,
                model = model,
                checkpoint = checkpoint,
                settings = whisperSettings,
                installedModelPath = installedVadModelPath,
                preparedSections = manifest.sections,
                sectionOffset = previouslyCompletedSections,
                totalSectionCount = sectionCount
            )

            ensureContinues()
            val nativeConfiguration = whisperSettings.toNativeConfiguration()
            activeBackend = if (nativeConfiguration.useGpu) "VULKAN_REQUESTED" else "CPU"
            markWorkerProgress("model_loading", 0)
            updateNotification("Whisper-Modell wird geladen …", 0, indeterminate = true)
            activeWhisperContext = WhisperContext.createContextFromFile(
                modelFile.absolutePath,
                useGpu = nativeConfiguration.useGpu
            )
            var cpuRetryUsed = false
            addDiagnostic(
                "${model.modelLabel} einmal für alle Abschnitte geladen · " +
                    "Backend ${checkNotNull(activeWhisperContext).runtimeBackend.name}."
            )
            activeBackend = checkNotNull(activeWhisperContext).runtimeBackend.name
            manifest.sections.forEachIndexed { index, prepared ->
                ensureContinues()
                val section = prepared.section
                val absoluteSectionNumber = previouslyCompletedSections + index + 1
                markWorkerProgress("inference", absoluteSectionNumber)
                val samples = preparedAudioStore.readSection(prepared)
                try {
                    checkpoint = transcribePreparedSection(
                        request = effectiveRequest,
                        model = model,
                        section = section,
                        samples = samples,
                        sectionNumber = absoluteSectionNumber,
                        sectionCount = sectionCount,
                        checkpoint = checkpoint,
                        whisperSettings = whisperSettings
                    )
                    latestCheckpoint = checkpoint
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException || stopRequested.get()) throw throwable
                    if (!cpuRetryUsed && shouldRetryOnCpu(throwable) &&
                        nativeConfiguration.useGpu
                    ) {
                        cpuRetryUsed = true
                        TranscriptionControlReceiver.cpuRetryFile(this)
                            .writeText(effectiveRequest.jobId)
                        addDiagnostic("GPU/Vulkan-Fehler erkannt; derselbe Abschnitt wird einmal auf CPU wiederholt.")
                        activeWhisperContext?.release()
                        activeWhisperContext = WhisperContext.createContextFromFile(
                            modelFile.absolutePath,
                            useGpu = false
                        )
                        activeBackend = "CPU"
                        checkpoint = transcribePreparedSection(
                            request = effectiveRequest,
                            model = model,
                            section = section,
                            samples = samples,
                            sectionNumber = absoluteSectionNumber,
                            sectionCount = sectionCount,
                            checkpoint = checkpoint,
                            whisperSettings = whisperSettings
                        )
                        latestCheckpoint = checkpoint
                    } else {
                        throw throwable
                    }
                }
                preparedAudioStore.deleteSection(prepared)
                preparedAudioStore.writeManifest(
                    manifest.copy(sections = manifest.sections.drop(index + 1))
                )
            }

            ensureContinues()
            markWorkerProgress("cleanup", sectionCount)
            checkpointStore.clear()
            preparedAudioStore.clear()
            TranscriptionControlReceiver.cpuRetryFile(this).delete()
            val elapsedSeconds = ((System.currentTimeMillis() - checkpoint.startedAtEpochMs) / 1_000L)
                .coerceAtLeast(0L)
            val completed = TranscriptionState.Completed(
                fileName = request.fileName,
                model = model,
                segments = checkpoint.segments,
                detectedLanguage = checkpoint.detectedLanguage.orEmpty(),
                transcriptionDurationSeconds = elapsedSeconds,
                vadSummary = activeVadSummary
            )
            // The local correction model can require several additional GB.
            // Release Whisper before announcing completion so the ViewModel can
            // safely start automatic AI post-processing without both models
            // overlapping in memory.
            markWorkerProgress("model_release", sectionCount)
            val completedWhisperContext = activeWhisperContext
            activeWhisperContext = null
            completedWhisperContext?.release()
            System.gc()
            publishState(completed)
            finishWithNotification(
                title = TRANSCRIPTION_COMPLETE_TITLE,
                text = TRANSCRIPTION_COMPLETE_TEXT,
                completionJobId = effectiveRequest.jobId
            )
        } catch (throwable: Throwable) {
            val checkpoint = latestCheckpoint ?: checkpointStore.read()
            if (userCancellationRequested.get()) {
                publishState(TranscriptionState.Cancelled(request.fileName))
                finishWithNotification("Transkription angehalten", "Der Zwischenstand bleibt erhalten.")
            } else if (!stopRequested.get()) {
                val resumableDataExists = checkpoint != null &&
                    (checkpoint.hasMeaningfulProgress() || preparedAudioStore.readManifest() != null)
                val canResume = resumableDataExists && !isFinalInputFailure(throwable)
                if (!canResume) {
                    checkpointStore.clear()
                    if (isFinalInputFailure(throwable)) preparedAudioStore.clear()
                }
                val message = userFacingError(throwable, canResume, request.language)
                publishState(
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
            activeVadSummary = null
            val context = activeWhisperContext
            activeWhisperContext = null
            runCatching { context?.release() }
            heartbeatScheduler?.shutdownNow()
            heartbeatScheduler = null
            workerHeartbeatStore(filesDir).clear()
            releaseTranscriptionWakeLock()
        }
    }

    private suspend fun prepareAudio(
        request: TranscriptionRequest,
        model: WhisperModel,
        uri: Uri,
        durationMs: Long,
        checkpoint: TranscriptionCheckpoint,
        sections: List<TranscriptionSection>,
        sectionOffset: Int,
        totalSectionCount: Int,
        sectionDurationMs: Long
    ): PreparedAudioManifest {
        val requestKey = preparedAudioRequestKey(request, durationMs)
        val existing = preparedAudioStore.readManifest()
        if (existing != null && preparedAudioStore.isUsable(existing, requestKey, checkpoint.nextStartMs)) {
            addDiagnostic("Vollständig vorbereitete PCM-Audiodaten werden wiederverwendet.")
            cachePreparedWaveform(uri, existing)
            return existing.copy(
                sections = existing.sections.filter { it.section.mainEndMs > checkpoint.nextStartMs }
            )
        }
        if (existing?.requestKey != requestKey) preparedAudioStore.clear()
        val reusable = existing?.sections.orEmpty().takeWhile(preparedAudioStore::sectionExists)
        val missingSections = sections.drop(reusable.size)
        val requiredBytes = requiredPreparedAudioFreeBytes(estimatePreparedAudioBytes(missingSections))
        val availableBytes = preparedAudioStore.usableSpace
        check(availableBytes >= requiredBytes) {
            "Für die Audioaufbereitung werden mindestens ${requiredBytes / (1024L * 1024L)} MB " +
                "freier Speicher benötigt; verfügbar sind ${availableBytes / (1024L * 1024L)} MB."
        }
        val prepared = reusable.toMutableList()
        val waveform = PreparedWaveformAccumulator(durationMs).apply {
            existing?.waveformPeaks?.let(::restore)
        }
        sections.drop(reusable.size).forEachIndexed { relativeIndex, section ->
            ensureContinues()
            val index = reusable.size + relativeIndex
            val sectionNumber = sectionOffset + index + 1
            markWorkerProgress("decoding", sectionNumber)
            publishRunning(request, model, section, sectionNumber, totalSectionCount, checkpoint, 0f,
                "Audio wird vorbereitet · Abschnitt $sectionNumber von $totalSectionCount",
                "Dekodierung auf PCM 16 kHz Mono; Whisper ist noch nicht geladen.")
            val chunk = decodeAudioChunk(this, uri, section.decodeStartMs, section.decodeEndMs,
                stopRequested::get, onDecoderRestart = { stall ->
                    addDiagnostic("Decoder-Stillstand; kontrollierter Neustart. ${stall.message}")
                }) { progress ->
                publishRunning(request, model, section, sectionNumber, totalSectionCount, checkpoint,
                    progress, "Audio wird vorbereitet · Abschnitt $sectionNumber von $totalSectionCount",
                    "Nur ein PCM-Abschnitt befindet sich im Arbeitsspeicher.")
            }
            ensureContinues()
            addDiagnostic(
                "PCM Abschnitt $sectionNumber: ${chunk.mimeType}, " +
                    "${chunk.sourceSampleRate} Hz/${chunk.sourceChannelCount} Kanal/Kanäle → " +
                    "16 kHz Mono, ${pcmEncodingLabel(chunk.pcmEncoding)}, " +
                    "${chunk.diagnostics.sampleCount} Samples, " +
                    "Peak ${formatLevel(chunk.diagnostics.peak)}, " +
                    "RMS ${formatLevel(chunk.diagnostics.rms)}, " +
                    "nahezu still ${formatPercent(chunk.diagnostics.nearSilentSampleRatio)}."
            )
            waveform.add(section, chunk.samples)
            val stored = preparedAudioStore.writeSection(index, chunk.samples)
            prepared += PreparedAudioSection(index, section, stored.first, stored.second)
            preparedAudioStore.writeManifest(
                PreparedAudioManifest(requestKey, durationMs, sectionDurationMs, false,
                    prepared.toList(), waveform.normalized())
            )
        }
        return PreparedAudioManifest(requestKey, durationMs, sectionDurationMs, true,
            prepared.toList(), waveform.normalized()).also {
            preparedAudioStore.writeManifest(it)
            cachePreparedWaveform(uri, it)
            addDiagnostic("Audio vollständig vorbereitet; Decoder-Ressourcen sind freigegeben.")
        }
    }

    private fun cachePreparedWaveform(uri: Uri, manifest: PreparedAudioManifest) {
        val length = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        val cache = WaveformCache(File(cacheDir, "waveforms"))
        cache.write(cache.key(uri.toString(), manifest.durationMs, length),
            CachedWaveform(manifest.waveformPeaks, manifest.durationMs))
    }

    private suspend fun transcribePreparedSection(
        request: TranscriptionRequest,
        model: WhisperModel,
        section: TranscriptionSection,
        samples: FloatArray,
        sectionNumber: Int,
        sectionCount: Int,
        checkpoint: TranscriptionCheckpoint,
        whisperSettings: WhisperSettings
    ): TranscriptionCheckpoint {
        val language = if (request.language == "auto") {
            checkpoint.detectedLanguage?.takeIf(String::isNotBlank) ?: "auto"
        } else request.language
        val vadModelPath = activeVadModelPath
        val result = runCatching {
            transcribeChunk(request, model, section, sectionNumber, sectionCount, checkpoint,
                samples, language, whisperSettings, vadModelPath)
        }.recoverCatching { throwable ->
            if (vadModelPath == null || throwable is CancellationException || stopRequested.get()) throw throwable
            activeVadModelPath = null
            addDiagnostic("VAD-Fehler: Abschnitt $sectionNumber wird einmal ohne VAD wiederholt.")
            transcribeChunk(request, model, section, sectionNumber, sectionCount, checkpoint,
                samples, language, whisperSettings, null)
        }.getOrThrow()
        ensureContinues()
        val absoluteSegments = selectAbsoluteSegments(result.segments, section, checkpoint.durationMs)
        val updated = checkpoint.copy(
            nextStartMs = section.mainEndMs,
            detectedLanguage = checkpoint.detectedLanguage ?: result.detectedLanguage.takeIf {
                it.isNotBlank() && result.segments.any { segment -> segment.text.isNotBlank() }
            },
            segments = mergeCommittedSegments(checkpoint.segments, absoluteSegments)
        )
        checkpointStore.write(updated)
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
        checkpoint: TranscriptionCheckpoint,
        settings: WhisperSettings,
        installedModelPath: String?,
        preparedSections: List<PreparedAudioSection>,
        sectionOffset: Int,
        totalSectionCount: Int
    ): String? {
        if (settings.vadMode == WhisperVadMode.OFF) {
            addDiagnostic("Silero VAD ist ausgeschaltet.")
            activeVadSummary = fullAudioVadSummary(
                settings.vadMode,
                checkpoint.durationMs,
                "VAD ist ausgeschaltet; Whisper verarbeitet das vollständige Audio."
            )
            return null
        }
        if (installedModelPath == null) {
            addDiagnostic("Silero VAD ist nicht installiert; Whisper arbeitet ohne VAD.")
            activeVadSummary = fullAudioVadSummary(
                settings.vadMode,
                checkpoint.durationMs,
                "Silero VAD ist nicht installiert; Whisper verarbeitet das vollständige Audio."
            )
            return null
        }
        if (settings.vadMode == WhisperVadMode.ON) {
            addDiagnostic("Silero VAD ist eingeschaltet und wird verwendet.")
            activeVadSummary = VadProcessingSummary(
                requestedMode = settings.vadMode,
                usedVad = true,
                originalDurationMs = checkpoint.durationMs,
                processedDurationMs = checkpoint.durationMs,
                skippedDurationMs = 0L,
                speechRegionCount = 0,
                reason = "VAD ist eingeschaltet; die Einsparung wird in diesem Modus nicht vorgemessen.",
                measurementsAvailable = false
            )
            return installedModelPath
        }

        val analyzer = VadAutomaticAnalyzer()
        addDiagnostic("VAD-Automatik analysiert dieselben vorbereiteten PCM-Abschnitte mit Silero.")
        val vadContext = runCatching {
            WhisperVadContext.createContextFromFile(installedModelPath)
        }.getOrElse { failure ->
            addDiagnostic("VAD-Automatik konnte Silero nicht laden; Whisper arbeitet ohne VAD: ${failure.message}")
            return null
        }
        val decision = try {
            preparedSections.forEachIndexed { index, prepared ->
                ensureContinues()
                val section = prepared.section
                val sectionNumber = sectionOffset + index + 1
                markWorkerProgress("vad", sectionNumber)
                publishRunning(request, model, section, sectionNumber, totalSectionCount, checkpoint,
                    0f, "VAD-Automatik analysiert Abschnitt ${index + 1} von ${preparedSections.size}",
                    "Silero liest den vorbereiteten PCM-Abschnitt; es findet keine zweite Dekodierung statt.")
                val samples = preparedAudioStore.readSection(prepared)
                ensureContinues()
                val speechSegments = vadContext.detectSegments(
                    samples = samples,
                    threshold = settings.vadThresholdPercent / 100f,
                    minimumSpeechDurationMs = settings.vadMinSpeechDurationMs,
                    minimumSilenceDurationMs = settings.vadMinSilenceDurationMs,
                    maximumSpeechDurationSeconds = settings.vadMaxSpeechDurationSeconds.toFloat(),
                    speechPadMs = settings.vadSpeechPadMs,
                    overlapSeconds = settings.vadOverlapMs / 1_000f
                )
                analyzer.add(
                    chunkDurationMs = samples.size * 1_000L / PREPARED_SAMPLE_RATE,
                    segments = speechSegments,
                    chunkSampleCount = samples.size
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
            activeVadSummary = fullAudioVadSummary(
                settings.vadMode,
                checkpoint.durationMs,
                "Silero-Analyse ist fehlgeschlagen; Whisper verarbeitet das vollständige Audio."
            )
            preparedSections.lastOrNull()?.section?.let { finalSection ->
                publishRunning(request, model, finalSection, totalSectionCount, totalSectionCount,
                    checkpoint, 1f,
                    "VAD-Automatik: Whisper arbeitet ohne VAD",
                    "Silero-Analyse war nicht zuverlässig; der vollständige Ton bleibt erhalten.",
                    StatusMessageKind.IMPORTANT)
            }
            return null
        } finally {
            vadContext.release()
        }
        val summary = "${decision.silencePercent} % Pause, ${decision.speechPercent} % Sprache, " +
            "${decision.speechSegmentCount} Sprachbereiche, längste Pause ${decision.longestSilenceMs / 1_000.0} s, " +
            "${decision.detectedSpeechSampleCount} von ${decision.analyzedSampleCount} Samples in Sprachbereichen"
        val useVad = decision.useVad
        activeVadSummary = analyzedVadSummary(
            mode = settings.vadMode,
            useVad = useVad,
            durationMs = checkpoint.durationMs,
            decision = decision
        )
        if (useVad) {
            val status = "VAD-Automatik aktiviert · ${decision.silencePercent} % Pause erkannt"
            addDiagnostic("VAD-Automatik: VAD wird verwendet ($summary; ${decision.reason}).")
            preparedSections.lastOrNull()?.section?.let { finalSection ->
                publishRunning(request, model, finalSection, totalSectionCount, totalSectionCount,
                    checkpoint, 1f, status,
                    "$summary · ${decision.reason}.", StatusMessageKind.IMPORTANT)
            }
            updateNotification(status, 0, true)
            return installedModelPath
        }
        addDiagnostic("VAD-Automatik: ohne VAD ($summary; ${decision.reason}).")
        preparedSections.lastOrNull()?.section?.let { finalSection ->
            publishRunning(request, model, finalSection, totalSectionCount, totalSectionCount,
                checkpoint, 1f, "VAD-Automatik: Whisper verarbeitet das vollständige Audio",
                "$summary · ${decision.reason}.", StatusMessageKind.IMPORTANT)
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
        detail: String,
        statusKind: StatusMessageKind = StatusMessageKind.PROGRESS
    ) {
        recordWorkerProgress()
        val now = SystemClock.elapsedRealtime()
        val boundary = progressWithinSection <= 0f || progressWithinSection >= 1f
        val uiSignature = "$sectionNumber|${(progressWithinSection * 200f).toInt()}|$status"
        if (!uiUpdateThrottle.shouldPublish(now, uiSignature, force = boundary)) return
        val completedMs = section.mainStartMs +
            (section.mainDurationMs * progressWithinSection.coerceIn(0f, 1f)).toLong()
        val progress = (completedMs.toFloat() / checkpoint.durationMs.toFloat()).coerceIn(0f, 1f)
        publishState(
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
                detectedLanguage = checkpoint.detectedLanguage,
                statusKind = statusKind
            )
        )
        updateNotification(
            text = status,
            percent = (progress * 100).toInt(),
            indeterminate = false,
            force = boundary
        )
    }

    private fun requestUserCancellation() {
        userCancellationRequested.set(true)
        stopRequested.set(true)
        activeWhisperContext?.requestAbort()
        updateNotification("Transkription wird abgebrochen …", 0, indeterminate = true)
    }

    private fun publishState(state: TranscriptionState) {
        TranscriptionCoordinator.publish(this, state, processStartedAtEpochMs)
    }

    private fun ensureContinues() {
        val cancelledJob = runCatching {
            TranscriptionControlReceiver.cancellationFile(this).readText()
        }.getOrDefault("")
        if (stopRequested.get() || (activeJobId.isNotBlank() && cancelledJob == activeJobId)) {
            throw CancellationException("Transkription abgebrochen.")
        }
    }

    private fun startHeartbeat(jobId: String) {
        activeJobId = jobId
        activePhase = "preflight"
        activeBackend = if (forceCpuForRun) "CPU" else "pending"
        activeSectionNumber = 0
        lastProgressAtEpochMs = System.currentTimeMillis()
        heartbeatScheduler?.shutdownNow()
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor().also { scheduler ->
            scheduler.scheduleAtFixedRate(
                { runCatching(::writeHeartbeat) }, 0L, 2L, TimeUnit.SECONDS
            )
        }
    }

    private fun markWorkerProgress(phase: String, sectionNumber: Int) {
        activePhase = phase
        activeSectionNumber = sectionNumber
        recordWorkerProgress()
    }

    private fun recordWorkerProgress() {
        lastProgressAtEpochMs = System.currentTimeMillis()
    }

    private fun writeHeartbeat() {
        if (activeJobId.isBlank()) return
        workerHeartbeatStore(filesDir).write(
            WorkerHeartbeat(
                jobId = activeJobId,
                pid = android.os.Process.myPid(),
                workerStartedAtEpochMs = processStartedAtEpochMs,
                phase = activePhase,
                backend = activeBackend,
                sectionNumber = activeSectionNumber,
                heartbeatAtEpochMs = System.currentTimeMillis(),
                lastProgressAtEpochMs = lastProgressAtEpochMs
            )
        )
    }

    private fun addDiagnostic(message: String) {
        val elapsed = if (startedAtEpochMs == 0L) 0L else {
            (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0L) / 1_000L
        }
        if (diagnostics.size >= 12) diagnostics.removeFirst()
        diagnostics.addLast("${formatClock(elapsed)} · $message")
    }

    private fun updateNotification(
        text: String,
        percent: Int,
        indeterminate: Boolean,
        force: Boolean = true
    ) {
        val signature = "${percent.coerceIn(0, 100)}|$indeterminate|$text"
        if (!notificationUpdateThrottle.shouldPublish(
                SystemClock.elapsedRealtime(),
                signature,
                force
            )
        ) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(
                TRANSCRIPTION_NOTIFICATION_ID,
                buildNotification(text, percent, indeterminate)
            )
        }
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
        val cancel = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, TranscriptionControlReceiver::class.java).apply {
                action = ACTION_CANCEL_TRANSCRIPTION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transcript_notification)
            .setContentTitle("Simple Transcript")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .addAction(0, "Abbrechen", cancel)
            .build()
    }

    private fun finishWithNotification(
        title: String,
        text: String,
        completionJobId: String? = null
    ) {
        val isCompletion = completionJobId != null
        val preferences = getSharedPreferences(NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
        val mayPublish = !isCompletion || shouldPublishCompletionNotification(
            lastCompletedJobId = preferences.getString(LAST_COMPLETED_JOB_ID, null),
            completedJobId = completionJobId.orEmpty()
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            this,
            if (isCompletion) COMPLETION_CHANNEL_ID else RUNNING_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_transcript_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(!isCompletion)
            .setAutoCancel(true)
            .build()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (mayPublish) {
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    if (isCompletion) {
                        TRANSCRIPTION_COMPLETION_NOTIFICATION_ID
                    } else {
                        TRANSCRIPTION_NOTIFICATION_ID
                    },
                    notification
                )
            }.onSuccess {
                if (isCompletion) {
                    preferences.edit()
                        .putString(LAST_COMPLETED_JOB_ID, completionJobId)
                        .apply()
                }
            }
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RUNNING_CHANNEL_ID,
                "Offline-Transkription",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt Fortschritt und Abbruchmöglichkeit langer Transkriptionen."
            }
            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                "Fertige Transkripte",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Meldet neutral, wenn ein lokales Transkript fertiggestellt wurde."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(channel)
                createNotificationChannel(completionChannel)
            }
        }
    }

    private fun Intent.toRequest(): TranscriptionRequest? {
        val uri = getStringExtra(EXTRA_URI) ?: return null
        val fileName = getStringExtra(EXTRA_FILE_NAME) ?: return null
        val encodedConfiguration = getStringExtra(EXTRA_JOB_CONFIGURATION) ?: return null
        val configuration = runCatching {
            TranscriptionJobConfiguration.decode(encodedConfiguration)
        }.getOrNull() ?: return null
        val jobId = getStringExtra(EXTRA_JOB_ID).orEmpty()
        return TranscriptionRequest(uri, fileName, configuration, jobId)
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
                putExtra(
                    EXTRA_JOB_CONFIGURATION,
                    TranscriptionJobConfiguration(model.id, language, settings).encode()
                )
                putExtra(EXTRA_JOB_ID, UUID.randomUUID().toString())
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.sendBroadcast(Intent(context, TranscriptionControlReceiver::class.java).apply {
                action = ACTION_CANCEL_TRANSCRIPTION
            })
        }

        internal fun resumeCheckpoint(context: Context, forceCpu: Boolean) {
            val checkpoint = TranscriptionCheckpointStore(
                File(context.filesDir, CHECKPOINT_FILE_NAME)
            ).read() ?: return
            val request = checkpoint.request
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, request.uri)
                putExtra(EXTRA_FILE_NAME, request.fileName)
                putExtra(EXTRA_JOB_CONFIGURATION, request.configuration.encode())
                putExtra(EXTRA_JOB_ID, request.jobId)
                putExtra(EXTRA_FORCE_CPU, forceCpu)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private fun shouldRetryOnCpu(throwable: Throwable): Boolean {
    val causes = generateSequence(throwable) { it.cause }.toList()
    if (causes.filterIsInstance<WhisperTranscriptionException>().any { it.errorCode == -3 }) {
        return true
    }
    val details = causes.joinToString(" ") { "${it.javaClass.name} ${it.message.orEmpty()}" }
    return listOf("vulkan", "vk_", "gpu", "device lost", "native").any {
        details.contains(it, ignoreCase = true)
    }
}

private fun isFinalInputFailure(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }.any {
        it is UnusableAudioSamplesException ||
            (it is WhisperTranscriptionException && it.errorCode == -3)
    }

private fun completedSectionCount(positionMs: Long, sectionDurationMs: Long): Int =
    if (positionMs <= 0L) 0 else {
        ((positionMs + sectionDurationMs - 1L) / sectionDurationMs).toInt()
    }

private fun userFacingError(
    throwable: Throwable,
    canResume: Boolean,
    requestedLanguage: String
): String {
    val checkpointSuffix = if (canResume) " Der Zwischenstand bleibt erhalten." else ""
    val whisperFailure = generateSequence(throwable) { it.cause }
        .filterIsInstance<WhisperTranscriptionException>()
        .firstOrNull()
    if (whisperFailure?.errorCode == -3 && requestedLanguage == "auto") {
        return "Die automatische Spracherkennung ist fehlgeschlagen. " +
            "Wähle die gesprochene Sprache fest aus und starte die Transkription erneut."
    }
    val unusableAudio = generateSequence(throwable) { it.cause }
        .filterIsInstance<UnusableAudioSamplesException>()
        .firstOrNull()
    if (unusableAudio != null) return unusableAudio.message.orEmpty()
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

private fun pcmEncodingLabel(encoding: Int): String = when (encoding) {
    android.media.AudioFormat.ENCODING_PCM_8BIT -> "PCM 8 Bit"
    android.media.AudioFormat.ENCODING_PCM_16BIT -> "PCM 16 Bit"
    android.media.AudioFormat.ENCODING_PCM_32BIT -> "PCM 32 Bit"
    android.media.AudioFormat.ENCODING_PCM_FLOAT -> "PCM Float"
    else -> "PCM $encoding"
}

private fun formatLevel(value: Float): String =
    if (value <= 0f) "−∞ dB" else "%.1f dB".format(20.0 * kotlin.math.log10(value.toDouble()))

private fun formatPercent(value: Float): String = "%.1f %%".format(value * 100f)

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
