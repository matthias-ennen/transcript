package de.matthiasennen.transcript.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.TranscriptExportMetadata
import de.matthiasennen.transcript.export.exportTranscript
import de.matthiasennen.transcript.export.formatTimestamp
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val languages = listOf(
    "auto" to "Automatisch – empfohlen",
    "en" to "Englisch",
    "de" to "Deutsch"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    val state = viewModel.uiState
    val context = LocalContext.current
    var page by remember { mutableStateOf(AppPage.MAIN) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::selectAudio)
    }
    var pendingModelDownload by remember { mutableStateOf<WhisperModel?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingModelDownload?.let(viewModel::downloadModel)
        pendingModelDownload = null
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording() else viewModel.reportMicrophonePermissionDenied()
    }
    val textExporter = rememberExporter(context, state, ExportFormat.TEXT)
    val srtExporter = rememberExporter(context, state, ExportFormat.SUBRIP)
    val jsonExporter = rememberExporter(context, state, ExportFormat.JSON)

    Scaffold(
        topBar = {
            TranscriptTopBar(
                state = state,
                page = page,
                onNavigate = { page = it }
            )
        }
    ) { innerPadding ->
        when (page) {
            AppPage.SETTINGS -> SettingsScreen(
                state = state,
                onDeleteModel = viewModel::deleteModel,
                onDeleteAllModels = viewModel::deleteAllModels,
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.ABOUT -> AboutScreen(
                modifier = Modifier.padding(innerPadding)
            )
            AppPage.MAIN -> MainContent(
                viewModel = viewModel,
                state = state,
                innerPadding = innerPadding,
                audioPicker = { audioPicker.launch(arrayOf("audio/*", "video/*")) },
                requestModelDownload = {
                    val model = state.selectedModel
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingModelDownload = model
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.downloadModel(model)
                    }
                },
                requestRecording = {
                    if (state.isRecording) {
                        viewModel.stopRecording()
                    } else if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.startRecording()
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                textExporter = { textExporter.launch(defaultName(state, "txt")) },
                srtExporter = { srtExporter.launch(defaultName(state, "srt")) },
                jsonExporter = { jsonExporter.launch(defaultName(state, "json")) }
            )
        }
    }
}

