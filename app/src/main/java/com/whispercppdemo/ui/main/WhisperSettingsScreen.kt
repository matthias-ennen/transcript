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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.ai.AiPerformanceUiPreferences

@Composable
fun WhisperSettingsScreen(
    state: TranscriptUiState,
    onLanguageChanged: (String) -> Unit,
    onSettingsChanged: (WhisperSettings) -> Unit,
    onResetGroup: (WhisperSettingsGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = state.whisperSettings
    val preferences = androidx.compose.ui.platform.LocalContext.current.let {
        androidx.compose.runtime.remember(it.applicationContext) {
            AiPerformanceUiPreferences(it.applicationContext, "whisper_settings_cards")
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state)

        ExpandableSettingsCard("language_prompt", "Sprache und Vorgabetext", preferences) {
            ChoiceSetting(
                title = "Sprache",
                selected = state.language,
                options = listOf(
                    "auto" to "Automatisch – empfohlen",
                    "de" to "Deutsch",
                    "en" to "Englisch"
                ),
                onSelected = onLanguageChanged
            )
            TextSetting(
                title = "Vorgabetext",
                value = settings.initialPrompt,
                placeholder = "Namen, Fachbegriffe und Abkürzungen"
            ) { onSettingsChanged(settings.copy(initialPrompt = it)) }
            Text(
                "Der Vorgabetext weist Whisper auf erwartete Schreibweisen hin, erzwingt sie aber nicht.",
                style = MaterialTheme.typography.bodySmall
            )
            ResetButton { onResetGroup(WhisperSettingsGroup.DETECTION) }
        }

        ExpandableSettingsCard("compute", "Rechenleistung", preferences) {
            NumberSetting("CPU-Threads", settings.threads, "0 = automatisch; sonst 1 bis CPU-Kernzahl") {
                onSettingsChanged(settings.copy(threads = it))
            }
            ChoiceSetting(
                "Rechenbackend",
                settings.backend,
                listOf(
                    WhisperComputeBackend.AUTO to "Automatisch",
                    WhisperComputeBackend.CPU to "Nur CPU",
                    WhisperComputeBackend.VULKAN to "GPU/Vulkan bevorzugen"
                )
            ) { onSettingsChanged(settings.copy(backend = it)) }
            Text(
                "Automatisch und Vulkan erlauben die vorhandene GPU-Unterstützung; ist sie nicht nutzbar, verwendet whisper.cpp die CPU.",
                style = MaterialTheme.typography.bodySmall
            )
            ResetButton { onResetGroup(WhisperSettingsGroup.COMPUTE) }
        }

        ExpandableSettingsCard("vad", "Spracherkennung und Pausen (VAD)", preferences) {
            Text(
                "VAD benötigt zusätzlich ein separates Spracherkennungsmodell. Dieses Modell ist noch nicht Bestandteil der App; deshalb werden hier noch keine wirkungslosen Regler angeboten.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        ExpandableSettingsCard("decoding", "Erkennungsgenauigkeit", preferences) {
            ChoiceSetting(
                "Dekodierungsverfahren",
                settings.decoding,
                listOf(
                    WhisperDecoding.GREEDY to "Schnell",
                    WhisperDecoding.BEAM_SEARCH to "Beam Search – genauer"
                )
            ) { onSettingsChanged(settings.copy(decoding = it)) }
            NumberSetting("Beam-Größe", settings.beamSize, "1–20; nur bei Beam Search") {
                onSettingsChanged(settings.copy(beamSize = it))
            }
            NumberSetting("Alternative Ergebnisse", settings.bestOf, "1–20; nur im schnellen Verfahren") {
                onSettingsChanged(settings.copy(bestOf = it))
            }
            NumberSetting("Temperatur", settings.temperaturePercent, "0–100 Prozent; niedrig ist für Transkription meist besser") {
                onSettingsChanged(settings.copy(temperaturePercent = it))
            }
            BooleanSetting(
                "Kontext übernehmen",
                "Vorherige Textabschnitte helfen beim sprachlichen Zusammenhang.",
                settings.carryContext
            ) { onSettingsChanged(settings.copy(carryContext = it)) }
            ResetButton { onResetGroup(WhisperSettingsGroup.DECODING) }
        }

        ExpandableSettingsCard("segments", "Segmente, Zeitstempel und Chunking", preferences) {
            NumberSetting("Maximale Segmentlänge", settings.maximumSegmentCharacters, "0 = unbegrenzt; sonst 1–500 Zeichen") {
                onSettingsChanged(settings.copy(maximumSegmentCharacters = it))
            }
            BooleanSetting(
                "An Wortgrenzen aufteilen",
                "Trennt Segmente möglichst nicht mitten im Wort.",
                settings.splitOnWord
            ) { onSettingsChanged(settings.copy(splitOnWord = it)) }
            ChoiceSetting(
                "Zeitstempel",
                settings.timestampMode,
                listOf(
                    WhisperTimestampMode.SEGMENTS to "Abschnittsweise",
                    WhisperTimestampMode.WORDS to "Wortgenaue Berechnung"
                )
            ) { onSettingsChanged(settings.copy(timestampMode = it)) }
            NumberSetting("Abschnittslänge", settings.sectionMinutes, "1–10 Minuten; Standard 5 Minuten") {
                onSettingsChanged(settings.copy(sectionMinutes = it))
            }
            Text(
                "Die App verarbeitet Abschnitte nacheinander. Mehrere parallele Whisper-Modelle würden den Arbeitsspeicherbedarf vervielfachen und bleiben deshalb deaktiviert.",
                style = MaterialTheme.typography.bodySmall
            )
            ResetButton { onResetGroup(WhisperSettingsGroup.SEGMENTS) }
        }

        ExpandableSettingsCard("protection", "Unterdrückung und Halluzinationsschutz", preferences) {
            BooleanSetting("Leere Ausgaben unterdrücken", "Unterdrückt leere Startausgaben.", settings.suppressBlank) {
                onSettingsChanged(settings.copy(suppressBlank = it))
            }
            BooleanSetting("Nicht-Sprach-Tokens unterdrücken", "Reduziert Geräusch- und Musikmarkierungen.", settings.suppressNonSpeechTokens) {
                onSettingsChanged(settings.copy(suppressNonSpeechTokens = it))
            }
            NumberSetting("Mindest-Log-Wahrscheinlichkeit", settings.logProbabilityThresholdPercent, "−500 bis 0; Standard −100 = −1,00") {
                onSettingsChanged(settings.copy(logProbabilityThresholdPercent = it))
            }
            NumberSetting("Stille-Schwelle", settings.noSpeechThresholdPercent, "0–100 Prozent; Standard 60") {
                onSettingsChanged(settings.copy(noSpeechThresholdPercent = it))
            }
            NumberSetting("Kompressionsschwelle", settings.entropyThresholdPercent, "0–500; Standard 240 = 2,40") {
                onSettingsChanged(settings.copy(entropyThresholdPercent = it))
            }
            ResetButton { onResetGroup(WhisperSettingsGroup.PROTECTION) }
        }
    }
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text("Auf Standard zurücksetzen")
    }
}
