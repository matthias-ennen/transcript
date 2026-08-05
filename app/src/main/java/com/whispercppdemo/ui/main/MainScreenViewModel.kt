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
import de.matthiasennen.transcript.media.decodeAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val BASE_MODEL_URL =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
private const val MINIMUM_MODEL_BYTES = 100L * 1024L * 1024L

data class TranscriptUiState(
    val selectedAudio: Uri? = null,
    val selectedFileName: String? = null,
    val language: String = "auto",
    val modelReady: Boolean = false,
    val isBusy: Boolean = false,
    val progress: Float? = null,
    val status: String = "Bitte zuerst das Whisper-Modell herunterladen.",
    val elapsedSeconds: Long = 0L,
    val activityDetail: String? = null,
    val diagnostics: List<String> = emptyList(),
    val segments: List<WhisperSegment> = emptyList(),
    val error: String? = null
)

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var uiState by mutableStateOf(TranscriptUiState())
        private set

    private val modelFile = File(application.filesDir, "models/ggml-base.bin")
    private var whisperContext: WhisperContext? = null
    private var diagnosticTimer: Job? = null
    private var operationStartedAtMs = 0L
    private var lastNativeProgressAtMs = 0L
    private var lastProgressBucket = -1

    init {
        val ready = modelFile.isFile && modelFile.length() >= MINIMUM_MODEL_BYTES
        uiState = uiState.copy(
            modelReady = ready,
            status = if (ready) "Whisper Base ist bereit." else uiState.status
        )
    }

    fun selectAudio(uri: Uri) {
        runCatching {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        uiState = uiState.copy(
            selectedAudio = uri,
            selectedFileName = displayName(uri),
            segments = emptyList(),
            error = null,
            elapsedSeconds = 0L,
            activityDetail = null,
            diagnostics = emptyList(),
            status = "Audiodatei ausgewählt."
        )
    }

    fun setLanguage(language: String) {
        uiState = uiState.copy(language = language)
    }

    fun downloadModel() {
        if (uiState.isBusy) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isBusy = true,
                progress = 0f,
                error = null,
                status = "Whisper Base wird heruntergeladen …"
            )
            runCatching { downloadBaseModel() }
                .onSuccess {
                    uiState = uiState.copy(
                        modelReady = true,
                        isBusy = false,
                        progress = null,
                        status = "Whisper Base ist bereit."
                    )
                }
                .onFailure { throwable -> fail(throwable) }
        }
    }

    fun transcribe() {
        val uri = uiState.selectedAudio ?: return
        if (!uiState.modelReady || uiState.isBusy) return

        viewModelScope.launch {
            startDiagnostics()
            uiState = uiState.copy(
                isBusy = true,
                progress = 0f,
                segments = emptyList(),
                error = null,
                status = "1/4 · Audiodatei wird dekodiert …"
            )
            addDiagnostic("1/4 · MP3-Decodierung gestartet.")

            runCatching {
                val decodedAudio = withContext(Dispatchers.IO) {
                    decodeAudio(application, uri) { progress ->
                        viewModelScope.launch {
                            if (uiState.isBusy) {
                                val percent = (progress * 100).toInt().coerceIn(0, 100)
                                uiState = uiState.copy(
                                    progress = progress,
                                    status = "1/4 · Audiodatei wird dekodiert: $percent %"
                                )
                            }
                        }
                    }
                }
                addDiagnostic(
                    "1/4 · Audio dekodiert: ${formatClock(decodedAudio.durationMs / 1_000L)}, " +
                        "${decodedAudio.sourceSampleRate} Hz, " +
                        "${decodedAudio.sourceChannelCount} Kanal/Kanäle, ${decodedAudio.mimeType}."
                )
                uiState = uiState.copy(
                    progress = null,
                    status = "2/4 · Whisper-Modell wird in den Speicher geladen …"
                )
                val contextWasCached = whisperContext != null
                val context = withContext(Dispatchers.IO) {
                    whisperContext ?: WhisperContext.createContextFromFile(modelFile.absolutePath)
                        .also { whisperContext = it }
                }
                addDiagnostic(
                    "2/4 · Whisper Base ${if (contextWasCached) "war bereits geladen" else "wurde geladen"} " +
                        "(${formatFileSize(modelFile.length())})."
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

                context.transcribeSegments(decodedAudio.samples, selectedLanguage) { percent ->
                    viewModelScope.launch {
                        if (!uiState.isBusy) return@launch
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
            }.onSuccess { segments ->
                stopDiagnosticTimer()
                addDiagnostic("Abgeschlossen: ${segments.size} Textabschnitte empfangen.")
                uiState = uiState.copy(
                    isBusy = false,
                    progress = null,
                    activityDetail = null,
                    segments = segments,
                    status = if (segments.isEmpty()) {
                        "Es wurde kein Text erkannt."
                    } else {
                        "Fertig: ${segments.size} Textabschnitte erkannt."
                    }
                )
            }.onFailure { throwable ->
                addDiagnostic(
                    "Fehler: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}."
                )
                fail(throwable)
            }
        }
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

    private fun addDiagnostic(message: String) {
        val elapsed = if (operationStartedAtMs == 0L) 0L else {
            (SystemClock.elapsedRealtime() - operationStartedAtMs) / 1_000L
        }
        uiState = uiState.copy(
            diagnostics = (uiState.diagnostics + "${formatClock(elapsed)} · $message").takeLast(12)
        )
    }

    private suspend fun downloadBaseModel() = withContext(Dispatchers.IO) {
        modelFile.parentFile?.mkdirs()
        val partial = File(modelFile.parentFile, "${modelFile.name}.part")
        if (partial.exists()) partial.delete()

        val connection = URL(BASE_MODEL_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000

        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("Modelldownload fehlgeschlagen (HTTP ${connection.responseCode}).")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0L) {
                            uiState = uiState.copy(
                                progress = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
            }
            check(partial.length() >= MINIMUM_MODEL_BYTES) {
                "Das heruntergeladene Modell ist unvollständig."
            }
            if (modelFile.exists()) modelFile.delete()
            check(partial.renameTo(modelFile)) { "Das Modell konnte nicht gespeichert werden." }
        } finally {
            connection.disconnect()
            if (!modelFile.exists()) partial.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        application.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment ?: "Audiodatei"
    }

    private fun fail(throwable: Throwable) {
        stopDiagnosticTimer()
        uiState = uiState.copy(
            isBusy = false,
            progress = null,
            activityDetail = null,
            error = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            status = "Vorgang fehlgeschlagen."
        )
    }

    override fun onCleared() {
        stopDiagnosticTimer()
        runBlocking { whisperContext?.release() }
        whisperContext = null
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
