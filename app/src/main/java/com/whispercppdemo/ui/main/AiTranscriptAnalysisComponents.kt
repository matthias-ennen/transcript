package de.matthiasennen.transcript.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisAction
import de.matthiasennen.transcript.ai.aiTranscriptSourceFingerprint
import de.matthiasennen.transcript.ai.transcriptTextForAiAnalysis

@Composable
internal fun AiTranscriptAnalysisCard(
    state: TranscriptUiState,
    onStart: (AiTranscriptAnalysisAction) -> Unit,
    onCancel: () -> Unit
) {
    var showActionDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sourceText = transcriptTextForAiAnalysis(state.segments)
    val currentFingerprint = sourceText.takeIf(String::isNotBlank)
        ?.let(::aiTranscriptSourceFingerprint)
    val result = state.aiTranscriptAnalysisResult
        ?.takeIf { it.sourceFileName == (state.selectedFileName ?: "Transkript") }
    val resultOutdated = result != null && result.sourceFingerprint != currentFingerprint

    if (showActionDialog) {
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text("Mit KI auswerten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CannaBotStatusAnimation(state)
                        Text(
                            "Die lokale KI arbeitet auf der aktuell akzeptierten Fassung des gesamten Transkripts. Das Transkript selbst wird nicht verändert.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    AiTranscriptAnalysisAction.values().forEach { action ->
                        OutlinedButton(
                            onClick = {
                                showActionDialog = false
                                onStart(action)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(action.displayLabel)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { showActionDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("KI-Auswertung", style = MaterialTheme.typography.titleSmall)
            Text(
                "Optional und vollständig lokal · Ergebnis getrennt vom Transkript",
                style = MaterialTheme.typography.bodySmall
            )

            if (state.isAiTranscriptAnalysisRunning) {
                val progress = state.aiTranscriptAnalysisProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = progress.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.aiTranscriptAnalysisStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                state.activityDetail?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !state.aiTranscriptAnalysisCancellationRequested,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.aiTranscriptAnalysisCancellationRequested) {
                            "Abbruch wird durchgeführt …"
                        } else {
                            "Auswertung abbrechen"
                        }
                    )
                }
            } else {
                Button(
                    onClick = { showActionDialog = true },
                    enabled = sourceText.isNotBlank() && !state.isEditingTranscript && !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mit KI auswerten")
                }
                if (result == null) {
                    state.aiTranscriptAnalysisStatus
                        ?.takeIf(String::isNotBlank)
                        ?.let { status ->
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                }
            }

            result?.let { completed ->
                Text(completed.action.resultTitle, style = MaterialTheme.typography.titleMedium)
                if (resultOutdated) {
                    Text(
                        "Hinweis: Das Transkript wurde seit dieser Auswertung geändert. Erzeuge die Auswertung neu, um den aktuellen Stand zu verwenden.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                SelectionContainer {
                    Text(completed.text, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(completed.action.resultTitle, completed.text)
                            )
                            Toast.makeText(context, "KI-Ergebnis kopiert.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Kopieren")
                    }
                    OutlinedButton(
                        onClick = { onStart(completed.action) },
                        enabled = !state.isBusy && !state.isEditingTranscript,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Neu erzeugen")
                    }
                }
                Text(
                    "${completed.model.modelLabel} · ${completed.sourceChunkCount} Quellteil(e) · ${completed.generationCount} KI-Lauf/Läufe",
                    style = MaterialTheme.typography.labelSmall
                )
                AiTranscriptAnalysisPerformanceDetails(completed)
            }
        }
    }
}
