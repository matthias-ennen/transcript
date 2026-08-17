package de.matthiasennen.transcript.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiModelDownloadCoordinator
import de.matthiasennen.transcript.ai.AiModelDownloadService
import de.matthiasennen.transcript.ai.AiModelDownloadState
import de.matthiasennen.transcript.ai.AiModelInstallation
import de.matthiasennen.transcript.ai.AiCorrectionTrace
import de.matthiasennen.transcript.ai.AiEngineSessionManager
import de.matthiasennen.transcript.ai.AiBenchmarkResult
import de.matthiasennen.transcript.ai.AiBenchmarkRun
import de.matthiasennen.transcript.ai.AiHardwareProbe
import de.matthiasennen.transcript.ai.AiHardwareSnapshot
import de.matthiasennen.transcript.ai.AiPostProcessingCoordinator
import de.matthiasennen.transcript.ai.AiPostProcessingMode
import de.matthiasennen.transcript.ai.AiPostProcessingService
import de.matthiasennen.transcript.ai.AiPostProcessingState
import de.matthiasennen.transcript.ai.AiPreferences
import de.matthiasennen.transcript.ai.AiPerformancePreferences
import de.matthiasennen.transcript.ai.AiSelfTestMetrics
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.ai.LocalAiEngine
import de.matthiasennen.transcript.download.ModelDownloadCoordinator
import de.matthiasennen.transcript.download.ModelDownloadService
import de.matthiasennen.transcript.download.ModelDownloadState
import de.matthiasennen.transcript.download.SileroVadModel
import de.matthiasennen.transcript.download.VadModelDownloadCoordinator
import de.matthiasennen.transcript.download.VadModelDownloadService
import de.matthiasennen.transcript.download.VadModelDownloadState
import de.matthiasennen.transcript.download.VadModelInstallation
import de.matthiasennen.transcript.media.AudioPlayerController
import de.matthiasennen.transcript.media.CachedWaveform
import de.matthiasennen.transcript.media.RecordingCoordinator
import de.matthiasennen.transcript.media.RecordingFolder
import de.matthiasennen.transcript.media.RecordingFolderPreferences
import de.matthiasennen.transcript.media.RecordingService
import de.matthiasennen.transcript.media.RecordingState
import de.matthiasennen.transcript.media.WaveformCache
import de.matthiasennen.transcript.media.generateWaveform
import de.matthiasennen.transcript.media.inspectAudioTrack
import de.matthiasennen.transcript.transcription.TranscriptionCoordinator
import de.matthiasennen.transcript.transcription.StoredTranscriptResult
import de.matthiasennen.transcript.transcription.TranscriptResultPersistence
import de.matthiasennen.transcript.transcription.TranscriptResultStore
import de.matthiasennen.transcript.transcription.TranscriptionService
import de.matthiasennen.transcript.transcription.TranscriptionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val PREFERENCES_NAME = "transcript_preferences"
private const val SELECTED_MODEL_KEY = "selected_model"
private const val LANGUAGE_KEY = "transcription_language"

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var uiState by mutableStateOf(TranscriptUiState())
        private set

    private val modelsDirectory = File(application.filesDir, "models")
    private val vadModelsDirectory = File(application.filesDir, "vad-models")
    private val aiModelsDirectory = File(application.filesDir, "ai-models")
    private val sharedMediaImportStore = SharedMediaImportStore(
        File(application.filesDir, "shared-media")
    )
    private val aiPreferences = AiPreferences(application)
    private val aiPerformancePreferences = AiPerformancePreferences(application)
    private val waveformCache = WaveformCache(File(application.filesDir, "waveforms"))
    private val transcriptResultStore = TranscriptResultStore(
        File(application.filesDir, "transcripts/current-transcript.bin")
    )
    private val transcriptResultPersistence = TranscriptResultPersistence(transcriptResultStore)
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val recordingFolderPreferences = RecordingFolderPreferences(application)
    private val whisperSettingsPreferences = WhisperSettingsPreferences(application)
    private val audioPlayer = AudioPlayerController(
        context = application,
        onPrepared = { durationMs ->
            uiState = uiState.copy(audioDurationMs = durationMs)
                .withRecalculatedTranscriptionEstimate()
        },
        onCompletion = {
            playbackTimer?.cancel()
            playbackTimer = null
            uiState = uiState.copy(
                isPlaying = false,
                playbackPositionMs = uiState.audioDurationMs,
                status = "Wiedergabe beendet.",
                cannaBotMode = CannaBotMode.IDLE
            )
            cue(CannaBotCue.WAVING)
        },
        onError = { message ->
            playbackTimer?.cancel()
            playbackTimer = null
            uiState = uiState.copy(
                isPlaying = false,
                error = message,
                status = "Wiedergabe fehlgeschlagen.",
                cannaBotMode = CannaBotMode.IDLE
            )
            cue(CannaBotCue.FAILED)
        }
    )
    private var playbackTimer: Job? = null
    private var waveformJob: Job? = null
    private var aiBenchmarkJob: Job? = null
    private var transcriptionElapsedTimer: Job? = null
    private var transcriptionStartedAtEpochMs = 0L
    private var lastDownloadAnimationBucket = -1
    private var lastTranscriptionAnimationSection = -1

    private fun cue(cue: CannaBotCue) {
        uiState = uiState.copy(cannaBotCue = cue, cannaBotCueId = uiState.cannaBotCueId + 1L)
    }

    init {
        modelsDirectory.mkdirs()
        vadModelsDirectory.mkdirs()
        aiModelsDirectory.mkdirs()
        sharedMediaImportStore.clearPending()
        refreshDeviceStorage()
        val selectedModel = WhisperModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null))
        val whisperSettings = whisperSettingsPreferences.load()
        refreshModelInstallations(selectedModel)
        refreshVadModelInstallation()
        val aiSettings = aiPreferences.load()
        val recordingFolder = recordingFolderPreferences.loadValid()
        uiState = uiState.copy(
            language = preferences.getString(LANGUAGE_KEY, "auto") ?: "auto",
            recordingFolderName = recordingFolder?.displayName,
            whisperSettings = whisperSettings,
            selectedAiModel = aiSettings.selectedModel,
            aiPostProcessingEnabled = aiSettings.enabled,
            automaticAiPostProcessingEnabled = aiSettings.automatic,
            performanceProfileModel = aiSettings.selectedModel,
            aiPerformanceConfiguration = aiPerformancePreferences.load(aiSettings.selectedModel),
            aiHardwareSnapshot = runCatching { AiHardwareProbe.read(application) }.getOrNull(),
            performanceModelLayerCount = inspectAiModelLayers(aiSettings.selectedModel)
        )
        refreshAiModelInstallations(aiSettings.selectedModel)
        TranscriptionCoordinator.initialize(application)
        restoreStoredTranscript()
        viewModelScope.launch {
            ModelDownloadCoordinator.state.collect(::handleModelDownloadState)
        }
        viewModelScope.launch {
            VadModelDownloadCoordinator.state.collect(::handleVadModelDownloadState)
        }
        viewModelScope.launch {
            AiModelDownloadCoordinator.state.collect(::handleAiModelDownloadState)
        }
        viewModelScope.launch {
            TranscriptionCoordinator.state.collect(::handleTranscriptionState)
        }
        viewModelScope.launch {
            AiPostProcessingCoordinator.state.collect(::handleAiPostProcessingState)
        }
        viewModelScope.launch {
            RecordingCoordinator.state.collect(::handleRecordingState)
        }
    }

    fun selectAudio(uri: Uri) {
        runCatching {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        sharedMediaImportStore.clearCommitted()
        selectAudioInternal(
            uri = uri,
            fileName = displayName(uri),
            status = "Audio- oder Videodatei ausgewählt."
        )
    }

    fun receiveSharedMedia(uri: Uri?, mimeType: String?) {
        if (uri == null) {
            reportUnsupportedShare("Die geteilte Datei konnte nicht gelesen werden.")
            return
        }
        val resolvedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: application.contentResolver.getType(uri)?.lowercase()
        if (!isSupportedSharedMediaMime(resolvedMime)) {
            reportUnsupportedShare("Dieses Audio- oder Videoformat wird nicht unterstützt.")
            return
        }
        if (uiState.isBusy || uiState.isRecording || uiState.isRecordingStopping) {
            reportUnsupportedShare("Während eines laufenden Vorgangs kann keine Datei übernommen werden.")
            return
        }
        val request = SharedMediaRequest(
            uri = uri,
            fileName = displayName(uri),
            mimeType = resolvedMime.orEmpty(),
            declaredSizeBytes = contentLength(uri)
        )
        val currentWorkExists = uiState.selectedAudio != null ||
            uiState.segments.isNotEmpty() ||
            uiState.draftSegments.isNotEmpty() ||
            uiState.completedModel != null
        if (currentWorkExists) {
            uiState = uiState.copy(
                pendingSharedMediaImport = request,
                status = "Geteilte Datei wartet auf Bestätigung.",
                error = null,
                cannaBotMode = CannaBotMode.WAITING
            )
        } else {
            importSharedMedia(request)
        }
    }

    fun confirmSharedMediaImport() {
        val request = uiState.pendingSharedMediaImport ?: return
        uiState = uiState.copy(pendingSharedMediaImport = null)
        importSharedMedia(request)
    }

    fun cancelSharedMediaImport() {
        if (uiState.pendingSharedMediaImport == null) return
        uiState = uiState.copy(
            pendingSharedMediaImport = null,
            status = "Geteilte Datei wurde nicht übernommen.",
            cannaBotMode = CannaBotMode.IDLE
        )
    }

    fun reportUnsupportedShare(message: String) {
        uiState = uiState.copy(
            pendingSharedMediaImport = null,
            isSharedMediaImporting = false,
            error = message,
            status = "Geteilte Datei konnte nicht übernommen werden.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.FAILED)
    }

    private fun importSharedMedia(request: SharedMediaRequest) {
        if (uiState.isBusy || uiState.isSharedMediaImporting) return
        viewModelScope.launch {
            uiState = uiState.copy(
                pendingSharedMediaImport = null,
                isSharedMediaImporting = true,
                isBusy = true,
                progress = null,
                error = null,
                status = "Geteilte Datei wird sicher übernommen …",
                activityDetail = "Datei wird in den privaten App-Speicher kopiert.",
                cannaBotMode = CannaBotMode.WAITING
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    val stats = StatFs(application.filesDir.absolutePath)
                    val availableBytes = stats.availableBlocksLong * stats.blockSizeLong
                    val staged = application.contentResolver.openInputStream(request.uri)?.use { input ->
                        sharedMediaImportStore.stage(
                            input = input,
                            requestedFileName = request.fileName,
                            declaredSizeBytes = request.declaredSizeBytes,
                            availableBytes = availableBytes
                        )
                    } ?: error("Die geteilte Datei ist nicht lesbar.")
                    try {
                        inspectAudioTrack(application, Uri.fromFile(staged.file))
                        sharedMediaImportStore.commit(staged)
                    } catch (failure: Throwable) {
                        staged.file.delete()
                        throw failure
                    }
                }
            }.onSuccess { importedFile ->
                uiState = uiState.copy(
                    isSharedMediaImporting = false,
                    isBusy = false,
                    activityDetail = null
                )
                selectAudioInternal(
                    uri = Uri.fromFile(importedFile),
                    fileName = request.fileName,
                    status = "Geteilte Datei wurde sicher übernommen."
                )
            }.onFailure { failure ->
                uiState = uiState.copy(
                    isSharedMediaImporting = false,
                    isBusy = false,
                    activityDetail = null,
                    error = failure.localizedMessage ?: "Die geteilte Datei ist nicht lesbar.",
                    status = "Geteilte Datei konnte nicht importiert werden.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
            }
        }
    }

    fun startRecording() {
        if (uiState.isBusy || uiState.isRecording || uiState.isRecordingStopping) return
        sharedMediaImportStore.clearCommitted()
        stopPlayback(release = true)
        transcriptResultPersistence.clear()
        uiState = uiState.copy(
            isRecording = true,
            isRecordingStopping = false,
            liveWaveform = emptyList(),
            rawWhisperSegments = emptyList(),
            segments = emptyList(),
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            error = null,
            elapsedSeconds = 0L,
            status = "Aufnahme wird gestartet …",
            cannaBotMode = CannaBotMode.WAITING
        )
        runCatching { RecordingService.start(application) }
            .onFailure { failure ->
                handleRecordingState(
                    RecordingState.Failed(
                        failure.localizedMessage ?: "Die Aufnahme konnte nicht gestartet werden."
                    )
                )
            }
    }

    fun recordingFolder(): RecordingFolder? = recordingFolderPreferences.loadValid()

    fun saveRecordingFolder(uri: Uri, grantedFlags: Int): Boolean {
        val folder = recordingFolderPreferences.save(uri, grantedFlags) ?: run {
            uiState = uiState.copy(
                error = "Der ausgewählte Ordner kann nicht dauerhaft beschrieben werden.",
                status = "Aufnahmeordner konnte nicht verwendet werden.",
                cannaBotMode = CannaBotMode.IDLE
            )
            cue(CannaBotCue.FAILED)
            return false
        }
        uiState = uiState.copy(
            recordingFolderName = folder.displayName,
            error = null,
            status = "Aufnahmeordner „${folder.displayName}“ ausgewählt.",
            cannaBotMode = CannaBotMode.REVIEW
        )
        cue(CannaBotCue.SUCCESS)
        return true
    }

    fun reportRecordingFolderSelectionCancelled() {
        uiState = uiState.copy(
            status = "Kein Aufnahmeordner ausgewählt. Aufnahme nicht gestartet.",
            cannaBotMode = CannaBotMode.IDLE
        )
    }

    fun reportMicrophonePermissionDenied() {
        uiState = uiState.copy(
            error = "Für eine Aufnahme benötigt Transcript die Mikrofonberechtigung.",
            status = "Mikrofonberechtigung fehlt.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.FAILED)
    }

    fun reportRecordingNotificationPermissionDenied() {
        uiState = uiState.copy(
            isRecording = false,
            isRecordingStopping = false,
            error = "Für eine sichere Hintergrundaufnahme benötigt Transcript die Benachrichtigungsberechtigung.",
            status = "Benachrichtigungsberechtigung fehlt.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.FAILED)
    }

    fun stopRecording() {
        if (!uiState.isRecording || uiState.isRecordingStopping) return
        uiState = uiState.copy(
            isRecordingStopping = true,
            status = "Aufnahme wird beendet …",
            cannaBotMode = CannaBotMode.WAITING
        )
        RecordingService.stop(application)
    }

    private fun handleRecordingState(state: RecordingState) {
        when (state) {
            RecordingState.Idle -> Unit
            RecordingState.Starting -> {
                uiState = uiState.copy(
                    isRecording = true,
                    isRecordingStopping = false,
                    status = "Aufnahme wird gestartet …",
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            RecordingState.Stopping -> {
                uiState = uiState.copy(
                    isRecording = true,
                    isRecordingStopping = true,
                    status = "Aufnahme wird beendet …",
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            is RecordingState.Running -> {
                if (uiState.isRecordingStopping) return
                val joiningActiveRecording = !uiState.isRecording
                if (joiningActiveRecording) {
                    waveformJob?.cancel()
                    waveformJob = null
                    stopPlayback(release = true)
                }
                uiState = uiState.copy(
                    isRecording = true,
                    isRecordingStopping = false,
                    elapsedSeconds = state.elapsedSeconds,
                    liveWaveform = (uiState.liveWaveform + state.amplitude).takeLast(72),
                    selectedAudio = if (joiningActiveRecording) null else uiState.selectedAudio,
                    selectedFileName = if (joiningActiveRecording) state.output.fileName else uiState.selectedFileName,
                    waveform = if (joiningActiveRecording) emptyList() else uiState.waveform,
                    isWaveformLoading = if (joiningActiveRecording) false else uiState.isWaveformLoading,
                    waveformProgress = if (joiningActiveRecording) null else uiState.waveformProgress,
                    audioDurationMs = if (joiningActiveRecording) 0L else uiState.audioDurationMs,
                    rawWhisperSegments = if (joiningActiveRecording) emptyList() else uiState.rawWhisperSegments,
                    segments = if (joiningActiveRecording) emptyList() else uiState.segments,
                    draftSegments = if (joiningActiveRecording) emptyList() else uiState.draftSegments,
                    completedModel = if (joiningActiveRecording) null else uiState.completedModel,
                    status = "Aufnahme läuft … Zum Beenden erneut tippen.",
                    cannaBotMode = CannaBotMode.RUNNING,
                    error = null
                )
            }
            is RecordingState.Completed -> {
                selectAudioInternal(
                    uri = state.output.uri,
                    fileName = state.output.fileName,
                    status = "Aufnahme gespeichert und ausgewählt."
                )
                RecordingCoordinator.reset()
            }
            is RecordingState.Failed -> {
                uiState = uiState.copy(
                    isRecording = false,
                    isRecordingStopping = false,
                    liveWaveform = emptyList(),
                    error = state.message,
                    status = "Aufnahme fehlgeschlagen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
                RecordingCoordinator.reset()
            }
        }
    }

    fun togglePlayback() {
        val uri = uiState.selectedAudio ?: return
        if (uiState.isRecording || uiState.isBusy) return
        runCatching { audioPlayer.toggle(uri) }
            .onSuccess { playing ->
                uiState = uiState.copy(
                    isPlaying = playing,
                    error = null,
                    status = if (playing) "Audio wird wiedergegeben …" else "Wiedergabe pausiert.",
                    cannaBotMode = if (playing) CannaBotMode.RUNNING else CannaBotMode.IDLE
                )
                if (playing) cue(CannaBotCue.RUNNING_RIGHT)
                if (playing) startPlaybackTimer() else stopPlaybackTimer()
            }
            .onFailure { throwable ->
                stopPlaybackTimer()
                uiState = uiState.copy(
                    isPlaying = false,
                    error = throwable.localizedMessage ?: "Die Mediendatei konnte nicht abgespielt werden.",
                    status = "Wiedergabe fehlgeschlagen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
            }
    }

    fun seekPlayback(fraction: Float) {
        val duration = uiState.audioDurationMs
        if (duration <= 0L || uiState.isRecording || uiState.isBusy) return
        val position = (duration * fraction.coerceIn(0f, 1f)).toLong()
        seekPlaybackTo(position)
    }

    fun skipToPreviousTranscriptSegment() {
        if (!transcriptSegmentNavigationEnabled()) return
        previousTranscriptSegmentPositionMs(
            segments = uiState.segments,
            positionMs = uiState.playbackPositionMs
        )?.let(::seekPlaybackTo)
    }

    fun skipToNextTranscriptSegment() {
        if (!transcriptSegmentNavigationEnabled()) return
        nextTranscriptSegmentPositionMs(
            segments = uiState.segments,
            positionMs = uiState.playbackPositionMs
        )?.let(::seekPlaybackTo)
    }

    private fun transcriptSegmentNavigationEnabled(): Boolean =
        uiState.completedModel != null && uiState.segments.isNotEmpty() &&
            !uiState.isRecording && !uiState.isBusy

    private fun seekPlaybackTo(positionMs: Long) {
        val position = positionMs.coerceIn(0L, uiState.audioDurationMs.coerceAtLeast(0L))
        audioPlayer.seekTo(position)
        uiState = uiState.copy(playbackPositionMs = position)
    }

    private fun selectAudioInternal(
        uri: Uri,
        fileName: String,
        status: String
    ) = loadAudioState(uri, fileName, status, restoredTranscript = null)

    private fun restoreAudioInternal(
        uri: Uri,
        fileName: String,
        status: String,
        restoredTranscript: StoredTranscriptResult
    ) = loadAudioState(uri, fileName, status, restoredTranscript)

    private fun loadAudioState(
        uri: Uri,
        fileName: String,
        status: String,
        restoredTranscript: StoredTranscriptResult?
    ) {
        stopPlayback(release = true)
        if (restoredTranscript == null) transcriptResultPersistence.clear()
        waveformJob?.cancel()
        uiState = uiState.copy(
            selectedAudio = uri,
            selectedFileName = fileName,
            isRecording = false,
            isRecordingStopping = false,
            isPlaying = false,
            playbackPositionMs = 0L,
            audioDurationMs = 0L,
            mediaReadyStatus = null,
            waveform = emptyList(),
            liveWaveform = emptyList(),
            isWaveformLoading = true,
            waveformProgress = 0f,
            rawWhisperSegments = restoredTranscript?.rawWhisperSegments.orEmpty(),
            segments = restoredTranscript?.displayedSegments.orEmpty(),
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            error = null,
            elapsedSeconds = 0L,
            diagnostics = emptyList(),
            detectedLanguage = restoredTranscript?.detectedLanguage,
            completedModel = restoredTranscript?.let { WhisperModel.fromId(it.modelId) },
            transcriptionDurationSeconds = restoredTranscript?.transcriptionDurationSeconds,
            vadProcessingSummary = restoredTranscript?.vadSummary,
            status = if (restoredTranscript == null) {
                "Wellenform wird erstellt …"
            } else {
                status
            },
            runtimeEstimateAnnouncementId = 0L,
            activityDetail = if (restoredTranscript == null) "Wellenform wird erstellt · 0 %" else null,
            cannaBotMode = if (restoredTranscript == null) CannaBotMode.WAITING else CannaBotMode.IDLE
        ).withRecalculatedTranscriptionEstimate()
        waveformJob = viewModelScope.launch {
            try {
                val metadataDurationMs = withContext(Dispatchers.IO) {
                    inspectAudioTrack(application, uri).durationMs
                }
                if (uiState.selectedAudio == uri && uiState.audioDurationMs <= 0L) {
                    uiState = uiState.copy(audioDurationMs = metadataDurationMs)
                        .withRecalculatedTranscriptionEstimate()
                }
                val cacheKey = withContext(Dispatchers.IO) {
                    waveformCache.key(
                        uri = uri.toString(),
                        durationMs = metadataDurationMs,
                        contentLength = contentLength(uri)
                    )
                }
                val cached = withContext(Dispatchers.IO) { waveformCache.read(cacheKey) }
                if (cached != null) {
                    finishWaveform(uri, cached.peaks, cached.durationMs, status)
                    return@launch
                }

                val workerIsActive = when (TranscriptionCoordinator.state.value) {
                    is TranscriptionState.Starting, is TranscriptionState.Running -> true
                    else -> false
                }
                if (restoredTranscript != null && workerIsActive) {
                    // The isolated worker is creating this exact cache from its prepared PCM.
                    // Never start a competing decoder merely because the UI was reopened.
                    var cachePolls = 0
                    while (cachePolls < 180) {
                        delay(1_000L)
                        cachePolls++
                        val preparedCache = withContext(Dispatchers.IO) { waveformCache.read(cacheKey) }
                        if (preparedCache != null) {
                            finishWaveform(uri, preparedCache.peaks, preparedCache.durationMs, status)
                            return@launch
                        }
                        val stillActive = when (TranscriptionCoordinator.state.value) {
                            is TranscriptionState.Starting, is TranscriptionState.Running -> true
                            else -> false
                        }
                        if (!stillActive) break
                    }
                    finishWaveformWithoutPreview(uri)
                    return@launch
                }

                val (waveform, durationMs) = withContext(Dispatchers.IO) {
                    val workerContext = currentCoroutineContext()
                    generateWaveform(
                        context = application,
                        uri = uri,
                        shouldCancel = { !workerContext.isActive },
                        onProgress = { progress -> reportWaveformProgress(uri, progress) }
                    )
                }
                withContext(Dispatchers.IO) {
                    waveformCache.write(
                        cacheKey,
                        CachedWaveform(peaks = waveform, durationMs = durationMs)
                    )
                }
                finishWaveform(uri, waveform, durationMs, status)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                finishWaveformWithoutPreview(uri)
            }
        }
    }

    private fun reportWaveformProgress(uri: Uri, progress: Float) {
        viewModelScope.launch {
            if (uiState.selectedAudio != uri || !uiState.isWaveformLoading) return@launch
            val safeProgress = progress.coerceIn(0f, 1f)
            val detail = "Wellenform wird erstellt · ${(safeProgress * 100f).roundToInt()} %"
            uiState = if (
                uiState.isBusy ||
                uiState.isTranscribing ||
                uiState.segments.isNotEmpty() ||
                uiState.error != null
            ) {
                uiState.copy(waveformProgress = safeProgress)
            } else {
                uiState.copy(waveformProgress = safeProgress, activityDetail = detail)
            }
        }
    }

    private fun finishWaveform(
        uri: Uri,
        waveform: List<Float>,
        durationMs: Long,
        readyStatus: String
    ) {
        if (uiState.selectedAudio != uri) return
        val operationOwnsStatus = uiState.isBusy || uiState.isTranscribing || uiState.isRecording ||
            uiState.segments.isNotEmpty() || uiState.error != null
        val restoredTimeline = if (
            uiState.completedModel != null && uiState.rawWhisperSegments.isNotEmpty()
        ) {
            restoreManualTimelineText(
                timeline = buildTranscriptTimeline(uiState.rawWhisperSegments, durationMs),
                previouslyDisplayed = uiState.segments,
                rawWhisperSegments = uiState.rawWhisperSegments
            )
        } else {
            uiState.segments
        }
        uiState = uiState.copy(
            waveform = waveform,
            audioDurationMs = uiState.audioDurationMs.takeIf { it > 0L } ?: durationMs,
            isWaveformLoading = false,
            waveformProgress = 1f,
            segments = restoredTimeline,
            mediaReadyStatus = readyStatus,
            status = if (operationOwnsStatus) uiState.status else readyStatus,
            activityDetail = if (operationOwnsStatus) uiState.activityDetail else null,
            cannaBotMode = if (operationOwnsStatus) uiState.cannaBotMode else CannaBotMode.REVIEW
        ).withRecalculatedTranscriptionEstimate()
        if (restoredTimeline != uiState.rawWhisperSegments && uiState.completedModel != null) {
            persistCurrentTranscript(restoredTimeline)
        }
    }

    private fun finishWaveformWithoutPreview(uri: Uri) {
        if (uiState.selectedAudio != uri) return
        val operationOwnsStatus = uiState.isBusy || uiState.isTranscribing || uiState.isRecording ||
            uiState.segments.isNotEmpty() || uiState.error != null
        val readyStatus = "Datei ist bereit · Wellenform konnte nicht erstellt werden."
        val restoredTimeline = if (
            uiState.completedModel != null &&
            uiState.rawWhisperSegments.isNotEmpty() &&
            uiState.audioDurationMs > 0L
        ) {
            restoreManualTimelineText(
                timeline = buildTranscriptTimeline(uiState.rawWhisperSegments, uiState.audioDurationMs),
                previouslyDisplayed = uiState.segments,
                rawWhisperSegments = uiState.rawWhisperSegments
            )
        } else {
            uiState.segments
        }
        uiState = uiState.copy(
            waveform = emptyList(),
            isWaveformLoading = false,
            waveformProgress = null,
            segments = restoredTimeline,
            mediaReadyStatus = readyStatus,
            status = if (operationOwnsStatus) uiState.status else readyStatus,
            activityDetail = if (operationOwnsStatus) uiState.activityDetail else null,
            cannaBotMode = if (operationOwnsStatus) uiState.cannaBotMode else CannaBotMode.REVIEW
        )
    }

    fun setLanguage(language: String, page: WhisperSettingsPage? = null) {
        preferences.edit().putString(LANGUAGE_KEY, language).apply()
        uiState = if (page == null) {
            uiState.copy(language = language)
        } else {
            uiState.copy(
                language = language,
                status = page.savedMessage,
                statusKind = StatusMessageKind.IMPORTANT,
                statusEventId = uiState.statusEventId + 1L,
                cannaBotMode = CannaBotMode.REVIEW
            )
        }
    }

    fun updateWhisperSettings(
        settings: WhisperSettings,
        page: WhisperSettingsPage = WhisperSettingsPage.WHISPER
    ) {
        if (uiState.isTranscribing) return
        val normalized = settings.normalized()
        whisperSettingsPreferences.save(normalized)
        uiState = uiState.copy(
            whisperSettings = normalized,
            status = page.savedMessage,
            statusKind = StatusMessageKind.IMPORTANT,
            statusEventId = uiState.statusEventId + 1L,
            cannaBotMode = CannaBotMode.REVIEW
        )
    }

    fun resetWhisperSettings(
        group: WhisperSettingsGroup,
        page: WhisperSettingsPage = WhisperSettingsPage.WHISPER
    ) {
        if (uiState.isTranscribing) return
        val normalized = uiState.whisperSettings.reset(group).normalized()
        whisperSettingsPreferences.save(normalized)
        uiState = uiState.copy(
            whisperSettings = normalized,
            status = page.resetMessage,
            statusKind = StatusMessageKind.IMPORTANT,
            statusEventId = uiState.statusEventId + 1L,
            cannaBotMode = CannaBotMode.REVIEW
        )
    }

    fun selectModel(model: WhisperModel) {
        if (uiState.isBusy) return
        val stateBeforeSelection = uiState
        preferences.edit().putString(SELECTED_MODEL_KEY, model.id).apply()
        refreshModelInstallations(model)
        if (
            uiState.modelReady &&
            stateBeforeSelection.selectedAudio != null &&
            stateBeforeSelection.audioDurationMs > 0L
        ) {
            uiState = uiState.copy(
                status = when {
                    stateBeforeSelection.isWaveformLoading -> stateBeforeSelection.status
                    uiState.mediaReadyStatus != null ->
                        uiState.mediaReadyStatus ?: uiState.status
                    else -> uiState.status
                },
                activityDetail = if (stateBeforeSelection.isWaveformLoading) {
                    stateBeforeSelection.activityDetail
                } else {
                    uiState.activityDetail
                },
                runtimeEstimateAnnouncementId =
                    stateBeforeSelection.runtimeEstimateAnnouncementId + 1L,
                cannaBotMode = if (stateBeforeSelection.isWaveformLoading) {
                    CannaBotMode.WAITING
                } else {
                    CannaBotMode.REVIEW
                }
            )
        }
    }

    /** Emits the model-only guidance once for each actual visit to the central settings page. */
    fun announceSettingsPerformance() {
        if (
            uiState.isBusy ||
            uiState.isRecording ||
            uiState.isTranscribing ||
            uiState.error != null
        ) return
        uiState = uiState.copy(
            status = uiState.selectedModel.settingsPerformanceMessage(),
            statusKind = StatusMessageKind.IMPORTANT,
            statusEventId = uiState.statusEventId + 1L,
            cannaBotMode = CannaBotMode.REVIEW
        )
    }

    fun startTranscriptEditing(groupStartMs: Long) {
        if (uiState.isBusy || uiState.segments.isEmpty()) return
        if (uiState.segments.none { transcriptGroupStartMs(it.startMs) == groupStartMs }) return
        uiState = uiState.copy(
            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = uiState.segments
        )
    }

    fun startAiTranscriptEditing(groupStartMs: Long) {
        if (uiState.isBusy || uiState.completedModel == null || uiState.segments.isEmpty() || !uiState.selectedAiModelInstalled) return
        if (uiState.segments.none { transcriptGroupStartMs(it.startMs) == groupStartMs }) return
        uiState = uiState.copy(
            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = uiState.segments,
            isBusy = true,
            isAiPostProcessing = true,
            progress = null,
            error = null,
            status = "Texte werden mit KI überarbeitet …",
            activityDetail = "Die ausgewählte Gruppe wird lokal geprüft.",
            diagnostics = (uiState.diagnostics +
                "Manuelle KI-Nachbearbeitung mit ${uiState.selectedAiModel.modelLabel} gestartet.")
                .takeLast(12),
            latestAiCorrectionTrace = null,
            cannaBotMode = CannaBotMode.REVIEW
        )
        AiPostProcessingService.startManualGroup(
            context = application,
            model = uiState.selectedAiModel,
            fileName = uiState.selectedFileName ?: "Transkript",
            groupStartMs = groupStartMs,
            segments = uiState.segments
        )
    }

    fun updateAiTestPrompt(prompt: String) {
        uiState = uiState.copy(aiTestPrompt = prompt)
    }

    fun prepareAiDiagnostics() {
        val model = uiState.selectedAiModel
        val modelFile = aiModelFile(model)
        val configuration = aiPerformancePreferences.load(model)
        val matchingSessionLoaded = AiEngineSessionManager.isLoaded(
            model,
            modelFile,
            configuration
        )
        val operationActive = uiState.isBusy || uiState.isRecording ||
            uiState.isRecordingStopping || uiState.isAiModelPreloading ||
            AiPostProcessingCoordinator.state.value != AiPostProcessingState.Idle
        val decision = aiDiagnosticsPreloadDecision(
            modelInstalled = uiState.selectedAiModelInstalled,
            operationActive = operationActive,
            matchingSessionLoaded = matchingSessionLoaded
        )

        // Opening the page starts a fresh volatile diagnostic conversation. The loaded model
        // itself remains alive and can be reused immediately.
        uiState = uiState.copy(
            showAiDiagnosticsWelcome = true,
            aiSelfTestResponse = null,
            aiSelfTestModel = null,
            aiSelfTestMetrics = null,
            error = null
        )

        when (decision) {
            AiDiagnosticsPreloadDecision.MODEL_MISSING -> {
                uiState = uiState.copy(
                    isAiModelPreloading = false,
                    isAiModelReady = false,
                    status = "Bitte zuerst das ausgewählte KI-Modell herunterladen.",
                    statusKind = StatusMessageKind.IMPORTANT,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = null,
                    cannaBotMode = CannaBotMode.IDLE
                )
            }
            AiDiagnosticsPreloadDecision.OPERATION_ACTIVE -> {
                uiState = uiState.copy(
                    isAiModelPreloading = false,
                    isAiModelReady = false,
                    status = "KI-Modell kann noch nicht geladen werden.",
                    statusKind = StatusMessageKind.IMPORTANT,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail =
                        "Zuerst den laufenden Vorgang abschließen und die KI-Diagnose erneut öffnen.",
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            AiDiagnosticsPreloadDecision.ALREADY_LOADED -> {
                AiEngineSessionManager.resetTestConversation()
                val report = AiEngineSessionManager.runtimeReport(model, modelFile, configuration)
                val backendDiagnostic = report?.let {
                    "Backend angefordert: ${it.requestedBackend} · aktiv: ${it.activeBackend} · ${it.activeCpuBackend}."
                }
                uiState = uiState.copy(
                    isAiModelPreloading = false,
                    isAiModelReady = true,
                    status = "KI-Modell ist geladen.",
                    statusKind = StatusMessageKind.COMPLETION,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = "${model.modelLabel} wird aus dem RAM wiederverwendet.",
                    diagnostics = (uiState.diagnostics + listOfNotNull(
                        "KI-Diagnose geöffnet: ${model.modelLabel} ist bereits im RAM.",
                        backendDiagnostic
                    )).takeLast(12),
                    cannaBotMode = CannaBotMode.IDLE
                )
            }
            AiDiagnosticsPreloadDecision.START -> {
                AiEngineSessionManager.resetTestConversation()
                uiState = uiState.copy(
                    isBusy = true,
                    isAiModelPreloading = true,
                    isAiModelReady = false,
                    status = "KI-Modell wird geladen …",
                    statusKind = StatusMessageKind.IMPORTANT,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = "${model.modelLabel} wird für die KI-Diagnose vorbereitet.",
                    diagnostics = (uiState.diagnostics +
                        "KI-Diagnose geöffnet: Vorladen von ${model.modelLabel} gestartet.")
                        .takeLast(12),
                    cannaBotMode = CannaBotMode.WAITING
                )
                AiPostProcessingService.preloadModel(application, model)
            }
        }
        refreshAiDiagnosticsThermalStatus()
    }

    fun refreshAiDiagnosticsThermalStatus() {
        val thermalStatus = normalizeAiDiagnosticsThermalStatus(
            AiHardwareProbe.readThermalStatus(application)
        )
        uiState = if (thermalStatus == null) {
            val alreadyReported = uiState.status == "Keine Thermaldaten verfügbar."
            uiState.copy(
                aiDiagnosticsThermalStatus = null,
                status = "Keine Thermaldaten verfügbar.",
                statusKind = StatusMessageKind.IMPORTANT,
                statusEventId = if (alreadyReported) {
                    uiState.statusEventId
                } else {
                    uiState.statusEventId + 1L
                }
            )
        } else {
            uiState.copy(aiDiagnosticsThermalStatus = thermalStatus)
        }
    }

    fun startAiSelfTest() {
        if (!canSendAiDiagnosticsRequest(
                modelInstalled = uiState.selectedAiModelInstalled,
                modelReady = uiState.isAiModelReady,
                modelPreloading = uiState.isAiModelPreloading,
                operationActive = uiState.isBusy,
                prompt = uiState.aiTestPrompt
            )
        ) return
        val selectedModelFile = aiModelFile(uiState.selectedAiModel)
        val performanceConfiguration = aiPerformancePreferences.load(uiState.selectedAiModel)
        val modelAlreadyLoaded = AiEngineSessionManager.isLoaded(
            uiState.selectedAiModel,
            selectedModelFile,
            performanceConfiguration
        )
        uiState = uiState.copy(
            isBusy = true,
            isAiSelfTest = true,
            progress = null,
            error = null,
            status = "KI-Test wird vorbereitet …",
            activityDetail = if (modelAlreadyLoaded) {
                "${uiState.selectedAiModel.modelLabel} ist bereits im RAM."
            } else {
                "${uiState.selectedAiModel.modelLabel} wird lokal geladen."
            },
            diagnostics = (uiState.diagnostics +
                "Freier KI-Test mit ${uiState.selectedAiModel.modelLabel} gestartet.")
                .takeLast(12),
            cannaBotMode = CannaBotMode.REVIEW
        )
        AiPostProcessingService.startSelfTest(
            application,
            uiState.selectedAiModel,
            uiState.aiTestPrompt
        )
    }

    fun resetAiTestConversation() {
        if (uiState.isBusy || uiState.isAiModelPreloading) return
        AiEngineSessionManager.resetTestConversation()
        uiState = uiState.copy(
            showAiDiagnosticsWelcome = true,
            aiSelfTestResponse = null,
            aiSelfTestModel = null,
            aiSelfTestMetrics = null,
            error = null,
            status = "Unterhaltung wurde zurückgesetzt.",
            statusKind = StatusMessageKind.IMPORTANT,
            statusEventId = uiState.statusEventId + 1L,
            activityDetail = "Die nächste Anfrage beginnt mit einem neuen Gesprächskontext.",
            diagnostics = (uiState.diagnostics + "Flüchtige KI-Unterhaltung zurückgesetzt.")
                .takeLast(12),
            cannaBotMode = CannaBotMode.IDLE
        )
    }

    fun selectPerformanceProfileModel(model: AiModel) {
        if (uiState.isBusy) return
        uiState = uiState.copy(
            performanceProfileModel = model,
            aiPerformanceConfiguration = aiPerformancePreferences.load(model),
            aiPerformanceJson = "",
            aiPerformanceMessage = null,
            aiBenchmarkResult = null,
            performanceModelLayerCount = inspectAiModelLayers(model)
        )
        refreshAiHardware()
    }

    fun updateAiPerformanceConfiguration(configuration: LocalAiConfiguration) {
        if (uiState.isBusy) return
        val model = uiState.performanceProfileModel
        val previous = aiPerformancePreferences.load(model)
        val saved = aiPerformancePreferences.save(model, configuration)
        if (model == uiState.selectedAiModel && previous.runtimeKey() != saved.runtimeKey()) {
            AiEngineSessionManager.release(model)
        }
        uiState = uiState.copy(
            aiPerformanceConfiguration = saved,
            aiPerformanceJson = "",
            aiPerformanceMessage = "Einstellungen für ${model.modelLabel} gespeichert.",
            aiBenchmarkResult = null,
            status = "KI-Leistung und Hardware gespeichert.",
            statusKind = StatusMessageKind.IMPORTANT,
            statusEventId = uiState.statusEventId + 1L,
            cannaBotMode = CannaBotMode.IDLE
        )
    }

    fun resetAiPerformanceConfiguration() {
        if (uiState.isBusy) return
        val model = uiState.performanceProfileModel
        AiEngineSessionManager.release(model)
        val reset = aiPerformancePreferences.reset(model)
        uiState = uiState.copy(
            aiPerformanceConfiguration = reset,
            aiPerformanceJson = "",
            aiPerformanceMessage = "Standardwerte für ${model.modelLabel} wiederhergestellt.",
            aiBenchmarkResult = null,
            status = "KI-Leistung und Hardware auf Standard zurückgesetzt.",
            statusKind = StatusMessageKind.IMPORTANT,
            statusEventId = uiState.statusEventId + 1L,
            cannaBotMode = CannaBotMode.IDLE
        )
    }

    fun copyAiPerformanceConfiguration(target: AiModel) {
        if (uiState.isBusy) return
        val source = uiState.performanceProfileModel
        val copied = aiPerformancePreferences.copy(source, target)
        AiEngineSessionManager.release(target)
        uiState = uiState.copy(
            aiPerformanceMessage = "Profil von ${source.modelLabel} nach ${target.modelLabel} kopiert.",
            aiPerformanceConfiguration = if (target == source) copied else uiState.aiPerformanceConfiguration
        )
    }

    fun exportAiPerformanceConfiguration() {
        if (uiState.isBusy) return
        uiState = uiState.copy(
            aiPerformanceJson = aiPerformancePreferences.exportJson(uiState.performanceProfileModel),
            aiPerformanceMessage = "Profil-JSON erstellt."
        )
    }

    fun updateAiPerformanceJson(value: String) {
        if (uiState.isBusy) return
        uiState = uiState.copy(aiPerformanceJson = value, aiPerformanceMessage = null)
    }

    fun importAiPerformanceConfiguration() {
        if (uiState.isBusy || uiState.aiPerformanceJson.isBlank()) return
        val model = uiState.performanceProfileModel
        runCatching {
            aiPerformancePreferences.importJson(model, uiState.aiPerformanceJson)
        }.onSuccess { imported ->
            AiEngineSessionManager.release(model)
            uiState = uiState.copy(
                aiPerformanceConfiguration = imported,
                aiPerformanceMessage = "Profil für ${model.modelLabel} importiert.",
                aiBenchmarkResult = null,
                status = "KI-Leistungsprofil importiert.",
                cannaBotMode = CannaBotMode.IDLE
            )
        }.onFailure { failure ->
            uiState = uiState.copy(
                aiPerformanceMessage = failure.localizedMessage ?: "Profil konnte nicht importiert werden.",
                error = failure.localizedMessage ?: "Profil konnte nicht importiert werden."
            )
        }
    }

    fun refreshAiHardware() {
        val snapshot = runCatching { AiHardwareProbe.read(application) }.getOrNull()
        uiState = uiState.copy(aiHardwareSnapshot = snapshot)
    }

    fun startAiPerformanceBenchmark() {
        if (uiState.isBusy || aiBenchmarkJob?.isActive == true) return
        val model = uiState.performanceProfileModel
        val modelFile = aiModelFile(model)
        val configuration = aiPerformancePreferences.load(model)
        if (!modelFile.isFile || modelFile.length() < model.minimumBytes) {
            uiState = uiState.copy(
                error = "${model.modelLabel} ist nicht vollständig installiert.",
                status = "Leistungstest kann nicht starten."
            )
            return
        }
        uiState = uiState.copy(
            isBusy = true,
            isAiBenchmarkRunning = true,
            aiBenchmarkProgress = 0f,
            aiBenchmarkResult = null,
            aiPerformanceMessage = null,
            error = null,
            status = "KI-Leistungstest wird vorbereitet …",
            activityDetail = "${model.modelLabel} · reproduzierbarer lokaler Benchmark",
            cannaBotMode = CannaBotMode.RUNNING
        )
        cue(CannaBotCue.RUNNING_RIGHT)
        aiBenchmarkJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runAiPerformanceBenchmark(model, modelFile, configuration)
                }
                uiState = uiState.copy(
                    isBusy = false,
                    isAiBenchmarkRunning = false,
                    aiBenchmarkProgress = 1f,
                    aiBenchmarkResult = result,
                    aiHardwareSnapshot = runCatching { AiHardwareProbe.read(application) }.getOrNull(),
                    aiPerformanceMessage = "Leistungstest mit ${result.runs.size} Messdurchläufen abgeschlossen.",
                    status = "KI-Leistungstest abgeschlossen.",
                    activityDetail = null,
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.SUCCESS)
            } catch (cancelled: CancellationException) {
                uiState = uiState.copy(
                    isBusy = false,
                    isAiBenchmarkRunning = false,
                    activityDetail = null,
                    aiPerformanceMessage = "Leistungstest abgebrochen.",
                    status = "KI-Leistungstest abgebrochen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
            } catch (failure: Throwable) {
                val message = failure.localizedMessage ?: "Der KI-Leistungstest ist fehlgeschlagen."
                uiState = uiState.copy(
                    isBusy = false,
                    isAiBenchmarkRunning = false,
                    activityDetail = null,
                    aiPerformanceMessage = message,
                    error = message,
                    status = "KI-Leistungstest fehlgeschlagen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
            } finally {
                AiEngineSessionManager.release(model)
                aiBenchmarkJob = null
            }
        }
    }

    fun cancelAiPerformanceBenchmark() {
        aiBenchmarkJob?.cancel()
    }

    private suspend fun runAiPerformanceBenchmark(
        model: AiModel,
        modelFile: File,
        configuration: LocalAiConfiguration
    ): AiBenchmarkResult {
        val prompt = benchmarkPrompt(configuration.benchmarkPromptCharacters)
        val totalRuns = configuration.benchmarkWarmupRuns + configuration.benchmarkMeasuredRuns
        val measured = mutableListOf<AiBenchmarkRun>()
        repeat(totalRuns) { runIndex ->
            currentCoroutineContext().ensureActive()
            val hardwareBefore = AiHardwareProbe.read(application)
            require(
                hardwareBefore.batteryPercent < 0 ||
                    hardwareBefore.batteryPercent >= configuration.benchmarkMinimumBatteryPercent
            ) {
                "Akkustand ${hardwareBefore.batteryPercent} % liegt unter der Benchmark-Grenze von ${configuration.benchmarkMinimumBatteryPercent} %."
            }
            require(!configuration.benchmarkRequiresCharging || hardwareBefore.charging) {
                "Der Leistungstest ist laut Einstellung nur am Ladegerät erlaubt."
            }
            require(hardwareBefore.thermalStatus <= configuration.benchmarkMaximumThermalStatus) {
                "Wärmezustand ${hardwareBefore.thermalStatus} überschreitet die Benchmark-Grenze ${configuration.benchmarkMaximumThermalStatus}."
            }
            AiHardwareProbe.checkMemory(application, modelFile, configuration)
            AiEngineSessionManager.release(model)
            val runStarted = android.os.SystemClock.elapsedRealtime()
            val session = AiEngineSessionManager.withModel(
                model,
                modelFile,
                configuration
            ) { engine, _ ->
                engine.resetTestConversation()
                val generation = engine.generateTest(
                    prompt,
                    configuration.benchmarkOutputTokens
                )
                generation to engine.runtimeReport()
            }
            val generation = session.value.first
            val report = session.value.second
            val hardwareAfter = AiHardwareProbe.read(application)
            if (runIndex >= configuration.benchmarkWarmupRuns) {
                measured += AiBenchmarkRun(
                    runNumber = runIndex - configuration.benchmarkWarmupRuns + 1,
                    modelLoadMs = session.info.modelLoadMs,
                    promptTokens = generation.metrics.promptTokens,
                    generatedTokens = generation.metrics.generatedTokens,
                    promptProcessingMs = generation.metrics.promptProcessingMs,
                    timeToFirstTokenMs = generation.metrics.timeToFirstTokenMs,
                    answerGenerationMs = generation.metrics.answerGenerationMs,
                    totalMs = (android.os.SystemClock.elapsedRealtime() - runStarted).coerceAtLeast(0L),
                    appPssBytes = hardwareAfter.appPssBytes,
                    thermalStatus = hardwareAfter.thermalStatus,
                    runtimeReport = report
                )
            }
            AiEngineSessionManager.release(model)
            withContext(Dispatchers.Main) {
                uiState = uiState.copy(
                    aiBenchmarkProgress = (runIndex + 1).toFloat() / totalRuns,
                    status = if (runIndex < configuration.benchmarkWarmupRuns) {
                        "KI-Leistungstest: Aufwärmdurchlauf ${runIndex + 1} von ${configuration.benchmarkWarmupRuns} …"
                    } else {
                        "KI-Leistungstest: Messung ${runIndex - configuration.benchmarkWarmupRuns + 1} von ${configuration.benchmarkMeasuredRuns} …"
                    },
                    activityDetail = "Backend ${report.activeBackend} · ${report.activeCpuBackend}"
                )
            }
            if (runIndex + 1 < totalRuns && configuration.benchmarkPauseSeconds > 0) {
                delay(configuration.benchmarkPauseSeconds * 1_000L)
            }
        }
        return AiBenchmarkResult(model, configuration, measured)
    }

    private fun benchmarkPrompt(length: Int): String {
        val sentence = "Korrigiere diesen neutralen deutschen Testsatz sorgfältig und antworte knapp. "
        return buildString(length + sentence.length) {
            while (this.length < length) append(sentence)
        }.take(length)
    }

    private fun inspectAiModelLayers(model: AiModel): Int {
        val file = aiModelFile(model)
        if (!file.isFile || file.length() < model.minimumBytes) return 0
        return runCatching { LocalAiEngine.inspectModelLayerCount(file.absolutePath) }
            .getOrDefault(0)
    }

    fun updateTranscriptText(index: Int, text: String) {
        if (!uiState.isEditingTranscript || uiState.isAiPostProcessing) return
        val groupStartMs = uiState.editingTranscriptGroupStartMs ?: return
        val segment = uiState.segments.getOrNull(index) ?: return
        if (transcriptGroupStartMs(segment.startMs) != groupStartMs) return
        uiState = uiState.copy(
            draftSegments = uiState.draftSegments.withUpdatedTranscriptText(index, text)
        )
    }

    fun cancelTranscriptEditing() {
        if (!uiState.isEditingTranscript || uiState.isAiPostProcessing) return
        uiState = uiState.copy(
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList()
        )
    }

    fun applyTranscriptEdits() {
        val groupStartMs = uiState.editingTranscriptGroupStartMs
        if (
            !uiState.isEditingTranscript || uiState.isAiPostProcessing ||
            groupStartMs == null ||
            uiState.draftSegments.size != uiState.segments.size
        ) {
            return
        }
        val appliedSegments = applyTranscriptGroupEdits(
            original = uiState.segments,
            draft = uiState.draftSegments,
            groupStartMs = groupStartMs
        )
        uiState = uiState.copy(
            segments = appliedSegments,
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            status = "Textkorrekturen übernommen. Die Exporte sind aktualisiert.",
            cannaBotMode = CannaBotMode.IDLE
        )
        persistCurrentTranscript(appliedSegments)
        cue(CannaBotCue.WAVING)
    }

    fun setAiPostProcessingEnabled(enabled: Boolean) {
        if (uiState.isBusy || uiState.isAiModelPreloading) return
        aiPreferences.setEnabled(enabled)
        if (!enabled) aiPreferences.setAutomatic(false)
        uiState = uiState.copy(
            aiPostProcessingEnabled = enabled,
            automaticAiPostProcessingEnabled = enabled && uiState.automaticAiPostProcessingEnabled
        )
    }

    fun setAutomaticAiPostProcessingEnabled(enabled: Boolean) {
        if (uiState.isBusy || uiState.isAiModelPreloading || !uiState.aiPostProcessingEnabled) return
        aiPreferences.setAutomatic(enabled)
        uiState = uiState.copy(automaticAiPostProcessingEnabled = enabled)
    }

    fun selectAiModel(model: AiModel) {
        if (uiState.isBusy || uiState.isAiModelPreloading) return
        AiEngineSessionManager.releaseIfDifferent(model)
        aiPreferences.setSelectedModel(model)
        refreshAiModelInstallations(model)
        uiState = uiState.copy(
            isAiModelReady = false,
            showAiDiagnosticsWelcome = true,
            aiSelfTestResponse = null,
            aiSelfTestModel = null,
            aiSelfTestMetrics = null,
            performanceProfileModel = model,
            aiPerformanceConfiguration = aiPerformancePreferences.load(model),
            aiPerformanceJson = "",
            aiBenchmarkResult = null,
            performanceModelLayerCount = inspectAiModelLayers(model)
        )
    }

    fun downloadAiModel(model: AiModel = uiState.selectedAiModel) {
        if (uiState.isBusy || uiState.isAiModelPreloading) return
        AiEngineSessionManager.releaseIfDifferent(model)
        aiPreferences.setSelectedModel(model)
        uiState = uiState.copy(
            selectedAiModel = model,
            isBusy = true,
            progress = 0f,
            downloadingAiModel = model,
            aiDownloadedBytes = 0L,
            aiDownloadTotalBytes = 0L,
            error = null,
            status = "${model.modelLabel} wird im Hintergrund heruntergeladen …",
            activityDetail = "KI-Modell wird sicher auf dem Gerät gespeichert.",
            cannaBotMode = CannaBotMode.RUNNING
        )
        lastDownloadAnimationBucket = -1
        cue(CannaBotCue.RUNNING_RIGHT)
        AiModelDownloadService.start(application, model)
    }

    fun deleteAllAiModels() {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                progress = null,
                error = null,
                status = "Alle KI-Modelle werden gelöscht …",
                cannaBotMode = CannaBotMode.WAITING
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    AiEngineSessionManager.release()
                    AiModel.entries.forEach { model ->
                        val file = aiModelFile(model)
                        check(!file.exists() || file.delete()) {
                            "${model.modelLabel} konnte nicht gelöscht werden."
                        }
                        val partial = partialAiModelFile(model)
                        check(!partial.exists() || partial.delete()) {
                            "Der unvollständige Download von ${model.modelLabel} konnte nicht gelöscht werden."
                        }
                    }
                }
            }.onSuccess {
                refreshAiModelInstallations(uiState.selectedAiModel)
                uiState = uiState.copy(
                    isBusy = false,
                    isAiModelReady = false,
                    status = "Alle KI-Modelle wurden gelöscht.",
                    cannaBotMode = CannaBotMode.IDLE
                )
            }.onFailure(::fail)
        }
    }

    fun deleteAiModel(model: AiModel) {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                progress = null,
                error = null,
                status = "${model.modelLabel} wird gelöscht …",
                cannaBotMode = CannaBotMode.WAITING
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    AiEngineSessionManager.release(model)
                    val file = aiModelFile(model)
                    check(!file.exists() || file.delete()) { "Das KI-Modell konnte nicht gelöscht werden." }
                    val partial = partialAiModelFile(model)
                    check(!partial.exists() || partial.delete()) {
                        "Der unvollständige KI-Download konnte nicht gelöscht werden."
                    }
                }
            }.onSuccess {
                refreshAiModelInstallations(uiState.selectedAiModel)
                uiState = uiState.copy(
                    isBusy = false,
                    status = "${model.modelLabel} wurde gelöscht.",
                    cannaBotMode = CannaBotMode.IDLE
                )
            }.onFailure(::fail)
        }
    }

    fun downloadModel(model: WhisperModel = uiState.selectedModel) {
        if (uiState.isBusy) return
        preferences.edit().putString(SELECTED_MODEL_KEY, model.id).apply()
        uiState = uiState.copy(
            isBusy = true,
            progress = 0f,
            downloadingModel = model,
            downloadedBytes = 0L,
            downloadTotalBytes = 0L,
            error = null,
            status = "${model.modelLabel} wird im Hintergrund heruntergeladen …",
            cannaBotMode = CannaBotMode.RUNNING
        )
        lastDownloadAnimationBucket = -1
        cue(CannaBotCue.RUNNING_RIGHT)
        ModelDownloadService.start(application, model)
    }

    fun deleteModel(model: WhisperModel = uiState.selectedModel) {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                progress = null,
                error = null,
                status = "${model.modelLabel} wird gelöscht …",
                cannaBotMode = CannaBotMode.WAITING
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = modelFile(model)
                    check(!file.exists() || file.delete()) {
                        "Das Modell konnte nicht gelöscht werden."
                    }
                    val partial = partialModelFile(model)
                    check(!partial.exists() || partial.delete()) {
                        "Der unvollständige Download konnte nicht gelöscht werden."
                    }
                }
            }.onSuccess {
                refreshModelInstallations(uiState.selectedModel)
                uiState = uiState.copy(
                    isBusy = false,
                    status = "${model.modelLabel} wurde gelöscht.",
                    cannaBotMode = CannaBotMode.IDLE
                )
            }.onFailure { throwable -> fail(throwable) }
        }
    }

    fun deleteAllModels() {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                progress = null,
                error = null,
                status = "Alle Whisper-Modelle werden gelöscht …",
                cannaBotMode = CannaBotMode.WAITING
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    WhisperModel.entries.forEach { model ->
                        val file = modelFile(model)
                        check(!file.exists() || file.delete()) {
                            "${model.modelLabel} konnte nicht gelöscht werden."
                        }
                        val partial = partialModelFile(model)
                        check(!partial.exists() || partial.delete()) {
                            "Der unvollständige Download von ${model.modelLabel} konnte nicht gelöscht werden."
                        }
                    }
                }
            }.onSuccess {
                refreshModelInstallations(uiState.selectedModel)
                uiState = uiState.copy(
                    isBusy = false,
                    status = "Alle Whisper-Modelle wurden gelöscht.",
                    cannaBotMode = CannaBotMode.IDLE
                )
            }.onFailure { throwable -> fail(throwable) }
        }
    }

    fun downloadVadModel() {
        if (uiState.isBusy) return
        uiState = uiState.copy(
            isBusy = true,
            isVadDownloading = true,
            progress = 0f,
            error = null,
            status = "${SileroVadModel.modelLabel} wird heruntergeladen …",
            cannaBotMode = CannaBotMode.RUNNING
        )
        cue(CannaBotCue.RUNNING_RIGHT)
        VadModelDownloadService.start(application)
    }

    fun deleteVadModel() {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(isBusy = true, progress = null, status = "Silero VAD wird gelöscht …")
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = vadModelFile()
                    check(!file.exists() || file.delete()) { "Das VAD-Modell konnte nicht gelöscht werden." }
                    val partial = partialVadModelFile()
                    check(!partial.exists() || partial.delete()) { "Der unvollständige VAD-Download konnte nicht gelöscht werden." }
                }
            }.onSuccess {
                refreshVadModelInstallation()
                uiState = uiState.copy(isBusy = false, status = "Silero VAD wurde gelöscht.", cannaBotMode = CannaBotMode.IDLE)
            }.onFailure(::fail)
        }
    }

    fun transcribe() {
        val uri = uiState.selectedAudio ?: return
        if (!uiState.modelReady || uiState.isBusy) return
        stopPlayback(release = false)
        transcriptResultPersistence.clear()
        uiState = uiState.withRecalculatedTranscriptionEstimate()
        uiState = uiState.copy(
            isBusy = true,
            isTranscribing = true,
            isCancellationRequested = false,
            progress = 0f,
            elapsedSeconds = 0L,
            runtimeEstimateAnnouncementId = 0L,
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            rawWhisperSegments = emptyList(),
            segments = emptyList(),
            detectedLanguage = null,
            completedModel = uiState.selectedModel,
            transcriptionDurationSeconds = null,
            vadProcessingSummary = null,
            diagnostics = emptyList(),
            error = null,
            activityDetail = null,
            status = "Transkription wird im Hintergrund vorbereitet …",
            cannaBotMode = CannaBotMode.RUNNING
        )
        ensureTranscriptionElapsedTimer(System.currentTimeMillis())
        cue(CannaBotCue.RUNNING_RIGHT)
        TranscriptionService.start(
            context = application,
            uri = uri,
            fileName = uiState.selectedFileName ?: displayName(uri),
            model = uiState.selectedModel,
            language = uiState.language,
            settings = uiState.whisperSettings
        )
    }

    fun cancelTranscription() {
        if (!uiState.isTranscribing || uiState.isCancellationRequested) return
        uiState = uiState.copy(
            isCancellationRequested = true,
            progress = null,
            status = "Transkription wird abgebrochen …",
            activityDetail = "Das Abbruchsignal wurde an die laufende Verarbeitung gesendet.",
            cannaBotMode = CannaBotMode.WAITING
        )
        TranscriptionService.cancel(application)
    }

    private fun startPlaybackTimer() {
        playbackTimer?.cancel()
        playbackTimer = viewModelScope.launch {
            while (uiState.isPlaying) {
                uiState = uiState.copy(
                    playbackPositionMs = audioPlayer.positionMs(),
                    audioDurationMs = audioPlayer.durationMs().takeIf { it > 0L }
                        ?: uiState.audioDurationMs
                )
                delay(100L)
            }
        }
    }

    private fun stopPlaybackTimer() {
        playbackTimer?.cancel()
        playbackTimer = null
    }

    private fun ensureTranscriptionElapsedTimer(startedAtEpochMs: Long) {
        val safeStartedAtEpochMs = startedAtEpochMs.takeIf { it > 0L }
            ?: (System.currentTimeMillis() - uiState.elapsedSeconds * 1_000L)
        if (
            transcriptionElapsedTimer?.isActive == true &&
            transcriptionStartedAtEpochMs == safeStartedAtEpochMs
        ) {
            return
        }

        transcriptionElapsedTimer?.cancel()
        transcriptionStartedAtEpochMs = safeStartedAtEpochMs
        transcriptionElapsedTimer = viewModelScope.launch {
            while (isActive && uiState.isTranscribing) {
                val elapsed = elapsedSecondsSince(
                    startedAtEpochMs = transcriptionStartedAtEpochMs,
                    nowEpochMs = System.currentTimeMillis()
                )
                if (uiState.elapsedSeconds != elapsed) {
                    uiState = uiState.copy(elapsedSeconds = elapsed)
                }
                delay(1_000L)
            }
        }
    }

    private fun stopTranscriptionElapsedTimer() {
        transcriptionElapsedTimer?.cancel()
        transcriptionElapsedTimer = null
        transcriptionStartedAtEpochMs = 0L
    }

    private fun stopPlayback(release: Boolean) {
        stopPlaybackTimer()
        audioPlayer.pause()
        if (release) audioPlayer.release()
        uiState = uiState.copy(isPlaying = false)
    }

    private fun handleModelDownloadState(downloadState: ModelDownloadState) {
        when (downloadState) {
            ModelDownloadState.Idle -> Unit
            is ModelDownloadState.Running -> {
                val total = downloadState.totalBytes
                uiState = uiState.copy(
                    isBusy = true,
                    downloadingModel = downloadState.model,
                    downloadedBytes = downloadState.downloadedBytes,
                    downloadTotalBytes = total,
                    progress = if (total > 0L) {
                        (downloadState.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                    } else null,
                    error = null,
                    status = if (downloadState.resumed) {
                        "${downloadState.model.modelLabel}: Download wird im Hintergrund fortgesetzt …"
                    } else {
                        "${downloadState.model.modelLabel} wird im Hintergrund heruntergeladen …"
                    },
                    cannaBotMode = CannaBotMode.RUNNING
                )
                if (total > 0L) {
                    val animationBucket =
                        ((downloadState.downloadedBytes * 4L) / total).toInt().coerceIn(0, 4)
                    if (animationBucket > lastDownloadAnimationBucket) {
                        lastDownloadAnimationBucket = animationBucket
                        if (animationBucket > 0) {
                            cue(
                                if (animationBucket % 2 == 0) CannaBotCue.RUNNING_RIGHT
                                else CannaBotCue.RUNNING_LEFT
                            )
                        }
                    }
                }
            }
            is ModelDownloadState.Verifying -> {
                uiState = uiState.copy(
                    isBusy = true,
                    downloadingModel = downloadState.model,
                    downloadedBytes = downloadState.downloadedBytes,
                    downloadTotalBytes = downloadState.downloadedBytes,
                    progress = null,
                    status = "Download vollständig · Prüfsumme wird kontrolliert …",
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            is ModelDownloadState.Completed -> {
                preferences.edit().putString(SELECTED_MODEL_KEY, downloadState.model.id).apply()
                refreshModelInstallations(downloadState.model)
                uiState = uiState.copy(
                    isBusy = false,
                    progress = null,
                    downloadingModel = null,
                    error = null,
                    status = "${downloadState.model.modelLabel} ist installiert und aktiv.",
                    runtimeEstimateAnnouncementId = if (
                        uiState.selectedAudio != null && uiState.audioDurationMs > 0L
                    ) {
                        uiState.runtimeEstimateAnnouncementId + 1L
                    } else {
                        uiState.runtimeEstimateAnnouncementId
                    },
                    cannaBotMode = if (
                        uiState.selectedAudio != null && uiState.audioDurationMs > 0L
                    ) {
                        if (uiState.isWaveformLoading) CannaBotMode.WAITING else CannaBotMode.REVIEW
                    } else {
                        CannaBotMode.IDLE
                    }
                )
                cue(CannaBotCue.SUCCESS)
                ModelDownloadCoordinator.reset()
            }
            is ModelDownloadState.Failed -> {
                uiState = uiState.copy(
                    isBusy = false,
                    progress = null,
                    downloadingModel = null,
                    downloadedBytes = downloadState.downloadedBytes,
                    downloadTotalBytes = downloadState.totalBytes,
                    error = downloadState.message,
                    status = if (downloadState.downloadedBytes > 0L) {
                        "Download unterbrochen. Beim nächsten Start wird er fortgesetzt."
                    } else {
                        "Modelldownload fehlgeschlagen."
                    },
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
                ModelDownloadCoordinator.reset()
            }
        }
    }

    private fun handleAiModelDownloadState(downloadState: AiModelDownloadState) {
        when (downloadState) {
            AiModelDownloadState.Idle -> Unit
            is AiModelDownloadState.Running -> {
                val total = downloadState.totalBytes
                uiState = uiState.copy(
                    isBusy = true,
                    selectedAiModel = downloadState.model,
                    downloadingAiModel = downloadState.model,
                    aiDownloadedBytes = downloadState.downloadedBytes,
                    aiDownloadTotalBytes = total,
                    progress = if (total > 0L) {
                        (downloadState.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                    } else null,
                    error = null,
                    status = if (downloadState.resumed) {
                        "${downloadState.model.modelLabel}: Download wird fortgesetzt …"
                    } else {
                        "${downloadState.model.modelLabel} wird heruntergeladen …"
                    },
                    activityDetail = "Lokales KI-Modell · ${formatDownloadSize(downloadState.downloadedBytes)}",
                    cannaBotMode = CannaBotMode.RUNNING
                )
            }
            is AiModelDownloadState.Verifying -> {
                uiState = uiState.copy(
                    isBusy = true,
                    downloadingAiModel = downloadState.model,
                    aiDownloadedBytes = downloadState.downloadedBytes,
                    aiDownloadTotalBytes = downloadState.downloadedBytes,
                    progress = null,
                    status = "KI-Modell wird auf Vollständigkeit geprüft …",
                    activityDetail = "SHA-256-Prüfsumme wird kontrolliert.",
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            is AiModelDownloadState.Completed -> {
                aiPreferences.setSelectedModel(downloadState.model)
                refreshAiModelInstallations(downloadState.model)
                uiState = uiState.copy(
                    isBusy = false,
                    progress = null,
                    downloadingAiModel = null,
                    error = null,
                    status = "${downloadState.model.modelLabel} ist installiert und ausgewählt.",
                    activityDetail = null,
                    performanceProfileModel = downloadState.model,
                    aiPerformanceConfiguration = aiPerformancePreferences.load(downloadState.model),
                    performanceModelLayerCount = inspectAiModelLayers(downloadState.model),
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.SUCCESS)
                AiModelDownloadCoordinator.reset()
            }
            is AiModelDownloadState.Failed -> {
                refreshAiModelInstallations(uiState.selectedAiModel)
                uiState = uiState.copy(
                    isBusy = false,
                    progress = null,
                    downloadingAiModel = null,
                    aiDownloadedBytes = downloadState.downloadedBytes,
                    aiDownloadTotalBytes = downloadState.totalBytes,
                    error = downloadState.message,
                    status = if (downloadState.downloadedBytes > 0L) {
                        "KI-Download unterbrochen. Er wird beim nächsten Versuch fortgesetzt."
                    } else {
                        "KI-Modelldownload fehlgeschlagen."
                    },
                    activityDetail = null,
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
                AiModelDownloadCoordinator.reset()
            }
        }
    }

    private fun handleVadModelDownloadState(downloadState: VadModelDownloadState) {
        when (downloadState) {
            VadModelDownloadState.Idle -> Unit
            is VadModelDownloadState.Running -> uiState = uiState.copy(
                isBusy = true,
                isVadDownloading = true,
                vadDownloadedBytes = downloadState.downloadedBytes,
                vadDownloadTotalBytes = downloadState.totalBytes,
                progress = if (downloadState.totalBytes > 0L) downloadState.downloadedBytes.toFloat() / downloadState.totalBytes else null,
                status = if (downloadState.resumed) "Silero-VAD-Download wird fortgesetzt …" else "Silero VAD wird heruntergeladen …",
                cannaBotMode = CannaBotMode.RUNNING
            )
            is VadModelDownloadState.Verifying -> uiState = uiState.copy(
                isBusy = true, progress = null, status = "Silero VAD wird geprüft …", cannaBotMode = CannaBotMode.WAITING
            )
            VadModelDownloadState.Completed -> {
                refreshVadModelInstallation()
                uiState = uiState.copy(isBusy = false, isVadDownloading = false, progress = null, error = null,
                    status = "${SileroVadModel.modelLabel} ist installiert.", cannaBotMode = CannaBotMode.IDLE)
                cue(CannaBotCue.SUCCESS)
                VadModelDownloadCoordinator.reset()
            }
            is VadModelDownloadState.Failed -> {
                refreshVadModelInstallation()
                uiState = uiState.copy(isBusy = false, isVadDownloading = false, progress = null,
                    vadDownloadedBytes = downloadState.downloadedBytes, vadDownloadTotalBytes = downloadState.totalBytes,
                    error = downloadState.message, status = "VAD-Modelldownload unterbrochen.", cannaBotMode = CannaBotMode.IDLE)
                cue(CannaBotCue.FAILED)
                VadModelDownloadCoordinator.reset()
            }
        }
    }

    private fun handleAiPostProcessingState(state: AiPostProcessingState) {
        when (state) {
            AiPostProcessingState.Idle -> Unit
            is AiPostProcessingState.ModelPreloadStarting -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isAiModelPreloading = true,
                    isAiModelReady = false,
                    progress = null,
                    error = null,
                    status = "KI-Modell wird geladen …",
                    statusKind = StatusMessageKind.IMPORTANT,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = "${state.model.modelLabel} wird für die KI-Diagnose vorbereitet.",
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            is AiPostProcessingState.ModelPreloadRunning -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isAiModelPreloading = true,
                    isAiModelReady = false,
                    progress = null,
                    error = null,
                    status = state.status,
                    statusKind = StatusMessageKind.IMPORTANT,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = state.activityDetail,
                    diagnostics = state.diagnostics.takeLast(12),
                    cannaBotMode = CannaBotMode.WAITING
                )
            }
            is AiPostProcessingState.ModelPreloadCompleted -> {
                val selectedModelStillMatches = state.model == uiState.selectedAiModel
                uiState = uiState.copy(
                    isBusy = false,
                    isAiModelPreloading = false,
                    isAiModelReady = selectedModelStillMatches,
                    progress = null,
                    error = null,
                    status = if (selectedModelStillMatches) {
                        "KI-Modell ist geladen."
                    } else {
                        "Ausgewähltes KI-Modell wurde geändert."
                    },
                    statusKind = if (selectedModelStillMatches) {
                        StatusMessageKind.COMPLETION
                    } else {
                        StatusMessageKind.IMPORTANT
                    },
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = if (state.metrics.modelAlreadyLoaded) {
                        "${state.model.modelLabel} war bereits im RAM."
                    } else {
                        "${state.model.modelLabel} wurde in ${state.metrics.modelLoadMs} ms geladen."
                    },
                    diagnostics = state.diagnostics.takeLast(12),
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.SUCCESS)
                AiPostProcessingCoordinator.reset()
            }
            is AiPostProcessingState.ModelPreloadFailed -> {
                uiState = uiState.copy(
                    isBusy = false,
                    isAiModelPreloading = false,
                    isAiModelReady = false,
                    progress = null,
                    activityDetail = null,
                    diagnostics = state.diagnostics.takeLast(12),
                    error = state.message,
                    status = "KI-Modell konnte nicht geladen werden.",
                    statusKind = StatusMessageKind.ERROR,
                    statusEventId = uiState.statusEventId + 1L,
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
                AiPostProcessingCoordinator.reset()
            }
            is AiPostProcessingState.SelfTestStarting -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isAiSelfTest = true,
                    progress = null,
                    error = null,
                    status = "KI-Sitzung wird vorbereitet …",
                    activityDetail = "${state.model.modelLabel} wird für den freien KI-Test bereitgestellt.",
                    cannaBotMode = CannaBotMode.REVIEW
                )
            }
            is AiPostProcessingState.SelfTestRunning -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isAiSelfTest = true,
                    progress = null,
                    error = null,
                    status = state.status,
                    activityDetail = state.activityDetail,
                    diagnostics = state.diagnostics.takeLast(12),
                    cannaBotMode = CannaBotMode.REVIEW
                )
            }
            is AiPostProcessingState.SelfTestCompleted -> {
                uiState = uiState.copy(
                    isBusy = false,
                    isAiSelfTest = false,
                    progress = null,
                    activityDetail = "Antwort vollständig empfangen: ${state.response.length} Zeichen.",
                    diagnostics = (state.diagnostics +
                        "KI-Test erfolgreich: ${state.response.length} Zeichen empfangen.")
                        .takeLast(12),
                    showAiDiagnosticsWelcome = false,
                    aiTestPrompt = aiDiagnosticsPromptAfterResult(
                        currentPrompt = uiState.aiTestPrompt,
                        successful = true
                    ),
                    aiSelfTestResponse = state.response,
                    aiSelfTestModel = state.model,
                    aiSelfTestMetrics = state.metrics,
                    error = null,
                    status = "KI-Test erfolgreich.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.SUCCESS)
                AiPostProcessingCoordinator.reset()
            }
            is AiPostProcessingState.SelfTestFailed -> {
                uiState = uiState.copy(
                    isBusy = false,
                    isAiSelfTest = false,
                    progress = null,
                    activityDetail = null,
                    diagnostics = state.diagnostics.takeLast(12),
                    aiTestPrompt = aiDiagnosticsPromptAfterResult(
                        currentPrompt = uiState.aiTestPrompt,
                        successful = false
                    ),
                    error = state.message,
                    status = "KI-Test fehlgeschlagen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
                AiPostProcessingCoordinator.reset()
            }
            is AiPostProcessingState.Starting -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isAiPostProcessing = true,
                    progress = null,
                    error = null,
                    status = "Texte werden mit KI überarbeitet …",
                    activityDetail = "${state.model.modelLabel} wird lokal geladen.",
                    latestAiCorrectionTrace = null,
                    cannaBotMode = CannaBotMode.REVIEW
                )
            }
            is AiPostProcessingState.Running -> {
                val nextSegments = if (state.mode == AiPostProcessingMode.AUTOMATIC) {
                    state.correctedSegments
                } else {
                    uiState.segments
                }
                val nextDraft = if (state.mode == AiPostProcessingMode.MANUAL_GROUP) {
                    state.correctedSegments
                } else {
                    uiState.draftSegments
                }
                uiState = uiState.copy(
                    isBusy = true,
                    isAiPostProcessing = true,
                    progress = state.progress,
                    status = state.status,
                    activityDetail = state.activityDetail,
                    diagnostics = state.diagnostics.takeLast(12),
                    segments = nextSegments,
                    draftSegments = nextDraft,
                    latestAiCorrectionTrace = state.latestTrace,
                    error = null,
                    cannaBotMode = CannaBotMode.REVIEW
                )
            }
            is AiPostProcessingState.Completed -> {
                val manual = state.mode == AiPostProcessingMode.MANUAL_GROUP
                uiState = uiState.copy(
                    isBusy = false,
                    isAiPostProcessing = false,
                    progress = null,
                    activityDetail = null,
                    segments = if (manual) uiState.segments else state.segments,
                    draftSegments = if (manual) state.segments else emptyList(),
                    isEditingTranscript = manual,
                    editingTranscriptGroupStartMs = if (manual) state.groupStartMs else null,
                    diagnostics = (state.diagnostics +
                        "KI-Nachbearbeitung in ${state.durationSeconds} s abgeschlossen: ${state.checkedSegments} Segmente geprüft, ${state.appliedCorrections} Korrekturen ${if (manual) "im Entwurf" else "übernommen"}.${if (state.rejectedCorrections > 0) " ${state.rejectedCorrections} leere oder nicht lesbare Ergebnisse; Original beibehalten." else ""}").takeLast(12),
                    latestAiCorrectionTrace = state.latestTrace,
                    error = null,
                    status = if (manual) {
                        "KI-Prüfung abgeschlossen: ${state.checkedSegments} Segmente geprüft, ${state.appliedCorrections} Korrekturen im Entwurf."
                    } else {
                        "KI-Prüfung abgeschlossen: ${state.checkedSegments} Segmente geprüft, ${state.appliedCorrections} Korrekturen übernommen."
                    },
                    cannaBotMode = CannaBotMode.IDLE
                )
                if (!manual) persistCurrentTranscript(state.segments)
                cue(CannaBotCue.SUCCESS)
                AiPostProcessingCoordinator.reset()
            }
            is AiPostProcessingState.Failed -> {
                val manual = state.mode == AiPostProcessingMode.MANUAL_GROUP
                uiState = uiState.copy(
                    isBusy = false,
                    isAiPostProcessing = false,
                    progress = null,
                    activityDetail = null,
                    segments = state.originalSegments,
                    draftSegments = if (manual) state.originalSegments else emptyList(),
                    isEditingTranscript = manual,
                    editingTranscriptGroupStartMs = if (manual) state.groupStartMs else null,
                    diagnostics = state.diagnostics.takeLast(12),
                    error = state.message,
                    status = "KI-Nachbearbeitung fehlgeschlagen. Der ursprüngliche Text bleibt erhalten.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                if (!manual) persistCurrentTranscript(state.originalSegments)
                cue(CannaBotCue.FAILED)
                AiPostProcessingCoordinator.reset()
            }
        }
    }

    private fun handleTranscriptionState(state: TranscriptionState) {
        when (state) {
            TranscriptionState.Idle -> Unit
            is TranscriptionState.Starting -> {
                lastTranscriptionAnimationSection = -1
                uiState = uiState.copy(
                    isBusy = true,
                    isTranscribing = true,
                    isCancellationRequested = false,
                    progress = 0f,
                    error = null,
                    status = "Transkription wird im Hintergrund vorbereitet …",
                    statusKind = StatusMessageKind.IMPORTANT,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = state.fileName,
                    cannaBotMode = CannaBotMode.WAITING
                )
                ensureTranscriptionElapsedTimer(
                    System.currentTimeMillis() - uiState.elapsedSeconds * 1_000L
                )
            }
            is TranscriptionState.Running -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isTranscribing = true,
                    isCancellationRequested = false,
                    progress = state.progress,
                    elapsedSeconds = maxOf(
                        state.elapsedSeconds,
                        elapsedSecondsSince(state.startedAtEpochMs, System.currentTimeMillis())
                    ),
                    status = state.status,
                    statusKind = state.statusKind,
                    statusEventId = uiState.statusEventId + 1L,
                    activityDetail = state.activityDetail,
                    diagnostics = state.diagnostics,
                    rawWhisperSegments = emptyList(),
                    segments = state.committedSegments,
                    detectedLanguage = state.detectedLanguage,
                    completedModel = state.model,
                    transcriptionDurationSeconds = null,
                    vadProcessingSummary = null,
                    error = null,
                    cannaBotMode = CannaBotMode.RUNNING
                )
                ensureTranscriptionElapsedTimer(state.startedAtEpochMs)
                if (state.sectionNumber != lastTranscriptionAnimationSection) {
                    lastTranscriptionAnimationSection = state.sectionNumber
                    cue(
                        if (state.sectionNumber % 2 == 0) CannaBotCue.RUNNING_LEFT
                        else CannaBotCue.RUNNING_RIGHT
                    )
                }
            }
            is TranscriptionState.Completed -> {
                stopTranscriptionElapsedTimer()
                val timelineSegments = buildTranscriptTimeline(
                    whisperSegments = state.segments,
                    audioDurationMs = uiState.audioDurationMs
                )
                val canRunAutomaticAi = state.segments.isNotEmpty() &&
                    uiState.aiPostProcessingEnabled &&
                    uiState.automaticAiPostProcessingEnabled &&
                    uiState.selectedAiModelInstalled
                val automaticAiMissingModel = state.segments.isNotEmpty() &&
                    uiState.aiPostProcessingEnabled &&
                    uiState.automaticAiPostProcessingEnabled &&
                    !uiState.selectedAiModelInstalled
                uiState = uiState.copy(
                    isBusy = canRunAutomaticAi,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    rawWhisperSegments = state.segments,
                    segments = timelineSegments,
                    isEditingTranscript = false,
                    editingTranscriptGroupStartMs = null,
                    draftSegments = emptyList(),
                    detectedLanguage = state.detectedLanguage,
                    completedModel = state.model,
                    transcriptionDurationSeconds = state.transcriptionDurationSeconds,
                    vadProcessingSummary = state.vadSummary,
                    error = null,
                    status = if (canRunAutomaticAi) {
                        "Transkription fertig. Texte werden jetzt mit KI überarbeitet …"
                    } else if (state.segments.isEmpty()) {
                        "Es wurde kein Text erkannt."
                    } else if (automaticAiMissingModel) {
                        "Transkription fertig. KI-Nachbearbeitung übersprungen: Modell fehlt."
                    } else {
                        "Fertig: ${state.segments.size} Textabschnitte erkannt."
                    },
                    statusKind = StatusMessageKind.COMPLETION,
                    statusEventId = uiState.statusEventId + 1L,
                    cannaBotMode = if (canRunAutomaticAi) CannaBotMode.REVIEW else CannaBotMode.IDLE
                )
                persistCurrentTranscript(timelineSegments)
                if (canRunAutomaticAi) {
                    uiState = uiState.copy(
                        isAiPostProcessing = true,
                        activityDetail = "Whisper wurde entladen. Das KI-Modell wird vorbereitet.",
                        diagnostics = (uiState.diagnostics +
                            "Whisper-Speicher freigegeben; automatische KI-Nachbearbeitung gestartet.")
                            .takeLast(12)
                    )
                    AiPostProcessingService.startAutomatic(
                        context = application,
                        model = uiState.selectedAiModel,
                        fileName = uiState.selectedFileName ?: "Transkript",
                        segments = timelineSegments
                    )
                } else {
                    if (automaticAiMissingModel) {
                        uiState = uiState.copy(
                            diagnostics = (uiState.diagnostics +
                                "Automatische KI-Nachbearbeitung übersprungen: ausgewähltes Modell ist nicht installiert.")
                                .takeLast(12)
                        )
                    }
                    cue(CannaBotCue.SUCCESS)
                }
                TranscriptionCoordinator.acknowledgeTerminal(application)
            }
            is TranscriptionState.Cancelled -> {
                stopTranscriptionElapsedTimer()
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    rawWhisperSegments = emptyList(),
                    segments = emptyList(),
                    isEditingTranscript = false,
                    editingTranscriptGroupStartMs = null,
                    draftSegments = emptyList(),
                    detectedLanguage = null,
                    completedModel = null,
                    transcriptionDurationSeconds = null,
                    vadProcessingSummary = null,
                    error = null,
                    status = "Transkription angehalten · Der Zwischenstand bleibt erhalten.",
                    statusKind = StatusMessageKind.COMPLETION,
                    statusEventId = uiState.statusEventId + 1L,
                    cannaBotMode = CannaBotMode.IDLE
                )
                TranscriptionCoordinator.acknowledgeTerminal(application)
            }
            is TranscriptionState.Failed -> {
                stopTranscriptionElapsedTimer()
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    rawWhisperSegments = emptyList(),
                    segments = state.committedSegments,
                    isEditingTranscript = false,
                    editingTranscriptGroupStartMs = null,
                    draftSegments = emptyList(),
                    error = state.message,
                    status = if (state.canResume) {
                        "Transkription unterbrochen · Beim nächsten Start wird sie fortgesetzt."
                    } else {
                        "Transkription fehlgeschlagen."
                    },
                    statusKind = StatusMessageKind.ERROR,
                    statusEventId = uiState.statusEventId + 1L,
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
                TranscriptionCoordinator.acknowledgeTerminal(application)
            }
        }
    }

    private fun modelFile(model: WhisperModel): File = File(modelsDirectory, model.fileName)

    private fun restoreStoredTranscript() {
        val stored = transcriptResultStore.read() ?: return
        val pendingTerminal = TranscriptionCoordinator.state.value
        if (pendingTerminal is TranscriptionState.Completed &&
            pendingTerminal.fileName == stored.fileName &&
            pendingTerminal.model.id == stored.modelId
        ) {
            // This result was already persisted before the process restart. Consuming the
            // terminal envelope prevents automatic post-processing from running a second time.
            TranscriptionCoordinator.acknowledgeTerminal(application)
        }
        val sourceUri = stored.sourceUri.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (sourceUri != null) {
            restoreAudioInternal(
                uri = sourceUri,
                fileName = stored.fileName,
                status = "Gespeichertes Transkript wiederhergestellt: " +
                    "${stored.displayedSegments.count { it.text.isNotBlank() }} Textabschnitte.",
                restoredTranscript = stored
            )
            return
        }
        uiState = uiState.copy(
            selectedAudio = sourceUri,
            selectedFileName = stored.fileName,
            rawWhisperSegments = stored.rawWhisperSegments,
            segments = stored.displayedSegments,
            detectedLanguage = stored.detectedLanguage,
            completedModel = WhisperModel.fromId(stored.modelId),
            transcriptionDurationSeconds = stored.transcriptionDurationSeconds,
            vadProcessingSummary = stored.vadSummary,
            status = "Gespeichertes Transkript wiederhergestellt: " +
                "${stored.displayedSegments.count { it.text.isNotBlank() }} Textabschnitte.",
            cannaBotMode = CannaBotMode.IDLE
        )
    }

    private fun persistCurrentTranscript(displayedSegments: List<WhisperSegment>) {
        if (displayedSegments.isEmpty()) return
        val model = uiState.completedModel ?: return
        transcriptResultPersistence.save(
            StoredTranscriptResult(
                sourceUri = uiState.selectedAudio?.toString().orEmpty(),
                fileName = uiState.selectedFileName ?: "Transkript",
                modelId = model.id,
                detectedLanguage = uiState.detectedLanguage.orEmpty(),
                transcriptionDurationSeconds = uiState.transcriptionDurationSeconds ?: 0L,
                savedAtEpochMs = System.currentTimeMillis(),
                rawWhisperSegments = uiState.rawWhisperSegments,
                displayedSegments = displayedSegments,
                vadSummary = uiState.vadProcessingSummary
            )
        )
    }

    private fun partialModelFile(model: WhisperModel): File =
        File(modelsDirectory, "${model.fileName}.part")

    private fun vadModelFile(): File = File(vadModelsDirectory, SileroVadModel.fileName)
    private fun partialVadModelFile(): File = File(vadModelsDirectory, "${SileroVadModel.fileName}.part")

    private fun refreshVadModelInstallation() {
        val file = vadModelFile()
        uiState = uiState.copy(vadModelInstallation = VadModelInstallation(
            isInstalled = file.isFile && file.length() == SileroVadModel.expectedBytes,
            installedBytes = file.takeIf(File::isFile)?.length() ?: 0L,
            partialBytes = partialVadModelFile().takeIf(File::isFile)?.length() ?: 0L
        ))
        refreshDeviceStorage()
    }

    private fun aiModelFile(model: AiModel): File = File(aiModelsDirectory, model.fileName)

    private fun partialAiModelFile(model: AiModel): File =
        File(aiModelsDirectory, "${model.fileName}.part")

    fun refreshDeviceStorage() {
        val snapshot = runCatching {
            val stats = StatFs(application.filesDir.absolutePath)
            normalizedStorageSnapshot(
                totalBytes = stats.blockCountLong * stats.blockSizeLong,
                freeBytes = stats.availableBlocksLong * stats.blockSizeLong
            )
        }.getOrDefault(DeviceStorageSnapshot())
        uiState = uiState.copy(deviceStorage = snapshot)
    }

    private fun refreshAiModelInstallations(selectedModel: AiModel) {
        val installations = AiModel.entries.map { model ->
            val file = aiModelFile(model)
            AiModelInstallation(
                model = model,
                isInstalled = file.isFile && file.length() >= model.minimumBytes,
                installedBytes = file.takeIf(File::isFile)?.length() ?: 0L,
                partialBytes = partialAiModelFile(model).takeIf(File::isFile)?.length() ?: 0L
            )
        }
        uiState = uiState.copy(
            selectedAiModel = selectedModel,
            aiModelInstallations = installations
        )
        refreshDeviceStorage()
    }

    private fun refreshModelInstallations(selectedModel: WhisperModel) {
        val installations = WhisperModel.entries.map { model ->
            val file = modelFile(model)
            ModelInstallation(
                model = model,
                isInstalled = file.isFile && file.length() >= model.minimumBytes,
                installedBytes = file.takeIf { it.isFile }?.length() ?: 0L,
                partialBytes = partialModelFile(model).takeIf { it.isFile }?.length() ?: 0L
            )
        }
        val selectedInstallation = installations.first { it.model == selectedModel }
        uiState = uiState.copy(
            selectedModel = selectedModel,
            modelInstallations = installations,
            modelReady = selectedInstallation.isInstalled,
            status = if (selectedInstallation.isInstalled) {
                "${selectedModel.modelLabel} bereit."
            } else {
                "Bitte ${selectedModel.modelLabel} herunterladen."
            },
            cannaBotMode = if (
                selectedInstallation.isInstalled &&
                uiState.mediaReadyStatus != null &&
                uiState.segments.isEmpty() &&
                uiState.error == null
            ) {
                CannaBotMode.REVIEW
            } else {
                CannaBotMode.IDLE
            }
        ).withRecalculatedTranscriptionEstimate()
        refreshDeviceStorage()
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Mediendatei"
    }

    private fun contentLength(uri: Uri): Long {
        if (uri.scheme == "file") {
            return uri.path?.let(::File)?.takeIf(File::isFile)?.length() ?: -1L
        }
        return runCatching {
            application.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else {
                    -1L
                }
            } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun fail(throwable: Throwable) {
        stopTranscriptionElapsedTimer()
        uiState = uiState.copy(
            isBusy = false,
            isTranscribing = false,
            isCancellationRequested = false,
            progress = null,
            downloadingModel = null,
            downloadingAiModel = null,
            isAiPostProcessing = false,
            isAiSelfTest = false,
            isAiBenchmarkRunning = false,
            activityDetail = null,
            error = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            status = "Vorgang fehlgeschlagen.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.FAILED)
    }

    override fun onCleared() {
        waveformJob?.cancel()
        aiBenchmarkJob?.cancel()
        AiEngineSessionManager.release()
        stopPlaybackTimer()
        stopTranscriptionElapsedTimer()
        audioPlayer.release()
        transcriptResultPersistence.close()
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

internal fun formatClock(totalSeconds: Long): String {
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

internal fun elapsedSecondsSince(startedAtEpochMs: Long, nowEpochMs: Long): Long =
    ((nowEpochMs - startedAtEpochMs).coerceAtLeast(0L) / 1_000L)
