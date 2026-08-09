package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.BuildConfig
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiModelInstallation

@Composable
fun SettingsScreen(
    state: TranscriptUiState,
    onDeleteModel: (WhisperModel) -> Unit,
    onDeleteAllModels: () -> Unit,
    onAiEnabledChanged: (Boolean) -> Unit,
    onAiAutomaticChanged: (Boolean) -> Unit,
    onSelectAiModel: (AiModel) -> Unit,
    onDownloadAiModel: (AiModel) -> Unit,
    onDeleteAiModel: (AiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var modelToDelete by remember { mutableStateOf<WhisperModel?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var aiModelToDelete by remember { mutableStateOf<AiModel?>(null) }
    val totalBytes = state.modelInstallations.sumOf(ModelInstallation::storedBytes)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Whisper-Modellverwaltung", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Hier kannst du installierte Modelle und unvollständige Downloads entfernen, um Speicherplatz freizugeben.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Belegter Speicher: ${formatDownloadSize(totalBytes)}",
            style = MaterialTheme.typography.titleMedium
        )

        state.modelInstallations.forEach { installation ->
            ModelStorageCard(
                installation = installation,
                enabled = !state.isBusy && !state.isRecording,
                onDelete = { modelToDelete = installation.model }
            )
        }

        OutlinedButton(
            onClick = { confirmDeleteAll = true },
            enabled = totalBytes > 0L && !state.isBusy && !state.isRecording,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Alle Modelle löschen")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("KI-Nachbearbeitung", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Lokale KI glättet erkannte Texte, ohne Transkripte an einen Server zu senden.",
                    style = MaterialTheme.typography.bodyMedium
                )
                AiSettingSwitch(
                    title = "KI-Nachbearbeitung aktivieren",
                    description = "Schaltet die optionale lokale Textkorrektur frei.",
                    checked = state.aiPostProcessingEnabled,
                    enabled = !state.isBusy && !state.isRecording,
                    onCheckedChange = onAiEnabledChanged
                )
                AiSettingSwitch(
                    title = "Nach der Transkription automatisch ausführen",
                    description = "Whisper wird zuerst entladen; danach bearbeitet die KI das gesamte Transkript.",
                    checked = state.automaticAiPostProcessingEnabled,
                    enabled = state.aiPostProcessingEnabled && !state.isBusy && !state.isRecording,
                    onCheckedChange = onAiAutomaticChanged
                )

                Text("Lokales KI-Modell", style = MaterialTheme.typography.titleLarge)
                state.aiModelInstallations.forEach { installation ->
                    AiModelStorageCard(
                        installation = installation,
                        selected = installation.model == state.selectedAiModel,
                        enabled = !state.isBusy && !state.isRecording,
                        isDownloading = state.downloadingAiModel == installation.model,
                        downloadedBytes = state.aiDownloadedBytes,
                        totalBytes = state.aiDownloadTotalBytes,
                        onSelect = { onSelectAiModel(installation.model) },
                        onDownload = { onDownloadAiModel(installation.model) },
                        onDelete = { aiModelToDelete = installation.model }
                    )
                }
                Text(
                    "Für die beste Qualität empfehlen wir „Ausgewogen“. Größere Modelle benötigen mehr Arbeitsspeicher und Zeit.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (state.isBusy) {
            Text(state.status, style = MaterialTheme.typography.bodySmall)
        }
    }

    modelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text("Modell löschen?") },
            text = {
                Text("${model.modelLabel} und ein eventuell angefangener Download werden vom Gerät entfernt.")
            },
            confirmButton = {
                TextButton(onClick = {
                    modelToDelete = null
                    onDeleteModel(model)
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) { Text("Abbrechen") }
            }
        )
    }

    aiModelToDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { aiModelToDelete = null },
            title = { Text("KI-Modell löschen?") },
            text = {
                Text("${model.modelLabel} und ein eventuell angefangener Download werden entfernt.")
            },
            confirmButton = {
                TextButton(onClick = {
                    aiModelToDelete = null
                    onDeleteAiModel(model)
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { aiModelToDelete = null }) { Text("Abbrechen") }
            }
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Alle Modelle löschen?") },
            text = {
                Text("Alle installierten Whisper-Modelle und unvollständigen Downloads werden entfernt.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onDeleteAllModels()
                }) { Text("Alle löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun AiSettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun AiModelStorageCard(
    installation: AiModelInstallation,
    selected: Boolean,
    enabled: Boolean,
    isDownloading: Boolean,
    downloadedBytes: Long,
    totalBytes: Long,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val model = installation.model
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(model.qualityLabel, style = MaterialTheme.typography.titleMedium)
            Text(model.modelLabel, style = MaterialTheme.typography.bodyMedium)
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    isDownloading && totalBytes > 0L ->
                        "Download · ${formatDownloadSize(downloadedBytes)} von ${formatDownloadSize(totalBytes)}"
                    isDownloading -> "Download wird vorbereitet …"
                    installation.isInstalled ->
                        "Installiert · ${formatDownloadSize(installation.installedBytes)}${if (selected) " · Ausgewählt" else ""}"
                    installation.partialBytes > 0L ->
                        "Download angefangen · ${formatDownloadSize(installation.partialBytes)} von ${model.downloadSizeLabel}"
                    else -> "Nicht installiert · Download ${model.downloadSizeLabel}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (isDownloading) {
                if (totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (!installation.isInstalled) {
                Button(
                    onClick = onDownload,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (installation.partialBytes > 0L) "Download fortsetzen" else "Herunterladen")
                }
            } else if (!selected) {
                Button(
                    onClick = onSelect,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Auswählen")
                }
            }
            if (installation.storedBytes > 0L && !isDownloading) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Löschen")
                }
            }
        }
    }
}

