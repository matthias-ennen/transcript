package de.matthiasennen.transcript.ui.main

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.exportTranscript
import de.matthiasennen.transcript.export.formatTimestamp

private val languages = listOf(
    "auto" to "Automatisch",
    "en" to "Englisch",
    "de" to "Deutsch"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainScreenViewModel) {
    val state = viewModel.uiState
    val context = LocalContext.current

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::selectAudio)
    }
    val textExporter = rememberExporter(context, state, ExportFormat.TEXT)
    val srtExporter = rememberExporter(context, state, ExportFormat.SUBRIP)
    val jsonExporter = rememberExporter(context, state, ExportFormat.JSON)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Transcript") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "MP3- und Audiodateien vollständig offline mit Whisper transkribieren.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (!state.modelReady) {
                Button(
                    onClick = viewModel::downloadModel,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Whisper Base herunterladen (ca. 142 MB)")
                }
            }

            OutlinedButton(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(state.selectedFileName ?: "MP3 oder Audiodatei auswählen")
            }

            LanguageSelector(
                selected = state.language,
                enabled = !state.isBusy,
                onSelected = viewModel::setLanguage
            )

            Button(
                onClick = viewModel::transcribe,
                enabled = state.modelReady && state.selectedAudio != null && !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Transkribieren")
            }

            if (state.isBusy) {
                state.progress?.let {
                    LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(state.status, style = MaterialTheme.typography.bodyMedium)
            if (state.isBusy) {
                Text(
                    "Laufzeit: ${formatClock(state.elapsedSeconds)}",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            state.activityDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

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
                        onClick = { textExporter.launch(defaultName(state, "txt")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("TXT") }
                    OutlinedButton(
                        onClick = { srtExporter.launch(defaultName(state, "srt")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("SRT") }
                    OutlinedButton(
                        onClick = { jsonExporter.launch(defaultName(state, "json")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("JSON") }
                }
            }

            TranscriptList(state.segments, Modifier.weight(1f))
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
        Spacer(modifier)
        return
    }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(segments) { segment ->
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
            writer.write(exportTranscript(state.segments, format))
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
