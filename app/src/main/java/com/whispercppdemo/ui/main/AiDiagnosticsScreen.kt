package de.matthiasennen.transcript.ui.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun AiDiagnosticsScreen(
    state: TranscriptUiState,
    onPromptChange: (String) -> Unit,
    onStart: () -> Unit,
    onResetConversation: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state)
        AiSelfTestCard(
            state = state,
            onPromptChange = onPromptChange,
            onStart = onStart,
            onResetConversation = onResetConversation
        )
        if (state.diagnostics.isNotEmpty()) {
            DiagnosticsLogCard(state.diagnostics)
        }
    }
}

@Composable
private fun DiagnosticsLogCard(entries: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Diagnose", style = MaterialTheme.typography.titleSmall)
            entries.forEach { entry ->
                Text(entry, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun LiveStatusLine(state: TranscriptUiState) {
    val estimateStatus = transcriptionEstimateStatus(state.transcriptionEstimateSeconds)
    val alternatesReadyStatus =
        state.cannaBotMode == CannaBotMode.REVIEW &&
            state.selectedAudio != null &&
            state.segments.isEmpty() &&
            state.error == null &&
            estimateStatus != null
    val announcesChangedModelEstimate =
        state.runtimeEstimateAnnouncementId > 0L &&
            state.modelReady &&
            state.selectedAudio != null &&
            !state.isBusy &&
            !state.isRecording &&
            !state.isTranscribing &&
            state.error == null &&
            estimateStatus != null
    val isActiveOperation = state.isBusy || state.isRecording || state.isPlaying ||
        state.isWaveformLoading
    val primaryStatus = if (alternatesReadyStatus) {
        state.mediaReadyStatus ?: state.status
    } else {
        state.status
    }
    val activityStatus = state.activityDetail
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != primaryStatus }
    val alternateStatus = when {
        announcesChangedModelEstimate -> estimateStatus
        alternatesReadyStatus -> estimateStatus
        isActiveOperation -> activityStatus
        else -> null
    }
    var showAlternate by remember { mutableStateOf(false) }
    var handledEstimateAnnouncementId by remember { mutableStateOf(0L) }
    LaunchedEffect(
        primaryStatus,
        alternateStatus,
        announcesChangedModelEstimate,
        state.runtimeEstimateAnnouncementId
    ) {
        if (alternateStatus == null) {
            showAlternate = false
            return@LaunchedEffect
        }
        if (
            announcesChangedModelEstimate &&
            state.runtimeEstimateAnnouncementId != handledEstimateAnnouncementId
        ) {
            handledEstimateAnnouncementId = state.runtimeEstimateAnnouncementId
            showAlternate = true
        }
        while (true) {
            delay(3_600L)
            showAlternate = !showAlternate
        }
    }
    val isActive = isActiveOperation || alternatesReadyStatus || announcesChangedModelEstimate
    val transition = rememberInfiniteTransition(label = "status-pulse")
    val alpha = if (isActive) {
        transition.animateFloat(
            initialValue = 0.20f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "status-alpha"
        ).value
    } else {
        1f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CannaBotStatusAnimation(state)
        Text(
            text = if (showAlternate) alternateStatus.orEmpty() else primaryStatus,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        )
    }
}

@Composable
private fun AiSelfTestCard(
    state: TranscriptUiState,
    onPromptChange: (String) -> Unit,
    onStart: () -> Unit,
    onResetConversation: () -> Unit
) {
    var responseExpanded by remember { mutableStateOf(false) }
    val modelInstalled = state.selectedAiModelInstalled
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("KI-Testbereich", style = MaterialTheme.typography.titleSmall)
            Text(
                "Hier kannst du eine flüchtige Unterhaltung mit dem ausgewählten lokalen KI-Modell führen. Es wird kein Chatverlauf gespeichert.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = state.aiTestPrompt,
                onValueChange = onPromptChange,
                label = { Text("Frage oder Aufgabe") },
                placeholder = { Text("Eigene Eingabe für die KI …") },
                minLines = 3,
                maxLines = 8,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onStart,
                enabled = modelInstalled && !state.isBusy && state.aiTestPrompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isAiSelfTest) "Anfrage läuft …" else "Anfrage an KI senden")
            }
            OutlinedButton(
                onClick = onResetConversation,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unterhaltung zurücksetzen")
            }
            if (!modelInstalled) {
                Text(
                    "Bitte zuerst das ausgewählte KI-Modell in den Einstellungen herunterladen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.aiSelfTestResponse?.let { response ->
                state.aiSelfTestMetrics?.let { metrics ->
                    Text(
                        "Messwerte · ${state.aiSelfTestModel?.modelLabel ?: state.selectedAiModel.modelLabel}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        buildString {
                            append("Modell: ")
                            append(
                                if (metrics.modelAlreadyLoaded) {
                                    "bereits im RAM"
                                } else {
                                    "neu geladen (${metrics.modelLoadMs} ms)"
                                }
                            )
                            append("\nModellladezeit: ${metrics.modelLoadMs} ms")
                            append(
                                if (metrics.conversationContinued) {
                                    "\nUnterhaltung: vorhandenen Kontext fortgeführt"
                                } else {
                                    "\nUnterhaltung: neu begonnen"
                                }
                            )
                            append("\nPromptverarbeitung: ${metrics.promptProcessingMs} ms")
                            append("\nZeit bis zum ersten Token: ${metrics.timeToFirstTokenMs} ms")
                            append("\nAntworterzeugung: ${metrics.answerGenerationMs} ms")
                            append("\nGesamtdauer: ${metrics.totalMs} ms")
                            append("\nTokens: ${metrics.promptTokens} Eingabe · ${metrics.generatedTokens} Antwort")
                            append("\nBeendigung: ${aiFinishReasonLabel(metrics.finishReason)}")
                            append(
                                if (metrics.thinkingDisabled) {
                                    "\nThinking: technisch deaktiviert"
                                } else {
                                    "\nThinking: von der Modellvorlage nicht bestätigt"
                                }
                            )
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = { responseExpanded = !responseExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (responseExpanded) "KI-Antwort ausblenden" else "KI-Antwort anzeigen")
                }
                if (responseExpanded) {
                    Text(response, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun aiFinishReasonLabel(reason: String): String = when (reason) {
    "eog" -> "reguläres Modellende"
    "token_limit" -> "Antwortlimit erreicht"
    "structured_result" -> "vollständiges Ergebnis"
    "decode_error" -> "Dekodierungsfehler"
    else -> reason
}
