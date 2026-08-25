package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun NumberChoiceSetting(
    title: String,
    value: Int,
    description: String,
    regularOptions: List<Int>,
    enabled: Boolean = true,
    labelForValue: (Int) -> String = { defaultNumberLabel(title, it) },
    onValueChanged: (Int) -> Unit
) {
    val options = remember(regularOptions, value) {
        SettingsOptionCatalogs.withCurrentValue(regularOptions, value)
    }
    val regularSet = remember(regularOptions) { regularOptions.toSet() }
    val labeled = options.map { option ->
        val suffix = if (option == value && option !in regularSet) " · gespeicherter Wert" else ""
        option to (labelForValue(option) + suffix)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (labeled.size > 40) {
            SearchableChoiceSetting(
                title = title,
                selected = value,
                options = labeled,
                enabled = enabled,
                onSelected = onValueChanged
            )
        } else {
            ChoiceSetting(
                title = title,
                selected = value,
                options = labeled,
                enabled = enabled,
                onSelected = onValueChanged
            )
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> SearchableChoiceSetting(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    enabled: Boolean,
    onSelected: (T) -> Unit
) {
    var open by remember(title) { mutableStateOf(false) }
    var query by remember(title) { mutableStateOf("") }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = {
                query = ""
                open = true
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(selectedLabel)
        }
    }
    if (open) {
        val filtered = options.filter { (_, label) ->
            query.isBlank() || label.contains(query, ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Auswahl filtern") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.first.toString() }) { (option, label) ->
                            TextButton(
                                onClick = {
                                    open = false
                                    onSelected(option)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (option == selected) "✓  $label" else label,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Schließen") }
            }
        )
    }
}

@Composable
internal fun CpuCoreMaskSetting(
    value: String,
    processorCount: Int,
    maximumFrequenciesKhz: List<Long> = emptyList(),
    enabled: Boolean = true,
    onValueChanged: (String) -> Unit
) {
    val limit = processorCount.coerceIn(1, 64)
    val allCores = remember(limit) { (0 until limit).toSet() }
    val selected = remember(value, limit) {
        SettingsOptionCatalogs.parseCpuCoreMask(value, limit)
    }
    var open by remember { mutableStateOf(false) }
    val label = if (selected.size == limit) {
        "Alle CPU-Kerne"
    } else {
        selected.sorted().joinToString { "CPU $it" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("CPU-Kerne", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label.ifBlank { "Mindestens einen CPU-Kern auswählen" })
        }
        Text(
            "Beliebige erkannte Kerne kombinieren; „Alle CPU-Kerne“ entspricht der automatischen leeren Kernmaske.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("CPU-Kerne") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    item {
                        TextButton(
                            onClick = { onValueChanged("") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(checked = selected.size == limit, onCheckedChange = null)
                                Text("Alle CPU-Kerne")
                            }
                        }
                    }
                    items((0 until limit).toList(), key = { it }) { core ->
                        val checked = core in selected
                        val frequency = maximumFrequenciesKhz.getOrNull(core)?.takeIf { it > 0L }
                        val coreLabel = buildString {
                            append("CPU ")
                            append(core)
                            if (frequency != null) append(" · ${frequency / 1_000} MHz")
                        }
                        TextButton(
                            onClick = {
                                val next = if (checked) selected - core else selected + core
                                if (next.isNotEmpty()) {
                                    onValueChanged(SettingsOptionCatalogs.serializeCpuCoreMask(next, limit))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(coreLabel)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Fertig") }
            }
        )
    }
}

internal fun defaultNumberOptions(title: String): List<Int> = when (title) {
    "CPU-Threads" -> SettingsOptionCatalogs.processorThreads(Runtime.getRuntime().availableProcessors(), includeAutomatic = true)
    "Beam-Größe" -> SettingsOptionCatalogs.whisperBeamSize
    "Alternative Ergebnisse" -> SettingsOptionCatalogs.whisperBestOf
    "Temperatur" -> SettingsOptionCatalogs.whisperTemperaturePercent
    "Maximale Segmentlänge" -> SettingsOptionCatalogs.whisperMaximumSegmentCharacters
    "Mindest-Log-Wahrscheinlichkeit" -> SettingsOptionCatalogs.whisperLogProbabilityPercent
    "Stille-Schwelle" -> SettingsOptionCatalogs.whisperNoSpeechPercent
    "Kompressionsschwelle" -> SettingsOptionCatalogs.whisperEntropyPercent
    "Empfindlichkeit" -> SettingsOptionCatalogs.vadThresholdPercent
    "Mindestlänge Sprache", "Mindestdauer Pause" -> SettingsOptionCatalogs.vadSpeechDurationMs
    "Maximale Sprachdauer" -> SettingsOptionCatalogs.vadMaximumSpeechSeconds
    "Sicherheitsabstand", "Überlappung" -> SettingsOptionCatalogs.vadPaddingMs
    "Kontextgröße" -> SettingsOptionCatalogs.aiContextSize
    "Threads für Textausgabe", "Threads für Texteingabe", "Threads bei Wärmereduzierung" ->
        SettingsOptionCatalogs.processorThreads(Runtime.getRuntime().availableProcessors())
    "Thread-Polling" -> SettingsOptionCatalogs.aiThreadPollingPercent
    "KleidiAI SME-Einheiten" -> SettingsOptionCatalogs.aiKleidiSmeUnits
    "KleidiAI Chunk-Multiplikator" -> SettingsOptionCatalogs.aiKleidiChunkMultiplier
    "Freie RAM-Reserve" -> SettingsOptionCatalogs.aiMinimumFreeMemoryMb
    "Maximaler RAM-Anteil" -> SettingsOptionCatalogs.aiMaximumMemoryPercent
    "Maximaler Vulkan-Speicheranteil" -> SettingsOptionCatalogs.aiMaximumVulkanMemoryPercent
    "GPU-Schichten je Reduktionsschritt" -> SettingsOptionCatalogs.aiGpuLayersReducedPerStep
    "Abkühlpause" -> SettingsOptionCatalogs.aiCoolingPauseSeconds
    "Aufwärmdurchläufe" -> SettingsOptionCatalogs.benchmarkWarmupRuns
    "Messdurchläufe" -> SettingsOptionCatalogs.benchmarkMeasuredRuns
    "Testprompt-Länge" -> SettingsOptionCatalogs.benchmarkPromptCharacters
    "Ausgabetokens pro Test" -> SettingsOptionCatalogs.benchmarkOutputTokens
    "Pause zwischen Läufen" -> SettingsOptionCatalogs.benchmarkPauseSeconds
    "Mindestakkustand" -> SettingsOptionCatalogs.benchmarkMinimumBatteryPercent
    else -> emptyList()
}

internal fun defaultNumberLabel(title: String, value: Int): String = when (title) {
    "CPU-Threads" -> if (value == 0) "Automatisch" else "$value Thread${if (value == 1) "" else "s"}"
    "Temperatur", "Stille-Schwelle", "Empfindlichkeit", "Thread-Polling",
    "Maximaler RAM-Anteil", "Maximaler Vulkan-Speicheranteil", "GPU-Anteil", "Mindestakkustand" -> "$value %"
    "Maximale Segmentlänge" -> if (value == 0) "Unbegrenzt" else "$value Zeichen"
    "Mindest-Log-Wahrscheinlichkeit", "Kompressionsschwelle" -> String.format(Locale.GERMANY, "%.2f", value / 100.0)
    "Mindestlänge Sprache", "Mindestdauer Pause", "Sicherheitsabstand", "Überlappung" -> "$value ms"
    "Maximale Sprachdauer", "Abkühlpause", "Pause zwischen Läufen" -> "$value s"
    "Kontextgröße", "Prompt-Batchgröße", "Physische Micro-Batchgröße", "Maximale Ausgabetokens",
    "Testprompt-Länge", "Ausgabetokens pro Test" -> formatSettingsInteger(value)
    "KleidiAI SME-Einheiten" -> when (value) { -1 -> "Automatisch"; 0 -> "Aus"; else -> value.toString() }
    "KleidiAI Chunk-Multiplikator" -> if (value == 0) "Automatisch" else value.toString()
    "GPU-Schichten" -> if (value == -1) "Vollständig" else "$value Schicht${if (value == 1) "" else "en"}"
    "Freie RAM-Reserve" -> "${formatSettingsInteger(value)} MB"
    else -> value.toString()
}

private fun formatSettingsInteger(value: Int): String = String.format(Locale.GERMANY, "%,d", value)
