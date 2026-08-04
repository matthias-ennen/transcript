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
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.media.decodeAudio
import kotlinx.coroutines.Dispatchers
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
    val segments: List<WhisperSegment> = emptyList(),
    val error: String? = null
)

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var uiState by mutableStateOf(TranscriptUiState())
        private set

    private val modelFile = File(application.filesDir, "models/ggml-base.bin")
    private var whisperContext: WhisperContext? = null

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
            uiState = uiState.copy(
                isBusy = true,
                progress = 0f,
                segments = emptyList(),
                error = null,
                status = "Audio wird dekodiert …"
            )

            runCatching {
                val audio = withContext(Dispatchers.IO) {
                    decodeAudio(application, uri) { progress ->
                        viewModelScope.launch {
                            uiState = uiState.copy(progress = progress)
                        }
                    }
                }
                uiState = uiState.copy(
                    progress = null,
                    status = "Gesang und Sprache werden erkannt …"
                )
                val context = withContext(Dispatchers.IO) {
                    whisperContext ?: WhisperContext.createContextFromFile(modelFile.absolutePath)
                        .also { whisperContext = it }
                }
                context.transcribeSegments(audio, uiState.language)
            }.onSuccess { segments ->
                uiState = uiState.copy(
                    isBusy = false,
                    progress = null,
                    segments = segments,
                    status = if (segments.isEmpty()) {
                        "Es wurde kein Text erkannt."
                    } else {
                        "Fertig: ${segments.size} Textabschnitte erkannt."
                    }
                )
            }.onFailure { throwable -> fail(throwable) }
        }
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
        uiState = uiState.copy(
            isBusy = false,
            progress = null,
            error = throwable.localizedMessage ?: throwable.javaClass.simpleName,
            status = "Vorgang fehlgeschlagen."
        )
    }

    override fun onCleared() {
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
