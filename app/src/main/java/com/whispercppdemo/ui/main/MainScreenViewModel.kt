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
import de.matthiasennen.transcript.media.WAVEFORM_GENERATION_TIMEOUT_MS
import de.matthiasennen.transcript.media.generateWaveform
import de.matthiasennen.transcript.media.inspectAudioTrack
import de.matthiasennen.transcript.transcription.TranscriptionCoordinator
import de.matthiasennen.transcript.transcription.TranscriptionService
import de.matthiasennen.transcript.transcription.TranscriptionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

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
    val elapsedSeconds: Long = 0L,
    val activityDetail: String? = null,
    val diagnostics: List<String> = emptyList(),
    val segments: List<WhisperSegment> = emptyList(),
    val isEditingTranscript: Boolean = false,
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
            segments = emptyList(),
            isEditingTranscript = false,
            draftSegments = emptyList(),
            error = null,
            elapsedSeconds = 0L,
            activityDetail = null,
            diagnostics = emptyList(),
            detectedLanguage = null,
            completedModel = null,
            transcriptionDurationSeconds = null,
            status = "Wellenform wird erstellt …",
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
                val (waveform, durationMs) = withTimeout(WAVEFORM_GENERATION_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val workerContext = currentCoroutineContext()
                        generateWaveform(
                            context = application,
                            uri = uri,
                            shouldCancel = { !workerContext.isActive }
                        )
                    }
                }
                if (uiState.selectedAudio == uri) {
                    uiState = uiState.copy(
                        waveform = waveform,
                        audioDurationMs = if (uiState.audioDurationMs > 0L) {
                            uiState.audioDurationMs
                        } else {
                            durationMs
                        },
                        isWaveformLoading = false,
                        mediaReadyStatus = status,
                        status = status,
                        cannaBotMode = CannaBotMode.REVIEW
                    )
                }
            } catch (_: TimeoutCancellationException) {
                finishWaveformWithoutPreview(uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                finishWaveformWithoutPreview(uri)
            }
        }
    }

    private fun finishWaveformWithoutPreview(uri: Uri) {
        if (uiState.selectedAudio != uri) return
        uiState = uiState.copy(
            waveform = emptyList(),
            isWaveformLoading = false,
            mediaReadyStatus = "Wellenform übersprungen · Datei ist bereit.",
            status = "Wellenform übersprungen · Datei ist bereit.",
            cannaBotMode = CannaBotMode.REVIEW
        )
    }

    fun setLanguage(language: String) {
        uiState = uiState.copy(language = language)
    }

    fun selectModel(model: WhisperModel) {
        if (uiState.isBusy) return
        preferences.edit().putString(SELECTED_MODEL_KEY, model.id).apply()
        refreshModelInstallations(model)
    }

    fun startTranscriptEditing() {
        if (uiState.isBusy || uiState.segments.isEmpty()) return
        uiState = uiState.copy(
            isEditingTranscript = true,
            draftSegments = uiState.segments
        )
    }

    fun updateTranscriptText(index: Int, text: String) {
        if (!uiState.isEditingTranscript) return
        uiState = uiState.copy(
            draftSegments = uiState.draftSegments.withUpdatedTranscriptText(index, text)
        )
    }

    fun cancelTranscriptEditing() {
        if (!uiState.isEditingTranscript) return
        uiState = uiState.copy(
            isEditingTranscript = false,
            draftSegments = emptyList()
        )
    }

    fun applyTranscriptEdits() {
        if (!uiState.isEditingTranscript || uiState.draftSegments.size != uiState.segments.size) {
            return
        }
        uiState = uiState.copy(
            segments = uiState.draftSegments,
            isEditingTranscript = false,
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
        waveformJob?.cancel()
        waveformJob = null
        uiState = uiState.copy(
            isBusy = true,
            isTranscribing = true,
            isWaveformLoading = false,
            isCancellationRequested = false,
            progress = 0f,
            isEditingTranscript = false,
            draftSegments = emptyList(),
            error = null,
            status = "Transkription wird im Hintergrund vorbereitet …",
            cannaBotMode = CannaBotMode.RUNNING
        )
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
                    cannaBotMode = CannaBotMode.IDLE
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
            }
            is TranscriptionState.Running -> {
                uiState = uiState.copy(
                    isBusy = true,
                    isTranscribing = true,
                    isCancellationRequested = false,
                    progress = state.progress,
                    elapsedSeconds = state.elapsedSeconds,
                    status = state.status,
                    activityDetail = state.activityDetail,
                    diagnostics = state.diagnostics,
                    segments = state.committedSegments,
                    detectedLanguage = state.detectedLanguage,
                    error = null,
                    cannaBotMode = CannaBotMode.RUNNING
                )
                if (state.sectionNumber != lastTranscriptionAnimationSection) {
                    lastTranscriptionAnimationSection = state.sectionNumber
                    cue(
                        if (state.sectionNumber % 2 == 0) CannaBotCue.RUNNING_LEFT
                        else CannaBotCue.RUNNING_RIGHT
                    )
                }
            }
            is TranscriptionState.Completed -> {
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    segments = state.segments,
                    isEditingTranscript = false,
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
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    segments = emptyList(),
                    isEditingTranscript = false,
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
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    segments = state.committedSegments,
                    isEditingTranscript = false,
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

    private fun fail(throwable: Throwable) {
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