@Composable
private fun ModelStorageCard(
    installation: ModelInstallation,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(installation.model.qualityLabel, style = MaterialTheme.typography.titleMedium)
            Text(installation.model.modelLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    installation.isInstalled ->
                        "Installiert · ${formatDownloadSize(installation.installedBytes)}"
                    installation.partialBytes > 0L ->
                        "Download angefangen · ${formatDownloadSize(installation.partialBytes)}"
                    installation.installedBytes > 0L ->
                        "Unvollständige Modelldatei · ${formatDownloadSize(installation.installedBytes)}"
                    else -> "Nicht installiert"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (installation.storedBytes > 0L) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Löschen")
                }
            }
        }
    }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Simple Transcript", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Lokale Transkription von Audio- und Videodateien mit Whisper.",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Version ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.bodyMedium
        )

        InfoCard("Entwickler") {
            Text("Entwickelt von Matthias Ennen")
            TextButton(onClick = { uriHandler.openUri("mailto:matthias.ennen@gmx.de") }) {
                Text("matthias.ennen@gmx.de")
            }
            TextButton(
                onClick = { uriHandler.openUri("https://github.com/matthias-ennen/transcript") }
            ) {
                Text("GitHub-Projekt öffnen")
            }
        }

        InfoCard("Datenschutz") {
            Text(
                "Audio, Video, Transkription und KI-Nachbearbeitung bleiben auf diesem Gerät. Nur Modelldownloads benötigen eine Internetverbindung."
            )
        }

        InfoCard("Open Source & Lizenzen") {
            Text(
                "Die Spracherkennung verwendet whisper.cpp und die lokale KI llama.cpp unter MIT-Lizenz. Qwen3.5-Modelle stehen unter Apache 2.0."
            )
        }

        InfoCard("Impressum") {
            Text("Matthias Ennen")
            Text("Kontakt: matthias.ennen@gmx.de")
            Text(
                "Weitere gesetzlich erforderliche Anbieterangaben werden vor einer öffentlichen Veröffentlichung ergänzt.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Entwickler unterstützen", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Hier kannst du dem Entwickler später einen kleinen Kaffee ausgeben. Die Unterstützungsfunktion wird in einer kommenden Version ergänzt."
                )
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Demnächst verfügbar")
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