private enum class AppPage { MAIN, SETTINGS, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptTopBar(
    state: TranscriptUiState,
    page: AppPage,
    onNavigate: (AppPage) -> Unit
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (page == AppPage.MAIN) "Transcript" else page.title)
                if (page == AppPage.MAIN) {
                    Spacer(Modifier.width(8.dp))
                    CannaBotTitleAnimation(state)
                }
            }
        },
        navigationIcon = {
            if (page != AppPage.MAIN) {
                IconButton(onClick = { onNavigate(AppPage.MAIN) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                }
            }
        },
        actions = {
            if (page == AppPage.MAIN) {
                IconButton(onClick = { onNavigate(AppPage.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                }
                IconButton(onClick = { onNavigate(AppPage.ABOUT) }) {
                    Icon(Icons.Default.Info, contentDescription = "Über die App")
                }
            }
        }
    )
}

private val AppPage.title: String
    get() = when (this) {
        AppPage.MAIN -> "Transcript"
        AppPage.SETTINGS -> "Einstellungen"
        AppPage.ABOUT -> "Über die App"
    }

@Composable
private fun MainContent(
    viewModel: MainScreenViewModel,
    state: TranscriptUiState,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    audioPicker: () -> Unit,
    requestModelDownload: () -> Unit,
    requestRecording: () -> Unit,
    textExporter: () -> Unit,
    srtExporter: () -> Unit,
    jsonExporter: () -> Unit
) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Audio- und Videodateien vollständig offline mit Whisper transkribieren.",
                style = MaterialTheme.typography.bodyMedium
            )

            ModelSelector(
                selected = state.selectedModel,
                installations = state.modelInstallations,
                enabled = state.isModelSelectionEnabled,
                onSelected = viewModel::selectModel
            )

            ModelManagerCard(
                state = state,
                onDownload = requestModelDownload
            )

            OutlinedButton(
                onClick = audioPicker,
                enabled = !state.isBusy && !state.isRecording,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(state.selectedFileName ?: "Audio- oder Videodatei auswählen")
            }

            AudioControls(
                state = state,
                onRecordClick = requestRecording,
                onPlayPauseClick = viewModel::togglePlayback,
                onSeek = viewModel::seekPlayback
            )

            LanguageSelector(
                selected = state.language,
                enabled = !state.isBusy && !state.isRecording,
                onSelected = viewModel::setLanguage
            )

            Button(
                onClick = {
                    if (state.isTranscribing) viewModel.cancelTranscription()
                    else viewModel.transcribe()
                },
                enabled = if (state.isTranscribing) {
                    !state.isCancellationRequested
                } else {
                    state.modelReady && state.selectedAudio != null &&
                        !state.isBusy && !state.isRecording
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        state.isCancellationRequested -> "Wird abgebrochen …"
                        state.isTranscribing -> "Abbrechen"
                        else -> "Transkribieren"
                    }
                )
            }

            if (state.isBusy && state.downloadingModel == null) {
                state.progress?.let {
                    LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            if (state.isBusy && state.downloadingModel == null) {
                Text(
                    "Laufzeit: ${formatClock(state.elapsedSeconds)}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            state.activityDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (state.segments.isNotEmpty()) {
                TranscriptResultSummary(state)
            }

            if (state.diagnostics.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Diagnose", style = MaterialTheme.typography.titleSmall)
                        state.diagnostics.forEach { entry ->
                            Text(entry, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (state.segments.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = textExporter,
                        modifier = Modifier.weight(1f)
                    ) { Text("TXT") }
                    OutlinedButton(
                        onClick = srtExporter,
                        modifier = Modifier.weight(1f)
                    ) { Text("SRT") }
                    OutlinedButton(
                        onClick = jsonExporter,
                        modifier = Modifier.weight(1f)
                    ) { Text("JSON") }
                }
            }

            TranscriptList(state.segments)
        }
    }
@Composable
internal fun ModelSelector(
    selected: WhisperModel,
    installations: List<ModelInstallation>,
    enabled: Boolean,
    onSelected: (WhisperModel) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val selectorWidth = maxWidth
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text("Qualitätsstufe", style = MaterialTheme.typography.labelSmall)
                Text(selected.qualityLabel, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = if (expanded) "Modellliste schließen" else "Modellliste öffnen"
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(selectorWidth)
        ) {
            installations.forEach { installation ->
                val model = installation.model
                DropdownMenuItem(
                    text = { Text(model.qualityLabel) },
                    onClick = {
                        onSelected(model)
                        expanded = false
                    }
                )
            }
        }
    }
}

internal val TranscriptUiState.isModelSelectionEnabled: Boolean
    get() = !isBusy && !isRecording

@Composable
private fun ModelManagerCard(
    state: TranscriptUiState,
    onDownload: () -> Unit
) {
    val model = state.selectedModel
    val installation = state.modelInstallations.firstOrNull { it.model == model }
    val installed = installation?.isInstalled == true
    val isDownloading = state.downloadingModel == model

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(model.qualityLabel, style = MaterialTheme.typography.titleMedium)
            Text(model.modelLabel, style = MaterialTheme.typography.bodyMedium)
            Text(model.description, style = MaterialTheme.typography.bodySmall)

            when {
                isDownloading -> {
                    val downloaded = formatDownloadSize(state.downloadedBytes)
                    val total = state.downloadTotalBytes.takeIf { it > 0L }
                        ?.let(::formatDownloadSize)
                        ?: model.downloadSizeLabel
                    Text("Download: $downloaded von $total")
                    state.progress?.let {
                        LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                installed -> {
                    Text(
                        "Aktiv · Installiert (${formatDownloadSize(installation?.installedBytes ?: 0L)})",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Text("Noch nicht installiert · Download ${model.downloadSizeLabel}")
                    Button(
                        onClick = onDownload,
                        enabled = !state.isBusy && !state.isRecording,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Modell herunterladen")
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptResultSummary(state: TranscriptUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Ergebnis", style = MaterialTheme.typography.titleSmall)
            state.completedModel?.let { Text("Modell: ${it.modelLabel}") }
            state.detectedLanguage?.let { Text("Erkannte Sprache: ${languageDisplayName(it)}") }
            state.transcriptionDurationSeconds?.let { Text("Transkriptionszeit: ${formatClock(it)}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = languages.firstOrNull { it.first == selected }?.second ?: selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Sprache") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun TranscriptList(segments: List<WhisperSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) {
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { segment ->
            Card(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "${formatTimestamp(segment.startMs)} – ${formatTimestamp(segment.endMs)}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(segment.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberExporter(
    context: Context,
    state: TranscriptUiState,
    format: ExportFormat
) = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri: Uri? ->
    uri?.let {
        context.contentResolver.openOutputStream(it)?.bufferedWriter()?.use { writer ->
            writer.write(
                exportTranscript(
                    segments = state.segments,
                    format = format,
                    metadata = TranscriptExportMetadata(
                        whisperModel = state.completedModel?.modelLabel
                            ?: state.selectedModel.modelLabel,
                        detectedLanguage = state.detectedLanguage ?: "unknown",
                        transcriptionDurationSeconds = state.transcriptionDurationSeconds ?: 0L,
                        createdAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    )
                )
            )
        }
    }
}

private fun defaultName(state: TranscriptUiState, extension: String): String {
    val base = state.selectedFileName
        ?.substringBeforeLast('.')
        ?.ifBlank { "Transcript" }
        ?: "Transcript"
    return "$base Transcript.$extension"
}

private fun languageDisplayName(code: String): String = when (code) {
    "de" -> "Deutsch"
    "en" -> "Englisch"
    else -> code.ifBlank { "Unbekannt" }
}

internal fun formatDownloadSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}
