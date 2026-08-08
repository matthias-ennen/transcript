package de.matthiasennen.transcript.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.download.ModelDownloadCoordinator
import de.matthiasennen.transcript.download.ModelDownloadService
import de.matthiasennen.transcript.download.ModelDownloadState
import de.matthiasennen.transcript.media.AudioPlayerController
import de.matthiasennen.transcript.media.AudioRecorder
import de.matthiasennen.transcript.media.CachedWaveform
import de.matthiasennen.transcript.media.WaveformCache
import de.matthiasennen.transcript.media.generateWaveform
import de.matthiasennen.transcript.media.inspectAudioTrack
import de.matthiasennen.transcript.transcription.TranscriptionCoordinator
import de.matthiasennen.transcript.transcription.TranscriptionService
import de.matthiasennen.transcript.transcription.TranscriptionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val PREFERENCES_NAME = "transcript_preferences"
private const val SELECTED_MODEL_KEY = "selected_model"

enum class CannaBotMode { IDLE, WAITING, REVIEW, RUNNING }

enum class CannaBotCue { NONE, RUNNING_RIGHT, RUNNING_LEFT, JUMPING, WAVING, SUCCESS, FAILED }

data class TranscriptUiState(
    val selectedAudio: Uri? = null,
    val selectedFileName: String? = null,
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val mediaReadyStatus: String? = null,
    val waveform: List<Float> = emptyList(),
    val liveWaveform: List<Float> = emptyList(),
    val isWaveformLoading: Boolean = false,
    val waveformProgress: Float? = null,
    val language: String = "auto",
    val selectedModel: WhisperModel = WhisperModel.BASE,
    val modelInstallations: List<ModelInstallation> = emptyList(),
    val modelReady: Boolean = false,
    val downloadingModel: WhisperModel? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val isBusy: Boolean = false,
    val isTranscribing: Boolean = false,
    val isCancellationRequested: Boolean = false,
    val progress: Float? = null,
    val status: String = "Bitte zuerst das Whisper-Modell herunterladen.",
    val runtimeEstimateAnnouncementId: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val activityDetail: String? = null,
    val diagnostics: List<String> = emptyList(),
    val segments: List<WhisperSegment> = emptyList(),
    val isEditingTranscript: Boolean = false,
    val editingTranscriptGroupStartMs: Long? = null,
    val draftSegments: List<WhisperSegment> = emptyList(),
    val detectedLanguage: String? = null,
    val completedModel: WhisperModel? = null,
    val transcriptionDurationSeconds: Long? = null,
    val error: String? = null,
    val cannaBotMode: CannaBotMode = CannaBotMode.IDLE,
    val cannaBotCue: CannaBotCue = CannaBotCue.NONE,
    val cannaBotCueId: Long = 0L
)

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var uiState by mutableStateOf(TranscriptUiState())
        private set

    private val modelsDirectory = File(application.filesDir, "models")
    private val waveformCache = WaveformCache(File(application.filesDir, "waveforms"))
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, 0)
    private val audioRecorder = AudioRecorder(application)
    private val audioPlayer = AudioPlayerController(
        context = application,
        onPrepared = { durationMs ->
            uiState = uiState.copy(audioDurationMs = durationMs)
        },
        onCompletion = {
            playbackTimer?.cancel()
            playbackTimer = null
            uiState = uiState.copy(
                isPlaying = false,
                playbackPositionMs = 0L,
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
    private var recordingMeter: Job? = null
    private var playbackTimer: Job? = null
    private var waveformJob: Job? = null
    private var transcriptionElapsedTimer: Job? = null
    private var transcriptionStartedAtEpochMs = 0L
    private var lastDownloadAnimationBucket = -1
    private var lastTranscriptionAnimationSection = -1

    private fun cue(cue: CannaBotCue) {
        uiState = uiState.copy(cannaBotCue = cue, cannaBotCueId = uiState.cannaBotCueId + 1L)
    }

    init {
        modelsDirectory.mkdirs()
        val selectedModel = WhisperModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null))
        refreshModelInstallations(selectedModel)
        viewModelScope.launch {
            ModelDownloadCoordinator.state.collect(::handleModelDownloadState)
        }
        viewModelScope.launch {
            TranscriptionCoordinator.state.collect(::handleTranscriptionState)
        }
    }

    fun selectAudio(uri: Uri) {
        runCatching {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        selectAudioInternal(
            uri = uri,
            fileName = displayName(uri),
            status = "Audio- oder Videodatei ausgewählt."
        )
    }

    fun startRecording() {
        if (uiState.isBusy || uiState.isRecording) return
        stopPlayback(release = true)
        runCatching { audioRecorder.start() }
            .onSuccess {
                uiState = uiState.copy(
                    isRecording = true,
                    liveWaveform = emptyList(),
                    segments = emptyList(),
                    isEditingTranscript = false,
                    editingTranscriptGroupStartMs = null,
                    draftSegments = emptyList(),
                    error = null,
                    status = "Aufnahme läuft … Zum Beenden erneut tippen.",
                    cannaBotMode = CannaBotMode.RUNNING
                )
                recordingMeter?.cancel()
                recordingMeter = viewModelScope.launch {
                    while (uiState.isRecording) {
                        delay(100L)
                        val amplitude = audioRecorder.currentAmplitude()
                        uiState = uiState.copy(
                            liveWaveform = (uiState.liveWaveform + amplitude).takeLast(72)
                        )
                    }
                }
            }
            .onFailure { throwable ->
                uiState = uiState.copy(
                    error = throwable.localizedMessage ?: "Die Aufnahme konnte nicht gestartet werden.",
                    status = "Aufnahme fehlgeschlagen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
            }
    }

    fun reportMicrophonePermissionDenied() {
        uiState = uiState.copy(
            error = "Für eine Aufnahme benötigt Transcript die Mikrofonberechtigung.",
            status = "Mikrofonberechtigung fehlt.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.FAILED)
    }

    fun stopRecording() {
        if (!uiState.isRecording) return
        recordingMeter?.cancel()
        recordingMeter = null
        val file = audioRecorder.stop()
        uiState = uiState.copy(isRecording = false, liveWaveform = emptyList())
        if (file == null || !file.isFile || file.length() == 0L) {
            uiState = uiState.copy(
                error = "Die Aufnahme war zu kurz oder konnte nicht gespeichert werden.",
                status = "Aufnahme fehlgeschlagen.",
                cannaBotMode = CannaBotMode.IDLE
            )
            cue(CannaBotCue.FAILED)
            return
        }
        selectAudioInternal(
            uri = Uri.fromFile(file),
            fileName = file.name,
            status = "Aufnahme gespeichert und ausgewählt."
        )
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
        if (duration <= 0L) return
        val position = (duration * fraction.coerceIn(0f, 1f)).toLong()
        audioPlayer.seekTo(position)
        uiState = uiState.copy(playbackPositionMs = position)
    }

    private fun selectAudioInternal(uri: Uri, fileName: String, status: String) {
        stopPlayback(release = true)
        waveformJob?.cancel()
        uiState = uiState.copy(
            selectedAudio = uri,
            selectedFileName = fileName,
            isPlaying = false,
            playbackPositionMs = 0L,
            audioDurationMs = 0L,
            mediaReadyStatus = null,
            waveform = emptyList(),
            isWaveformLoading = true,
            waveformProgress = 0f,
            segments = emptyList(),
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            error = null,
            elapsedSeconds = 0L,
            diagnostics = emptyList(),
            detectedLanguage = null,
            completedModel = null,
            transcriptionDurationSeconds = null,
            status = "Wellenform wird erstellt …",
            runtimeEstimateAnnouncementId = 0L,
            activityDetail = "Wellenform wird erstellt · 0 %",
            cannaBotMode = CannaBotMode.WAITING
        )
        runCatching { audioPlayer.prepare(uri) }
            .onFailure { throwable ->
                uiState = uiState.copy(
                    error = throwable.localizedMessage ?: "Die Mediendatei konnte nicht geöffnet werden."
                )
            }
        waveformJob = viewModelScope.launch {
            try {
                val metadataDurationMs = withContext(Dispatchers.IO) {
                    inspectAudioTrack(application, uri).durationMs
                }
                if (uiState.selectedAudio == uri && uiState.audioDurationMs <= 0L) {
                    uiState = uiState.copy(audioDurationMs = metadataDurationMs)
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
        uiState = uiState.copy(
            waveform = waveform,
            audioDurationMs = uiState.audioDurationMs.takeIf { it > 0L } ?: durationMs,
            isWaveformLoading = false,
            waveformProgress = 1f,
            mediaReadyStatus = readyStatus,
            status = if (operationOwnsStatus) uiState.status else readyStatus,
            activityDetail = if (operationOwnsStatus) uiState.activityDetail else null,
            cannaBotMode = if (operationOwnsStatus) uiState.cannaBotMode else CannaBotMode.REVIEW
        )
    }

    private fun finishWaveformWithoutPreview(uri: Uri) {
        if (uiState.selectedAudio != uri) return
        val operationOwnsStatus = uiState.isBusy || uiState.isTranscribing || uiState.isRecording ||
            uiState.segments.isNotEmpty() || uiState.error != null
        val readyStatus = "Datei ist bereit · Wellenform konnte nicht erstellt werden."
        uiState = uiState.copy(
            waveform = emptyList(),
            isWaveformLoading = false,
            waveformProgress = null,
            mediaReadyStatus = readyStatus,
            status = if (operationOwnsStatus) uiState.status else readyStatus,
            activityDetail = if (operationOwnsStatus) uiState.activityDetail else null,
            cannaBotMode = if (operationOwnsStatus) uiState.cannaBotMode else CannaBotMode.REVIEW
        )
    }

    fun setLanguage(language: String) {
        uiState = uiState.copy(language = language)
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

    fun startTranscriptEditing(groupStartMs: Long) {
        if (uiState.isBusy || uiState.segments.isEmpty()) return
        if (uiState.segments.none { transcriptGroupStartMs(it.startMs) == groupStartMs }) return
        uiState = uiState.copy(
            isEditingTranscript = true,
            editingTranscriptGroupStartMs = groupStartMs,
            draftSegments = uiState.segments
        )
    }

    fun updateTranscriptText(index: Int, text: String) {
        if (!uiState.isEditingTranscript) return
        val groupStartMs = uiState.editingTranscriptGroupStartMs ?: return
        val segment = uiState.segments.getOrNull(index) ?: return
        if (transcriptGroupStartMs(segment.startMs) != groupStartMs) return
        uiState = uiState.copy(
            draftSegments = uiState.draftSegments.withUpdatedTranscriptText(index, text)
        )
    }

    fun cancelTranscriptEditing() {
        if (!uiState.isEditingTranscript) return
        uiState = uiState.copy(
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList()
        )
    }

    fun applyTranscriptEdits() {
        val groupStartMs = uiState.editingTranscriptGroupStartMs
        if (
            !uiState.isEditingTranscript ||
            groupStartMs == null ||
            uiState.draftSegments.size != uiState.segments.size
        ) {
            return
        }
        uiState = uiState.copy(
            segments = applyTranscriptGroupEdits(
                original = uiState.segments,
                draft = uiState.draftSegments,
                groupStartMs = groupStartMs
            ),
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            status = "Textkorrekturen übernommen. Die Exporte sind aktualisiert.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.WAVING)
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

    fun transcribe() {
        val uri = uiState.selectedAudio ?: return
        if (!uiState.modelReady || uiState.isBusy) return
        stopPlayback(release = false)
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
            language = uiState.language
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
                    activityDetail = state.activityDetail,
                    diagnostics = state.diagnostics,
                    segments = state.committedSegments,
                    detectedLanguage = state.detectedLanguage,
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
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    segments = state.segments,
                    isEditingTranscript = false,
                    editingTranscriptGroupStartMs = null,
                    draftSegments = emptyList(),
                    detectedLanguage = state.detectedLanguage,
                    completedModel = state.model,
                    transcriptionDurationSeconds = state.transcriptionDurationSeconds,
                    error = null,
                    status = if (state.segments.isEmpty()) {
                        "Es wurde kein Text erkannt."
                    } else {
                        "Fertig: ${state.segments.size} Textabschnitte erkannt."
                    },
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.SUCCESS)
            }
            is TranscriptionState.Cancelled -> {
                stopTranscriptionElapsedTimer()
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    segments = emptyList(),
                    isEditingTranscript = false,
                    editingTranscriptGroupStartMs = null,
                    draftSegments = emptyList(),
                    detectedLanguage = null,
                    completedModel = null,
                    transcriptionDurationSeconds = null,
                    error = null,
                    status = "Transkription abgebrochen.",
                    cannaBotMode = CannaBotMode.IDLE
                )
            }
            is TranscriptionState.Failed -> {
                stopTranscriptionElapsedTimer()
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
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
                    cannaBotMode = CannaBotMode.IDLE
                )
                cue(CannaBotCue.FAILED)
            }
        }
    }

    private fun modelFile(model: WhisperModel): File = File(modelsDirectory, model.fileName)

    private fun partialModelFile(model: WhisperModel): File =
        File(modelsDirectory, "${model.fileName}.part")

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
        )
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
            activityDetail = null,
            error = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            status = "Vorgang fehlgeschlagen.",
            cannaBotMode = CannaBotMode.IDLE
        )
        cue(CannaBotCue.FAILED)
    }

    override fun onCleared() {
        recordingMeter?.cancel()
        waveformJob?.cancel()
        stopPlaybackTimer()
        stopTranscriptionElapsedTimer()
        audioRecorder.release()
        audioPlayer.release()
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
