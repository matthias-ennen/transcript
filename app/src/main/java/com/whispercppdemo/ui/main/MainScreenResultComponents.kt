package de.matthiasennen.transcript.ui.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.ai.AiCorrectionTrace
import de.matthiasennen.transcript.download.DownloadStorageIssue

@Composable
internal fun CannaBotQuestionDialog(
    state: TranscriptUiState,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "cannabot-question-pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cannabot-question-alpha"
    ).value
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CannaBotStatusAnimation(state)
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(50)) {
                Text(dismissLabel)
            }
        }
    )
}

@Composable
internal fun CancelTranscriptionDialog(
    state: TranscriptUiState,
    onContinue: () -> Unit,
    onConfirmCancellation: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "cancel-question-pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cancel-question-alpha"
    ).value
    AlertDialog(
        onDismissRequest = onContinue,
        shape = RoundedCornerShape(28.dp),
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CannaBotStatusAnimation(state)
                Text(
                    text = "Möchtest du die Transkription wirklich abbrechen?",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirmCancellation, shape = RoundedCornerShape(50)) {
                Text("Abbrechen")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onContinue, shape = RoundedCornerShape(50)) {
                Text("Weiter")
            }
        }
    )
}

@Composable
internal fun RecordingFolderRequiredDialog(
    state: TranscriptUiState,
    onDismiss: () -> Unit,
    onChooseFolder: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "recording-folder-question-pulse")
    val alpha = transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording-folder-question-alpha"
    ).value
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CannaBotStatusAnimation(state)
                Text(
                    text = "Lege einen Ordner fest, in dem Transcript deine Aufnahmen speichert.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                )
            }
        },
        confirmButton = {
            Button(onClick = onChooseFolder, shape = RoundedCornerShape(50)) {
                Text("Ordner wählen")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(50)) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
internal fun DownloadStorageRequiredDialog(
    state: TranscriptUiState,
    issue: DownloadStorageIssue,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onConfirm,
        shape = RoundedCornerShape(28.dp),
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CannaBotStatusAnimation(state)
                Text(
                    text = "Es steht nicht genügend Speicherplatz zur Verfügung. " +
                        "Für ${issue.modelLabel} werden ${formatDownloadSize(issue.requiredFreeBytes)} freier Speicher benötigt; " +
                        "verfügbar sind ${formatDownloadSize(issue.availableBytes)}.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) { Text("Okay") }
        }
    )
}

@Composable
internal fun AiCorrectionTraceCard(trace: AiCorrectionTrace) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Letzte KI-Korrektur · Segment ${trace.segmentNumber}", style = MaterialTheme.typography.titleSmall)
            Text(trace.decision, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Interaktion ausblenden" else "Interaktion anzeigen")
            }
            if (expanded) {
                Text("Whisper-Original", style = MaterialTheme.typography.labelLarge)
                Text(trace.originalText, style = MaterialTheme.typography.bodyMedium)
                Text("KI-Rohantwort", style = MaterialTheme.typography.labelLarge)
                Text(trace.rawResponse, style = MaterialTheme.typography.bodyMedium)
                Text("Von der App verwendetes Ergebnis", style = MaterialTheme.typography.labelLarge)
                Text(trace.resultText, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun ModelManagerCard(state: TranscriptUiState, onDownload: () -> Unit) {
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
                        ?.let(::formatDownloadSize) ?: model.downloadSizeLabel
                    Text("Download: $downloaded von $total")
                    state.progress?.let {
                        LinearProgressIndicator(progress = it, modifier = Modifier.fillMaxWidth())
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                installed -> Text(
                    "Aktiv · Installiert (${formatDownloadSize(installation?.installedBytes ?: 0L)})",
                    color = MaterialTheme.colorScheme.primary
                )
                else -> {
                    Text("Noch nicht installiert · Download ${model.downloadSizeLabel}")
                    Button(
                        onClick = onDownload,
                        enabled = !state.isBusy && !state.isRecording,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Modell herunterladen") }
                }
            }
        }
    }
}

@Composable
internal fun TranscriptResultSummary(state: TranscriptUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Ergebnis", style = MaterialTheme.typography.titleSmall)
            state.completedModel?.let { Text("Modell: ${it.modelLabel}") }
            state.detectedLanguage?.let {
                Text("Erkannte Sprache: ${whisperLanguageDisplayName(it)}")
            }
            state.transcriptionDurationSeconds?.let { Text("Transkriptionszeit: ${formatClock(it)}") }
            state.vadProcessingSummary?.let { summary ->
                val mode = when (summary.requestedMode) {
                    WhisperVadMode.OFF -> "Aus"
                    WhisperVadMode.AUTOMATIC -> "Automatisch"
                    WhisperVadMode.ON -> "Ein"
                }
                Text("VAD: $mode · ${if (summary.usedVad) "verwendet" else "vollständiges Audio"}")
                if (summary.measurementsAvailable) {
                    Text(
                        "Audio: ${formatClock(summary.originalDurationMs / 1_000L)} original · " +
                            "${formatClock(summary.processedDurationMs / 1_000L)} verarbeitet · " +
                            "${formatClock(summary.skippedDurationMs / 1_000L)} übersprungen"
                    )
                    val skippedPercent = if (summary.originalDurationMs > 0L) {
                        (summary.skippedDurationMs * 100L / summary.originalDurationMs)
                            .coerceIn(0L, 100L)
                    } else {
                        0L
                    }
                    Text("Pauseneinsparung: $skippedPercent % · ${summary.speechRegionCount} Sprachbereiche")
                } else {
                    Text("Audioeinsparung: In diesem Modus nicht separat vorgemessen.")
                }
                Text("VAD-Entscheidung: ${summary.reason}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Textabschnitte: ${state.segments.count { it.text.isNotBlank() }}")
        }
    }
}
