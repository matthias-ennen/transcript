package de.matthiasennen.transcript.ui.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import android.os.SystemClock
import de.matthiasennen.transcript.ai.thermalStatusLabel
import kotlinx.coroutines.delay

@Composable
fun AiDiagnosticsScreen(
    state: TranscriptUiState,
    onEnter: () -> Unit,
    onPromptChange: (String) -> Unit,
    onStart: () -> Unit,
    onResetConversation: () -> Unit,
    onRefreshThermalStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        onEnter()
        while (true) {
            delay(2_000L)
            onRefreshThermalStatus()
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state)
        ThermalStatusIndicator(state.aiDiagnosticsThermalStatus)
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
private fun ThermalStatusIndicator(rawStatus: Int?) {
    val status = normalizeAiDiagnosticsThermalStatus(rawStatus)
    val colors = listOf(
        Color(0xFF4CAF50),
        Color(0xFF8BC34A),
        Color(0xFFFFC107),
        Color(0xFFFF9800),
        Color(0xFFF4511E),
        Color(0xFFD32F2F),
        Color(0xFF8E0000)
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Thermischer Zustand:",
                    style = MaterialTheme.typography.titleSmall
                )
                status?.let {
                    ThermalLegendDot(colors[it])
                    Text(
                        text = thermalStatusLabel(it),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val indicatorWidth = maxWidth
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(modifier = Modifier.height(16.dp).fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.small)
                        ) {
                            colors.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .background(color)
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.height(18.dp).fillMaxWidth()) {
                        status?.let {
                            Text(
                                text = "▲",
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.offset(
                                    x = (indicatorWidth - 16.dp) * (it / 6f)
                                )
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThermalLegendItem("Keine Drosselung", colors[0], status == 0)
                ThermalLegendItem("Leicht", colors[1], status == 1)
                ThermalLegendItem("Mittel", colors[2], status == 2)
                ThermalLegendItem("Stark", colors[3], status == 3)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThermalLegendItem("Kritisch", colors[4], status == 4)
                ThermalLegendItem("Notfall", colors[5], status == 5)
                ThermalLegendItem("Abschaltung", colors[6], status == 6)
            }
        }
    }
}

@Composable
private fun ThermalLegendItem(
    label: String,
    color: Color,
    active: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ThermalLegendDot(color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ThermalLegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
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
internal fun LiveStatusLine(
    state: TranscriptUiState,
    supplementalStatus: String? = null
) {
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
        state.isWaveformLoading || state.isAiModelPreloading
    val primaryStatus = if (alternatesReadyStatus) {
        state.mediaReadyStatus ?: state.status
    } else {
        state.status
    }
    val activityStatus = state.activityDetail
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != primaryStatus }
    val runtimeStatus = state.runtimeStatus()
    val alternateStatus = when {
        !supplementalStatus.isNullOrBlank() && supplementalStatus != primaryStatus -> supplementalStatus
        state.isTranscribing && runtimeStatus != null -> runtimeStatus
        announcesChangedModelEstimate -> estimateStatus
        alternatesReadyStatus -> estimateStatus
        !state.isBusy && runtimeStatus != null && runtimeStatus != primaryStatus -> runtimeStatus
        isActiveOperation -> activityStatus
        else -> null
    }
    val alternateCycleKey = when {
        !supplementalStatus.isNullOrBlank() -> "supplemental"
        state.isTranscribing -> "transcription-runtime"
        announcesChangedModelEstimate -> "model-estimate"
        alternatesReadyStatus -> "ready-estimate"
        !state.isBusy && runtimeStatus != null -> "completed-runtime"
        isActiveOperation && activityStatus != null -> "activity-detail"
        else -> "none"
    }
    var visiblePrimary by remember { mutableStateOf(primaryStatus) }
    var visibleKind by remember { mutableStateOf(state.statusKind) }
    var visibleUntilMs by remember { mutableStateOf(0L) }
    var showAlternate by remember { mutableStateOf(false) }
    var handledEstimateAnnouncementId by remember { mutableStateOf(0L) }
    LaunchedEffect(primaryStatus, state.statusKind, state.statusEventId) {
        val now = SystemClock.elapsedRealtime()
        if (
            shouldReplaceVisibleStatus(
                visibleKind = visibleKind,
                incomingKind = state.statusKind,
                visibleUntilMs = visibleUntilMs,
                nowMs = now
            )
        ) {
            visiblePrimary = primaryStatus
            visibleKind = state.statusKind
            visibleUntilMs = now + statusMinimumVisibleMs(state.statusKind)
        } else {
            delay((visibleUntilMs - now).coerceAtLeast(0L))
            visiblePrimary = primaryStatus
            visibleKind = state.statusKind
            visibleUntilMs = SystemClock.elapsedRealtime() +
                statusMinimumVisibleMs(state.statusKind)
        }
    }
    LaunchedEffect(
        alternateCycleKey,
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
    val isActive = isActiveOperation || alternatesReadyStatus || announcesChangedModelEstimate ||
        !supplementalStatus.isNullOrBlank() || visibleKind == StatusMessageKind.IMPORTANT
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CannaBotStatusAnimation(state)
        Text(
            text = if (showAlternate) alternateStatus.orEmpty() else visiblePrimary,
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
                value = aiDiagnosticsResponseText(
                    showWelcome = state.showAiDiagnosticsWelcome,
                    modelResponse = state.aiSelfTestResponse
                ),
                onValueChange = {},
                label = { Text("KI-Antwort") },
                minLines = 4,
                maxLines = 8,
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.aiTestPrompt,
                onValueChange = onPromptChange,
                label = { Text("Frage oder Aufgabe") },
                placeholder = { Text("Eigene Eingabe für die KI …") },
                minLines = 3,
                maxLines = 8,
                enabled = !state.isBusy || state.isAiModelPreloading,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onStart,
                enabled = canSendAiDiagnosticsRequest(
                    modelInstalled = modelInstalled,
                    modelReady = state.isAiModelReady,
                    modelPreloading = state.isAiModelPreloading,
                    operationActive = state.isBusy,
                    prompt = state.aiTestPrompt
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        state.isAiSelfTest -> "Anfrage läuft …"
                        state.isAiModelPreloading -> "KI-Modell wird geladen …"
                        else -> "Anfrage an KI senden"
                    }
                )
            }
            OutlinedButton(
                onClick = onResetConversation,
                enabled = !state.isBusy && !state.isAiModelPreloading,
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
            state.aiSelfTestResponse?.let {
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
