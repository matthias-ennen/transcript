package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.ai.AiPerformanceUiPreferences
import de.matthiasennen.transcript.download.SileroVadModel

@Composable
fun VadSettingsScreen(
    state: TranscriptUiState,
    onSettingsChanged: (WhisperSettings) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = state.whisperSettings
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) {
        AiPerformanceUiPreferences(context.applicationContext, "vad_settings_cards")
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state)
        ExpandableSettingsCard("vad", "Spracherkennung und Pausen (VAD)", preferences) {
            Text(
                if (state.vadModelInstallation.isInstalled) "${SileroVadModel.modelLabel} ist installiert und einsatzbereit."
                else "Silero VAD ist nicht installiert. Whisper arbeitet ohne VAD.",
                style = MaterialTheme.typography.bodyMedium
            )
            NumberSetting("Empfindlichkeit", settings.vadThresholdPercent, "10–90 Prozent; niedriger lässt mehr Audio durch") {
                onSettingsChanged(settings.copy(vadThresholdPercent = it))
            }
            NumberSetting("Mindestlänge Sprache", settings.vadMinSpeechDurationMs, "50–2000 ms") {
                onSettingsChanged(settings.copy(vadMinSpeechDurationMs = it))
            }
            NumberSetting("Mindestdauer Pause", settings.vadMinSilenceDurationMs, "50–2000 ms") {
                onSettingsChanged(settings.copy(vadMinSilenceDurationMs = it))
            }
            NumberSetting("Maximale Sprachdauer", settings.vadMaxSpeechDurationSeconds, "30–600 Sekunden") {
                onSettingsChanged(settings.copy(vadMaxSpeechDurationSeconds = it))
            }
            NumberSetting("Sicherheitsabstand", settings.vadSpeechPadMs, "0–1000 ms vor und nach erkannter Sprache") {
                onSettingsChanged(settings.copy(vadSpeechPadMs = it))
            }
            NumberSetting("Überlappung", settings.vadOverlapMs, "0–1000 ms zwischen Sprachbereichen") {
                onSettingsChanged(settings.copy(vadOverlapMs = it))
            }
            Text(
                "Bei aktivierter Stimmisolierung arbeitet VAD auf der bereits aufbereiteten Stimmspur.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Auf Standard zurücksetzen")
            }
        }
    }
}
