package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.BuildConfig
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiModelInstallation
import de.matthiasennen.transcript.download.SileroVadModel

@Composable
fun SettingsScreen(
    state: TranscriptUiState,
    onEnter: () -> Unit,
    onOpenAiDiagnostics: () -> Unit,
    onOpenAiPerformance: () -> Unit,
    onOpenWhisperSettings: () -> Unit,
    onOpenVadSettings: () -> Unit,
    onSelectModel: (WhisperModel) -> Unit,
    onDeleteModel: (WhisperModel) -> Unit,
    onDeleteAllModels: () -> Unit,
    onDownloadVadModel: () -> Unit,
    onDeleteVadModel: () -> Unit,
    onAiEnabledChanged: (Boolean) -> Unit,
    onAiAutomaticChanged: (Boolean) -> Unit,
    onSelectAiModel: (AiModel) -> Unit,
    onDownloadAiModel: (AiModel) -> Unit,
    onDeleteAiModel: (AiModel) -> Unit,
    onDeleteAllAiModels: () -> Unit,
    onChooseRecordingFolder: () -> Unit,
    onRefreshDeviceStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var modelToDelete by remember { mutableStateOf<WhisperModel?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var aiModelToDelete by remember { mutableStateOf<AiModel?>(null) }
    var confirmDeleteAllAi by remember { mutableStateOf(false) }
    var confirmDeleteVad by remember { mutableStateOf(false) }
    val totalBytes = state.modelInstallations.sumOf(ModelInstallation::storedBytes)

    LaunchedEffect(Unit) {
        onRefreshDeviceStorage()
        onEnter()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state)
        DeviceStorageCard(state.deviceStorage)
        RecordingFolderCard(
            folderName = state.recordingFolderName,
            enabled = !state.isBusy && !state.isRecording,
            onChooseFolder = onChooseRecordingFolder
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Whisper-Modellverwaltung", style = MaterialTheme.typography.headlineSmall)
                Text("Hier kannst du installierte Modelle und unvollständige Downloads entfernen, um Speicherplatz freizugeben.", style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onOpenWhisperSettings,
                    modifier = Modifier.align(Alignment.Start),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "Whisper-Einstellungen",
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                }
                Text("Belegter Speicher: ${formatDownloadSize(totalBytes)}", style = MaterialTheme.typography.titleMedium)
                state.modelInstallations.forEach { installation ->
                    ModelStorageCard(
                        installation = installation,
                        selected = installation.model == state.selectedModel,
                        enabled = !state.isBusy && !state.isRecording,
                        onSelect = { onSelectModel(installation.model) },
                        onDelete = { modelToDelete = installation.model }
                    )
                }
                Text(
                    "Für die meisten Aufnahmen bietet Ausgewogen ein gutes Verhältnis aus Genauigkeit, Laufzeit und Speicherbedarf.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { confirmDeleteAll = true }, enabled = totalBytes > 0L && !state.isBusy && !state.isRecording, modifier = Modifier.fillMaxWidth()) { Text("Alle Modelle löschen") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Silero VAD", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onOpenVadSettings, contentPadding = PaddingValues(0.dp)) {
                    Text("VAD-Einstellungen", color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = if (state.vadModelInstallation.isInstalled) BorderStroke(2.dp, Color.White) else null
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(SileroVadModel.modelLabel, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Erkennt Sprach- und Pausenbereiche, damit Whisper längere stille Abschnitte gezielt überspringen kann.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            when {
                                state.vadModelInstallation.isInstalled ->
                                    "Installiert · 0,9 MB · Ausgewählt"
                                state.vadModelInstallation.partialBytes > 0L ->
                                    "Download angefangen · ${formatDownloadSize(state.vadModelInstallation.partialBytes)} von 0,9 MB"
                                else -> "Nicht installiert · Download 0,9 MB"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (state.isVadDownloading) {
                            if (state.vadDownloadTotalBytes > 0L) {
                                LinearProgressIndicator(
                                    progress = (state.vadDownloadedBytes.toFloat() / state.vadDownloadTotalBytes).coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (!state.vadModelInstallation.isInstalled) {
                            Button(onClick = onDownloadVadModel, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.vadModelInstallation.partialBytes > 0L) "Download fortsetzen" else "Herunterladen")
                            }
                        }
                        if (state.vadModelInstallation.storedBytes > 0L && !state.isVadDownloading) {
                            OutlinedButton(onClick = { confirmDeleteVad = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.vadModelInstallation.isInstalled) "Löschen" else "Unvollständigen Download löschen")
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Lokale KI", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Hier werden die lokalen Qwen-Modelle sowie Diagnose und Leistungsprofile verwaltet. Die KI verändert das Transkript nicht mehr; separate Auswertungen des fertigen Transkripts folgen in einem eigenen Arbeitspaket.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = onOpenAiDiagnostics,
                    modifier = Modifier.align(Alignment.Start),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "KI-Diagnose",
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                }
                TextButton(
                    onClick = onOpenAiPerformance,
                    modifier = Modifier.align(Alignment.Start),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "KI-Leistung und Hardware",
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    )
                }

                Text("Lokales KI-Modell", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Belegter Speicher: ${formatDownloadSize(state.aiModelInstallations.sumOf(AiModelInstallation::storedBytes))}",
                    style = MaterialTheme.typography.titleMedium
                )
                state.aiModelInstallations.forEach { installation ->
                    AiModelStorageCard(
                        installation = installation,
                        selected = installation.model == state.selectedAiModel,
                        enabled = !state.isBusy && !state.isRecording &&
                            !state.isAiModelPreloading,
                        isDownloading = state.downloadingAiModel == installation.model,
                        downloadedBytes = state.aiDownloadedBytes,
                        totalBytes = state.aiDownloadTotalBytes,
                        onSelect = { onSelectAiModel(installation.model) },
                        onDownload = { onDownloadAiModel(installation.model) },
                        onDelete = { aiModelToDelete = installation.model }
                    )
                }
                OutlinedButton(
                    onClick = { confirmDeleteAllAi = true },
                    enabled = state.aiModelInstallations.any { it.storedBytes > 0L } &&
                        !state.isBusy && !state.isRecording && !state.isAiModelPreloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Alle KI-Modelle löschen")
                }
                Text(
                    "Für die beste Qualität empfehlen wir „Ausgewogen“. Größere Modelle benötigen mehr Arbeitsspeicher und Zeit.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
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

    if (confirmDeleteAllAi) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAllAi = false },
            title = { Text("Alle KI-Modelle löschen?") },
            text = {
                Text("Alle installierten KI-Modelle und unvollständigen Downloads werden entfernt.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAllAi = false
                    onDeleteAllAiModels()
                }) { Text("Alle löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAllAi = false }) { Text("Abbrechen") }
            }
        )
    }

    if (confirmDeleteVad) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVad = false },
            title = { Text("Silero VAD löschen?") },
            text = { Text("Das installierte Modell und ein eventuell unvollständiger Download werden entfernt.") },
            confirmButton = { TextButton(onClick = { confirmDeleteVad = false; onDeleteVadModel() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDeleteVad = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun RecordingFolderCard(
    folderName: String?,
    enabled: Boolean,
    onChooseFolder: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Eigene Aufnahmen", style = MaterialTheme.typography.titleLarge)
            if (folderName == null) {
                Text(
                    "Noch kein Aufnahmeordner festgelegt. Neue Aufnahmen starten erst nach der Ordnerauswahl.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("Aufnahmen werden gespeichert in", style = MaterialTheme.typography.bodyMedium)
                Text(folderName, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = onChooseFolder,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (folderName == null) "Ordner wählen" else "Ordner ändern")
            }
        }
    }
}

@Composable
private fun DeviceStorageCard(storage: DeviceStorageSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Gerätespeicher", style = MaterialTheme.typography.titleLarge)
            if (storage.totalBytes > 0L) {
                Text(
                    "${formatDownloadSize(storage.usedBytes)} belegt · ${formatDownloadSize(storage.freeBytes)} frei",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = storage.usedFraction,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Speicherwerte sind derzeit nicht verfügbar.", style = MaterialTheme.typography.bodyMedium)
            }
        }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, Color.White) else null
    ) {
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
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, Color.White) else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(installation.model.qualityLabel, style = MaterialTheme.typography.titleMedium)
            Text(installation.model.modelLabel, style = MaterialTheme.typography.bodyMedium)
            Text(installation.model.description, style = MaterialTheme.typography.bodySmall)
            Text(
                when {
                    installation.isInstalled ->
                        "Installiert · ${formatDownloadSize(installation.installedBytes)}${if (selected) " · Ausgewählt" else ""}"
                    installation.partialBytes > 0L ->
                        "Download angefangen · ${formatDownloadSize(installation.partialBytes)}"
                    installation.installedBytes > 0L ->
                        "Unvollständige Modelldatei · ${formatDownloadSize(installation.installedBytes)}"
                    else -> "Nicht installiert"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (installation.isInstalled && !selected) {
                Button(
                    onClick = onSelect,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Auswählen") }
            }
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
                "Audio, Video, Transkription und lokale KI-Verarbeitung bleiben auf diesem Gerät. Nur Modelldownloads benötigen eine Internetverbindung."
            )
        }

        InfoCard("Open Source & Lizenzen") {
            Text(
                "Die Spracherkennung verwendet whisper.cpp und die lokale KI llama.cpp unter MIT-Lizenz. Qwen3.5-Modelle und die eingebauten KleidiAI-CPU-Kernel stehen unter Apache 2.0."
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