package de.matthiasennen.transcript.ui.main

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercpp.whisper.WhisperCpuConfig
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.download.ModelDownloadCoordinator
import de.matthiasennen.transcript.download.ModelDownloadService
import de.matthiasennen.transcript.download.ModelDownloadState
import de.matthiasennen.transcript.media.AudioPlayerController
import de.matthiasennen.transcript.media.AudioRecorder
import de.matthiasennen.transcript.media.decodeAudio
import de.matthiasennen.transcript.media.generateWaveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val PREFERENCES_NAME = "transcript_preferences"
private const val SELECTED_MODEL_KEY = "selected_model"

data class TranscriptUiState(
    val selectedAudio: Uri? = null,
    val selectedFileName: String? = null,
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L,
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
    val error: String? = null
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
            uiState = uiState.copy(isPlaying = false, playbackPositionMs = 0L)
        },
        onError = { message ->
            playbackTimer?.cancel()
            playbackTimer = null
            uiState = uiState.copy(isPlaying = false, error = message)
        }
    )
    private var whisperContext: WhisperContext? = null
    private var activeContextModel: WhisperModel? = null
    private var diagnosticTimer: Job? = null
    private var recordingMeter: Job? = null
    private var playbackTimer: Job? = null
    private var waveformJob: Job? = null
    private var transcriptionJob: Job? = null
    private val transcriptionCancellationRequested = AtomicBoolean(false)
    private var operationStartedAtMs = 0L
    private var lastNativeProgressAtMs = 0L
    private var lastProgressBucket = -1

    init {
        modelsDirectory.mkdirs()
        val selectedModel = WhisperModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null))
        refreshModelInstallations(selectedModel)
        viewModelScope.launch {
            ModelDownloadCoordinator.state.collect(::handleModelDownloadState)
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
                    status = "Aufnahme läuft … Zum Beenden erneut tippen."
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
                    status = "Aufnahme fehlgeschlagen."
                )
            }
    }

    fun reportMicrophonePermissionDenied() {
        uiState = uiState.copy(
            error = "Für eine Aufnahme benötigt Transcript die Mikrofonberechtigung.",
            status = "Mikrofonberechtigung fehlt."
        )
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
                status = "Aufnahme fehlgeschlagen."
            )
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
                uiState = uiState.copy(isPlaying = playing, error = null)
                if (playing) startPlaybackTimer() else stopPlaybackTimer()
            }
            .onFailure { throwable ->
                stopPlaybackTimer()
                uiState = uiState.copy(
                    isPlaying = false,
                    error = throwable.localizedMessage ?: "Die Mediendatei konnte nicht abgespielt werden."
                )
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
            status = status
        )
        runCatching { audioPlayer.prepare(uri) }
            .onFailure { throwable ->
                uiState = uiState.copy(
                    error = throwable.localizedMessage ?: "Die Mediendatei konnte nicht geöffnet werden."
                )
            }
        waveformJob = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { generateWaveform(application, uri) } }
                .onSuccess { (waveform, durationMs) ->
                    if (uiState.selectedAudio == uri) {
                        uiState = uiState.copy(
                            waveform = waveform,
                            audioDurationMs = if (uiState.audioDurationMs > 0L) {
                                uiState.audioDurationMs
                            } else {
                                durationMs
                            },
                            isWaveformLoading = false
                        )
                    }
                }
                .onFailure { throwable ->
                    if (uiState.selectedAudio == uri) {
                        uiState = uiState.copy(
                            isWaveformLoading = false,
                            error = "Wellenform konnte nicht erstellt werden: " +
                                (throwable.localizedMessage ?: throwable.javaClass.simpleName)
                        )
                    }
                }
        }
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
            status = "Textkorrekturen übernommen. Die Exporte sind aktualisiert."
        )
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
            status = "${model.modelLabel} wird im Hintergrund heruntergeladen …"
        )
        ModelDownloadService.start(application, model)
    }

    fun deleteModel(model: WhisperModel = uiState.selectedModel) {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                progress = null,
                error = null,
                status = "${model.modelLabel} wird gelöscht …"
            )
            runCatching {
                if (activeContextModel == model) releaseWhisperContext()
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
                    status = "${model.modelLabel} wurde gelöscht."
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
                status = "Alle Whisper-Modelle werden gelöscht …"
            )
            runCatching {
                releaseWhisperContext()
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
                    status = "Alle Whisper-Modelle wurden gelöscht."
                )
            }.onFailure { throwable -> fail(throwable) }
        }
    }

    fun transcribe() {
        val uri = uiState.selectedAudio ?: return
        if (!uiState.modelReady || uiState.isBusy) return
        val selectedModel = uiState.selectedModel
        val selectedModelFile = modelFile(selectedModel)

        stopPlayback(release = false)
        transcriptionCancellationRequested.set(false)
        transcriptionJob = viewModelScope.launch {
            startDiagnostics()
            uiState = uiState.copy(
                isBusy = true,
                isTranscribing = true,
                isCancellationRequested = false,
                progress = 0f,
                segments = emptyList(),
                isEditingTranscript = false,
                draftSegments = emptyList(),
                error = null,
                status = "1/4 · Audiodatei wird dekodiert …"
            )
            addDiagnostic("1/4 · Audio-/Video-Dekodierung gestartet.")

            try {
                val decodedAudio = withContext(Dispatchers.IO) {
                    decodeAudio(
                        context = application,
                        uri = uri,
                        shouldCancel = transcriptionCancellationRequested::get
                    ) { progress ->
                        viewModelScope.launch {
                            if (uiState.isTranscribing && !uiState.isCancellationRequested) {
                                val percent = (progress * 100).toInt().coerceIn(0, 100)
                                uiState = uiState.copy(
                                    progress = progress,
                                    status = "1/4 · Audiodatei wird dekodiert: $percent %"
                                )
                            }
                        }
                    }
                }
                ensureTranscriptionContinues()
                addDiagnostic(
                    "1/4 · Audio dekodiert: ${formatClock(decodedAudio.durationMs / 1_000L)}, " +
                        "${decodedAudio.sourceSampleRate} Hz, " +
                        "${decodedAudio.sourceChannelCount} Kanal/Kanäle, ${decodedAudio.mimeType}."
                )
                uiState = uiState.copy(
                    progress = null,
                    status = "2/4 · Whisper-Modell wird in den Speicher geladen …"
                )
                val contextWasCached = whisperContext != null && activeContextModel == selectedModel
                val context = withContext(Dispatchers.IO) {
                    if (activeContextModel != selectedModel) releaseWhisperContext()
                    ensureTranscriptionContinues()
                    whisperContext ?: WhisperContext.createContextFromFile(selectedModelFile.absolutePath)
                        .also {
                            whisperContext = it
                            activeContextModel = selectedModel
                        }
                }
                ensureTranscriptionContinues()
                addDiagnostic(
                    "2/4 · ${selectedModel.modelLabel} " +
                        "${if (contextWasCached) "war bereits geladen" else "wurde geladen"} " +
                        "(${formatFileSize(selectedModelFile.length())})."
                )

                val selectedLanguage = uiState.language
                val threadCount = WhisperCpuConfig.preferredThreadCount
                lastNativeProgressAtMs = SystemClock.elapsedRealtime()
                lastProgressBucket = -1
                uiState = uiState.copy(
                    progress = null,
                    status = "3/4 · Native Whisper-Engine wird gestartet …",
                    activityDetail = "Warte auf die erste Rückmeldung aus whisper.cpp."
                )
                addDiagnostic(
                    "3/4 · Start mit $threadCount CPU-Threads, Sprache ${languageLabel(selectedLanguage)}, " +
                        "${decodedAudio.samples.size} PCM-Samples."
                )

                val result = context.transcribeSegments(
                    data = decodedAudio.samples,
                    language = selectedLanguage,
                    shouldCancel = transcriptionCancellationRequested::get
                ) { percent ->
                    viewModelScope.launch {
                        if (!uiState.isTranscribing || uiState.isCancellationRequested) return@launch
                        val now = SystemClock.elapsedRealtime()
                        lastNativeProgressAtMs = now
                        val bucket = percent / 10
                        if (percent == 0 || percent == 100 || bucket > lastProgressBucket) {
                            lastProgressBucket = bucket
                            addDiagnostic("4/4 · whisper.cpp meldet $percent % Fortschritt.")
                        }
                        uiState = uiState.copy(
                            progress = percent / 100f,
                            status = "4/4 · Whisper verarbeitet das Audio: $percent %",
                            activityDetail = "Native Engine aktiv; letzte Rückmeldung gerade eben."
                        )
                    }
                }
                ensureTranscriptionContinues()
                stopDiagnosticTimer()
                val elapsed = (SystemClock.elapsedRealtime() - operationStartedAtMs) / 1_000L
                val segments = result.segments
                addDiagnostic("Abgeschlossen: ${segments.size} Textabschnitte empfangen.")
                uiState = uiState.copy(
                    isBusy = false,
                    isTranscribing = false,
                    isCancellationRequested = false,
                    progress = null,
                    activityDetail = null,
                    segments = segments,
                    isEditingTranscript = false,
                    draftSegments = emptyList(),
                    detectedLanguage = result.detectedLanguage,
                    completedModel = selectedModel,
                    transcriptionDurationSeconds = elapsed,
                    status = if (segments.isEmpty()) {
                        "Es wurde kein Text erkannt."
                    } else {
                        "Fertig: ${segments.size} Textabschnitte erkannt."
                    }
                )
            } catch (throwable: Throwable) {
                if (transcriptionCancellationRequested.get()) {
                    finishCancelledTranscription()
                } else {
                    addDiagnostic(
                        "Fehler: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}."
                    )
                    fail(throwable)
                }
            } finally {
                transcriptionJob = null
            }
        }
    }

    fun cancelTranscription() {
        if (!uiState.isTranscribing || !transcriptionCancellationRequested.compareAndSet(false, true)) {
            return
        }
        uiState = uiState.copy(
            isCancellationRequested = true,
            progress = null,
            status = "Transkription wird abgebrochen …",
            activityDetail = "Das Abbruchsignal wurde an die laufende Verarbeitung gesendet."
        )
        addDiagnostic("Abbruch durch den Benutzer angefordert.")
        whisperContext?.requestAbort()
    }

    private fun ensureTranscriptionContinues() {
        check(!transcriptionCancellationRequested.get()) { "Transkription abgebrochen." }
    }

    private suspend fun finishCancelledTranscription() {
        releaseWhisperContext()
        stopDiagnosticTimer()
        addDiagnostic("Transkription vollständig abgebrochen; Whisper-Kontext freigegeben.")
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
            status = "Transkription abgebrochen. Du kannst ein anderes Modell wählen."
        )
        transcriptionCancellationRequested.set(false)
    }

    private fun startDiagnostics() {
        diagnosticTimer?.cancel()
        operationStartedAtMs = SystemClock.elapsedRealtime()
        lastNativeProgressAtMs = 0L
        lastProgressBucket = -1
        uiState = uiState.copy(
            elapsedSeconds = 0L,
            activityDetail = null,
            diagnostics = emptyList()
        )
        diagnosticTimer = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                if (!uiState.isBusy) continue
                val now = SystemClock.elapsedRealtime()
                val elapsedSeconds = (now - operationStartedAtMs) / 1_000L
                val detail = if (lastNativeProgressAtMs > 0L) {
                    val silentSeconds = (now - lastNativeProgressAtMs) / 1_000L
                    when {
                        silentSeconds >= 120L ->
                            "Seit ${formatClock(silentSeconds)} keine neue Whisper-Meldung. " +
                                "Die Engine kann rechnen oder festhängen."
                        else ->
                            "Letzte Rückmeldung aus whisper.cpp vor ${formatClock(silentSeconds)}."
                    }
                } else {
                    uiState.activityDetail
                }
                uiState = uiState.copy(
                    elapsedSeconds = elapsedSeconds,
                    activityDetail = detail
                )
            }
        }
    }

    private fun stopDiagnosticTimer() {
        diagnosticTimer?.cancel()
        diagnosticTimer = null
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

    private fun addDiagnostic(message: String) {
        val elapsed = if (operationStartedAtMs == 0L) 0L else {
            (SystemClock.elapsedRealtime() - operationStartedAtMs) / 1_000L
        }
        uiState = uiState.copy(
            diagnostics = (uiState.diagnostics + "${formatClock(elapsed)} · $message").takeLast(12)
        )
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
                    }
                )
            }
            is ModelDownloadState.Verifying -> {
                uiState = uiState.copy(
                    isBusy = true,
                    downloadingModel = downloadState.model,
                    downloadedBytes = downloadState.downloadedBytes,
                    downloadTotalBytes = downloadState.downloadedBytes,
                    progress = null,
                    status = "Download vollständig · Prüfsumme wird kontrolliert …"
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
                    status = "${downloadState.model.modelLabel} ist installiert und aktiv."
                )
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
                    }
                )
                ModelDownloadCoordinator.reset()
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
                "${selectedModel.modelLabel} ist bereit."
            } else {
                "Bitte ${selectedModel.modelLabel} herunterladen."
            }
        )
    }

    private suspend fun releaseWhisperContext() {
        val context = whisperContext
        whisperContext = null
        activeContextModel = null
        context?.release()
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
        stopDiagnosticTimer()
        uiState = uiState.copy(
            isBusy = false,
            isTranscribing = false,
            isCancellationRequested = false,
            progress = null,
            downloadingModel = null,
            activityDetail = null,
            error = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            status = "Vorgang fehlgeschlagen."
        )
    }

    override fun onCleared() {
        transcriptionCancellationRequested.set(true)
        whisperContext?.requestAbort()
        transcriptionJob?.cancel()
        stopDiagnosticTimer()
        recordingMeter?.cancel()
        waveformJob?.cancel()
        stopPlaybackTimer()
        audioRecorder.release()
        audioPlayer.release()
        runBlocking { releaseWhisperContext() }
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

private fun languageLabel(code: String): String = when (code) {
    "de" -> "Deutsch"
    "en" -> "Englisch"
    else -> "automatisch"
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

private fun formatFileSize(bytes: Long): String =
    "%.1f MB".format(bytes / (1024.0 * 1024.0))
