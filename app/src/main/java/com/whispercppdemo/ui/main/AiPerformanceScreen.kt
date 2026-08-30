package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.ai.AiBenchmarkResult
import de.matthiasennen.transcript.ai.AiHardwareSnapshot
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiPerformanceUiPreferences
import de.matthiasennen.transcript.ai.LocalAiBackend
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.ai.LocalAiCpuBackend
import de.matthiasennen.transcript.ai.LocalAiFlashAttention
import de.matthiasennen.transcript.ai.LocalAiLoadMode
import de.matthiasennen.transcript.ai.LocalAiThreadPriority
import de.matthiasennen.transcript.ai.thermalStatusLabel
import java.util.Locale

@Composable
fun AiPerformanceScreen(
    state: TranscriptUiState,
    onSelectProfileModel: (AiModel) -> Unit,
    onConfigurationChanged: (LocalAiConfiguration) -> Unit,
    onRefreshHardware: () -> Unit,
    onStartBenchmark: () -> Unit,
    onCancelBenchmark: () -> Unit,
    onResetConfiguration: () -> Unit,
    onCopyConfiguration: (AiModel) -> Unit,
    onExportConfiguration: () -> Unit,
    onJsonChanged: (String) -> Unit,
    onImportConfiguration: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = state.aiPerformanceConfiguration
    val context = LocalContext.current
    val uiPreferences = remember(context.applicationContext) {
        AiPerformanceUiPreferences(context.applicationContext)
    }
    val processorCount = (state.aiHardwareSnapshot?.processorCount
        ?: Runtime.getRuntime().availableProcessors()).coerceIn(1, 64)
    val gpuSettingsEnabled = configuration.backend == LocalAiBackend.VULKAN ||
        configuration.backend == LocalAiBackend.HYBRID
    val modelLayerCount = state.performanceModelLayerCount
    val gpuLayerSelectionEnabled = gpuSettingsEnabled && modelLayerCount > 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LiveStatusLine(state, state.aiPerformanceMessage)
        ProfileAndHardwareSection(
            state = state,
            onSelectProfileModel = onSelectProfileModel,
            onRefreshHardware = onRefreshHardware
        )

        ExpandableSettingsCard("context", "Kontext, Threads und Laden", uiPreferences, onReset = {
            val d = LocalAiConfiguration()
            onConfigurationChanged(
                configuration.copy(
                    contextSize = d.contextSize,
                    generationThreads = d.generationThreads,
                    promptThreads = d.promptThreads,
                    batchSize = d.batchSize,
                    microBatchSize = d.microBatchSize,
                    maximumOutputTokens = d.maximumOutputTokens,
                    flashAttention = d.flashAttention,
                    loadMode = d.loadMode
                )
            )
        }) {
            NumberChoiceSetting(
                title = "Kontextgröße",
                value = configuration.contextSize,
                description = "1.024–32.768 Tokens; Standard 4.096",
                regularOptions = SettingsOptionCatalogs.aiContextSize
            ) { selected ->
                val batch = configuration.batchSize.coerceIn(32, selected)
                val microBatch = configuration.microBatchSize.coerceIn(16, batch)
                val output = configuration.maximumOutputTokens.coerceIn(32, selected / 2)
                onConfigurationChanged(
                    configuration.copy(
                        contextSize = selected,
                        batchSize = batch,
                        microBatchSize = microBatch,
                        maximumOutputTokens = output
                    )
                )
            }
            NumberChoiceSetting(
                title = "Threads für Textausgabe",
                value = configuration.generationThreads,
                description = "1 bis erkannte CPU-Kerne",
                regularOptions = SettingsOptionCatalogs.processorThreads(processorCount)
            ) { onConfigurationChanged(configuration.copy(generationThreads = it)) }
            NumberChoiceSetting(
                title = "Threads für Texteingabe",
                value = configuration.promptThreads,
                description = "1 bis erkannte CPU-Kerne",
                regularOptions = SettingsOptionCatalogs.processorThreads(processorCount)
            ) { onConfigurationChanged(configuration.copy(promptThreads = it)) }
            NumberChoiceSetting(
                title = "Prompt-Batchgröße",
                value = configuration.batchSize,
                description = "32 bis aktuelle Kontextgröße",
                regularOptions = SettingsOptionCatalogs.aiBatchSize(configuration.contextSize)
            ) { selected ->
                onConfigurationChanged(
                    configuration.copy(
                        batchSize = selected,
                        microBatchSize = configuration.microBatchSize.coerceIn(16, selected)
                    )
                )
            }
            NumberChoiceSetting(
                title = "Physische Micro-Batchgröße",
                value = configuration.microBatchSize,
                description = "16 bis aktuelle Prompt-Batchgröße",
                regularOptions = SettingsOptionCatalogs.aiMicroBatchSize(configuration.batchSize)
            ) { onConfigurationChanged(configuration.copy(microBatchSize = it)) }
            NumberChoiceSetting(
                title = "Maximale Ausgabetokens",
                value = configuration.maximumOutputTokens,
                description = "32 bis halbe Kontextgröße",
                regularOptions = SettingsOptionCatalogs.aiMaximumOutputTokens(configuration.contextSize)
            ) { onConfigurationChanged(configuration.copy(maximumOutputTokens = it)) }
            ChoiceSetting(
                title = "Flash Attention",
                selected = configuration.flashAttention,
                options = listOf(
                    LocalAiFlashAttention.AUTO to "Automatisch",
                    LocalAiFlashAttention.ENABLED to "Ein",
                    LocalAiFlashAttention.DISABLED to "Aus"
                )
            ) { onConfigurationChanged(configuration.copy(flashAttention = it)) }
            ChoiceSetting(
                title = "Modell-Lademethode",
                selected = configuration.loadMode,
                options = listOf(
                    LocalAiLoadMode.AUTO to "Automatisch",
                    LocalAiLoadMode.MMAP to "Memory Mapping",
                    LocalAiLoadMode.READ to "Direkt in RAM lesen",
                    LocalAiLoadMode.MLOCK to "MLock",
                    LocalAiLoadMode.MMAP_MLOCK to "Mapping + MLock"
                )
            ) { onConfigurationChanged(configuration.copy(loadMode = it)) }
        }

        ExpandableSettingsCard("cpu", "CPU und KleidiAI", uiPreferences, onReset = {
            val d = LocalAiConfiguration()
            onConfigurationChanged(
                configuration.copy(
                    cpuBackend = d.cpuBackend,
                    cpuCoreMask = d.cpuCoreMask,
                    strictCpuPlacement = d.strictCpuPlacement,
                    threadPriority = d.threadPriority,
                    threadPollingPercent = d.threadPollingPercent,
                    kleidiSmeUnits = d.kleidiSmeUnits,
                    kleidiChunkMultiplier = d.kleidiChunkMultiplier
                )
            )
        }) {
            ChoiceSetting(
                title = "CPU-Beschleunigung",
                selected = configuration.cpuBackend,
                options = listOf(
                    LocalAiCpuBackend.AUTO to "Automatisch",
                    LocalAiCpuBackend.STANDARD to "Standard-CPU",
                    LocalAiCpuBackend.KLEIDIAI to "KleidiAI"
                )
            ) { onConfigurationChanged(configuration.copy(cpuBackend = it)) }
            CpuCoreMaskSetting(
                value = configuration.cpuCoreMask,
                processorCount = processorCount,
                maximumFrequenciesKhz = state.aiHardwareSnapshot?.coreMaximumFrequenciesKhz.orEmpty()
            ) { onConfigurationChanged(configuration.copy(cpuCoreMask = it)) }
            BooleanSetting(
                "Strikte CPU-Kernbindung",
                "Threadpool bleibt auf der angegebenen Kernauswahl.",
                configuration.strictCpuPlacement
            ) { onConfigurationChanged(configuration.copy(strictCpuPlacement = it)) }
            ChoiceSetting(
                title = "Thread-Priorität",
                selected = configuration.threadPriority,
                options = listOf(
                    LocalAiThreadPriority.LOW to "Niedrig",
                    LocalAiThreadPriority.NORMAL to "Normal",
                    LocalAiThreadPriority.MEDIUM to "Mittel",
                    LocalAiThreadPriority.HIGH to "Hoch"
                )
            ) { onConfigurationChanged(configuration.copy(threadPriority = it)) }
            NumberSetting("Thread-Polling", configuration.threadPollingPercent, "0–100 Prozent; Standard 50") {
                onConfigurationChanged(configuration.copy(threadPollingPercent = it))
            }
            NumberSetting("KleidiAI SME-Einheiten", configuration.kleidiSmeUnits, "−1 = automatisch, 0 = aus, danach 1–64") {
                onConfigurationChanged(configuration.copy(kleidiSmeUnits = it))
            }
            NumberSetting("KleidiAI Chunk-Multiplikator", configuration.kleidiChunkMultiplier, "0 = automatisch, danach 1–64") {
                onConfigurationChanged(configuration.copy(kleidiChunkMultiplier = it))
            }
            state.aiHardwareSnapshot?.let { hardware ->
                Text(
                    "KleidiAI eingebaut: ${yesNo(hardware.kleidiAiCompiled)} · " +
                        "auf diesem Gerät nutzbar: ${yesNo(hardware.kleidiAiUsable)} · " +
                        "Dot Product: ${yesNo(hardware.dotProduct)} · INT8: ${yesNo(hardware.int8Matrix)} · " +
                        "SVE: ${yesNo(hardware.sve)} · SME: ${yesNo(hardware.sme)} · SME2: ${yesNo(hardware.sme2)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Geladene CPU-Variante: ${hardware.cpuVariant} · " +
                        "KleidiAI-Puffer: ${yesNo(hardware.kleidiAiBufferAvailable)} · " +
                        "Modell ${state.performanceProfileModel.modelLabel}: " +
                        if (state.performanceProfileModel.kleidiAiCompatible) {
                            "KleidiAI-kompatibel"
                        } else {
                            "nicht KleidiAI-kompatibel (benötigt Q4_0 oder Q8_0)"
                        },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        ExpandableSettingsCard("vulkan", "Vulkan und GPU", uiPreferences, onReset = {
            val d = LocalAiConfiguration()
            onConfigurationChanged(
                configuration.copy(
                    backend = d.backend,
                    gpuDeviceIndex = d.gpuDeviceIndex,
                    gpuLayers = d.gpuLayers,
                    gpuLayerPercent = d.gpuLayerPercent,
                    offloadKqv = d.offloadKqv,
                    offloadOperations = d.offloadOperations,
                    automaticCpuFallback = d.automaticCpuFallback
                )
            )
        }) {
            ChoiceSetting(
                title = "Rechenbackend",
                selected = configuration.backend,
                options = listOf(
                    LocalAiBackend.AUTO to "Automatisch (CPU/KleidiAI)",
                    LocalAiBackend.CPU to "Nur CPU",
                    LocalAiBackend.VULKAN to "Vulkan – vollständig",
                    LocalAiBackend.HYBRID to "CPU/Vulkan gemischt"
                )
            ) { selectedBackend ->
                val updated = when {
                    selectedBackend == LocalAiBackend.VULKAN && modelLayerCount > 0 ->
                        configuration.copy(backend = selectedBackend, gpuLayers = -1, gpuLayerPercent = 100)
                    selectedBackend == LocalAiBackend.HYBRID && modelLayerCount > 0 -> {
                        val layers = when {
                            configuration.gpuLayers in 1..modelLayerCount -> configuration.gpuLayers
                            else -> modelLayerCount
                        }
                        configuration.copy(
                            backend = selectedBackend,
                            gpuLayers = layers,
                            gpuLayerPercent = SettingsOptionCatalogs.gpuPercentForLayers(
                                selectedBackend,
                                layers,
                                modelLayerCount
                            )
                        )
                    }
                    else -> configuration.copy(backend = selectedBackend)
                }
                onConfigurationChanged(updated)
            }
            val vulkanDevices = state.aiHardwareSnapshot?.vulkanDevices.orEmpty()
            ChoiceSetting(
                title = "Vulkan-Gerät",
                selected = configuration.gpuDeviceIndex,
                options = if (vulkanDevices.isEmpty()) {
                    listOf(0 to "Kein Vulkan-Gerät erkannt")
                } else {
                    vulkanDevices.mapIndexed { index, device -> index to device.description }
                },
                enabled = gpuSettingsEnabled
            ) { onConfigurationChanged(configuration.copy(gpuDeviceIndex = it)) }
            NumberChoiceSetting(
                title = "GPU-Schichten",
                value = configuration.gpuLayers,
                description = if (modelLayerCount > 0) {
                    "Exakte Auswahl bis $modelLayerCount Modellschichten; bei Vulkan zusätzlich vollständig"
                } else {
                    "Modellschichtzahl muss zuerst zuverlässig erkannt werden"
                },
                regularOptions = SettingsOptionCatalogs.aiGpuLayers(configuration.backend, modelLayerCount),
                enabled = gpuLayerSelectionEnabled
            ) { layers ->
                onConfigurationChanged(
                    configuration.copy(
                        gpuLayers = layers,
                        gpuLayerPercent = SettingsOptionCatalogs.gpuPercentForLayers(
                            configuration.backend,
                            layers,
                            modelLayerCount
                        )
                    )
                )
            }
            NumberChoiceSetting(
                title = "GPU-Anteil",
                value = configuration.gpuLayerPercent,
                description = if (modelLayerCount > 0) {
                    "Bedienhilfe in 5-%-Schritten · Modell hat $modelLayerCount Schichten; exakte Schichtzahl bleibt maßgeblich"
                } else {
                    "Modell muss für die Umrechnung installiert und analysierbar sein"
                },
                regularOptions = SettingsOptionCatalogs.aiGpuLayerPercent,
                enabled = gpuLayerSelectionEnabled
            ) { percent ->
                SettingsOptionCatalogs.gpuLayersForPercent(
                    configuration.backend,
                    percent,
                    modelLayerCount
                )?.let { layers ->
                    onConfigurationChanged(
                        configuration.copy(
                            gpuLayerPercent = percent,
                            gpuLayers = layers
                        )
                    )
                }
            }
            BooleanSetting(
                "KQV/KV-Cache auslagern",
                "Legt K/Q/V-Operationen und den KV-Cache auf das GPU-Backend.",
                configuration.offloadKqv,
                enabled = gpuSettingsEnabled
            ) { onConfigurationChanged(configuration.copy(offloadKqv = it)) }
            BooleanSetting(
                "Rechenoperationen auslagern",
                "Erlaubt llama.cpp geeignete Host-Operationen auf Vulkan auszuführen.",
                configuration.offloadOperations,
                enabled = gpuSettingsEnabled
            ) { onConfigurationChanged(configuration.copy(offloadOperations = it)) }
            BooleanSetting(
                "Automatischer CPU-Rückfall",
                "Lädt bei fehlendem oder nicht nutzbarem Vulkan-Gerät kontrolliert per CPU.",
                configuration.automaticCpuFallback
            ) { onConfigurationChanged(configuration.copy(automaticCpuFallback = it)) }
            Text(
                "Vulkan eingebaut: ${yesNo(state.aiHardwareSnapshot?.vulkanCompiled == true)} · " +
                    "auf diesem Gerät verfügbar: ${yesNo(state.aiHardwareSnapshot?.vulkanAvailable == true)} · " +
                    "erkannte GPU-Geräte: ${vulkanDevices.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        ExpandableSettingsCard("stability", "Arbeitsspeicher, Wärme und Stabilität", uiPreferences, onReset = {
            val d = LocalAiConfiguration()
            onConfigurationChanged(
                configuration.copy(
                    minimumFreeMemoryMb = d.minimumFreeMemoryMb,
                    maximumMemoryPercent = d.maximumMemoryPercent,
                    maximumVulkanMemoryPercent = d.maximumVulkanMemoryPercent,
                    thermalWarningStatus = d.thermalWarningStatus,
                    thermalThrottleStatus = d.thermalThrottleStatus,
                    thermalStopStatus = d.thermalStopStatus,
                    throttledThreads = d.throttledThreads,
                    gpuLayersReducedPerStep = d.gpuLayersReducedPerStep,
                    coolingPauseSeconds = d.coolingPauseSeconds
                )
            )
        }) {
            NumberSetting("Freie RAM-Reserve", configuration.minimumFreeMemoryMb, "128–8.192 MB; Standard 512 MB") {
                onConfigurationChanged(configuration.copy(minimumFreeMemoryMb = it))
            }
            NumberSetting("Maximaler RAM-Anteil", configuration.maximumMemoryPercent, "40–95 Prozent; 1-%-Schritte") {
                onConfigurationChanged(configuration.copy(maximumMemoryPercent = it))
            }
            NumberSetting("Maximaler Vulkan-Speicheranteil", configuration.maximumVulkanMemoryPercent, "25–95 Prozent; 1-%-Schritte") {
                onConfigurationChanged(configuration.copy(maximumVulkanMemoryPercent = it))
            }
            ThermalChoice(
                title = "Warnschwelle",
                selected = configuration.thermalWarningStatus,
                minimum = 0,
                maximum = configuration.thermalThrottleStatus
            ) { onConfigurationChanged(configuration.copy(thermalWarningStatus = it)) }
            ThermalChoice(
                title = "Leistungsreduzierung",
                selected = configuration.thermalThrottleStatus,
                minimum = configuration.thermalWarningStatus,
                maximum = configuration.thermalStopStatus
            ) { onConfigurationChanged(configuration.copy(thermalThrottleStatus = it)) }
            ThermalChoice(
                title = "Berechnung beenden",
                selected = configuration.thermalStopStatus,
                minimum = configuration.thermalThrottleStatus,
                maximum = 6
            ) { onConfigurationChanged(configuration.copy(thermalStopStatus = it)) }
            NumberChoiceSetting(
                title = "Threads bei Wärmereduzierung",
                value = configuration.throttledThreads,
                description = "1 bis erkannte CPU-Kerne",
                regularOptions = SettingsOptionCatalogs.processorThreads(processorCount)
            ) { onConfigurationChanged(configuration.copy(throttledThreads = it)) }
            NumberSetting("GPU-Schichten je Reduktionsschritt", configuration.gpuLayersReducedPerStep, "1–128; sinnvolle Staffelung für Stabilitätstests") {
                onConfigurationChanged(configuration.copy(gpuLayersReducedPerStep = it))
            }
            NumberSetting("Abkühlpause", configuration.coolingPauseSeconds, "0–300 Sekunden; Standard 15") {
                onConfigurationChanged(configuration.copy(coolingPauseSeconds = it))
            }
        }

        ExpandableSettingsCard("benchmark", "Leistungstest", uiPreferences, onReset = {
            val d = LocalAiConfiguration()
            onConfigurationChanged(
                configuration.copy(
                    benchmarkWarmupRuns = d.benchmarkWarmupRuns,
                    benchmarkMeasuredRuns = d.benchmarkMeasuredRuns,
                    benchmarkPromptCharacters = d.benchmarkPromptCharacters,
                    benchmarkOutputTokens = d.benchmarkOutputTokens,
                    benchmarkPauseSeconds = d.benchmarkPauseSeconds,
                    benchmarkMinimumBatteryPercent = d.benchmarkMinimumBatteryPercent,
                    benchmarkRequiresCharging = d.benchmarkRequiresCharging,
                    benchmarkMaximumThermalStatus = d.benchmarkMaximumThermalStatus
                )
            )
        }) {
            NumberSetting("Aufwärmdurchläufe", configuration.benchmarkWarmupRuns, "0–5") {
                onConfigurationChanged(configuration.copy(benchmarkWarmupRuns = it))
            }
            NumberSetting("Messdurchläufe", configuration.benchmarkMeasuredRuns, "1–10") {
                onConfigurationChanged(configuration.copy(benchmarkMeasuredRuns = it))
            }
            NumberSetting("Testprompt-Länge", configuration.benchmarkPromptCharacters, "128–8.192 Zeichen; 128er-Schritte") {
                onConfigurationChanged(configuration.copy(benchmarkPromptCharacters = it))
            }
            NumberSetting("Ausgabetokens pro Test", configuration.benchmarkOutputTokens, "32–512; 32er-Schritte") {
                onConfigurationChanged(configuration.copy(benchmarkOutputTokens = it))
            }
            NumberSetting("Pause zwischen Läufen", configuration.benchmarkPauseSeconds, "0–120 Sekunden") {
                onConfigurationChanged(configuration.copy(benchmarkPauseSeconds = it))
            }
            NumberSetting("Mindestakkustand", configuration.benchmarkMinimumBatteryPercent, "0–100 Prozent; 5-%-Schritte") {
                onConfigurationChanged(configuration.copy(benchmarkMinimumBatteryPercent = it))
            }
            BooleanSetting(
                "Nur am Ladegerät testen",
                "Der Benchmark startet nur bei aktivem Ladezustand.",
                configuration.benchmarkRequiresCharging
            ) { onConfigurationChanged(configuration.copy(benchmarkRequiresCharging = it)) }
            ThermalChoice("Maximaler Wärmestatus für Tests", configuration.benchmarkMaximumThermalStatus) {
                onConfigurationChanged(configuration.copy(benchmarkMaximumThermalStatus = it))
            }
            if (state.isAiBenchmarkRunning) {
                LinearProgressIndicator(
                    progress = state.aiBenchmarkProgress,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = onCancelBenchmark, modifier = Modifier.fillMaxWidth()) {
                    Text("Leistungstest abbrechen")
                }
            } else {
                Button(
                    onClick = onStartBenchmark,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("KI-Leistungstest starten") }
            }
            state.aiBenchmarkResult?.let { result ->
                BenchmarkResultCard(result)
            }
        }

        ExpandableSettingsCard("profiles", "Profile sowie JSON-Import und -Export", uiPreferences, onReset = onResetConfiguration) {
            Text("Profil auf ein anderes KI-Modell übertragen", style = MaterialTheme.typography.titleSmall)
            AiModel.entries.filter { it != state.performanceProfileModel }.forEach { target ->
                OutlinedButton(
                    onClick = { onCopyConfiguration(target) },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Nach ${target.modelLabel} kopieren") }
            }
            Button(
                onClick = onExportConfiguration,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Konfiguration als JSON anzeigen") }
            OutlinedTextField(
                value = state.aiPerformanceJson,
                onValueChange = onJsonChanged,
                label = { Text("Profil-JSON") },
                minLines = 4,
                maxLines = 12,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            val clipboard = LocalClipboardManager.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(state.aiPerformanceJson)) },
                    enabled = state.aiPerformanceJson.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Kopieren") }
                Button(
                    onClick = onImportConfiguration,
                    enabled = !state.isBusy && state.aiPerformanceJson.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Importieren") }
            }
        }
    }
}

@Composable
private fun ProfileAndHardwareSection(
    state: TranscriptUiState,
    onSelectProfileModel: (AiModel) -> Unit,
    onRefreshHardware: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Modellprofil und erkannte Hardware", style = MaterialTheme.typography.titleLarge)
            ChoiceSetting(
                title = "Einstellungen bearbeiten für",
                selected = state.performanceProfileModel,
                options = AiModel.entries.map { it to "${it.qualityLabel} · ${it.modelLabel}" }
            ) { onSelectProfileModel(it) }
            state.aiHardwareSnapshot?.let { HardwareSummary(it) }
            OutlinedButton(onClick = onRefreshHardware, modifier = Modifier.fillMaxWidth()) {
                Text("Hardwarestatus aktualisieren")
            }
        }
    }
}

@Composable
private fun HardwareSummary(hardware: AiHardwareSnapshot) {
    hardware.nativeRuntimeError?.let { error ->
        Text(
            "Native KI-Diagnosefehler: $error",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text(
        "CPU: ${hardware.processorCount} Kerne · ABI ${hardware.supportedAbis} · " +
            "Native Laufzeit: ${if (hardware.nativeRuntimeLoaded) "geladen" else "nicht geladen"}",
        style = MaterialTheme.typography.bodySmall
    )
    val frequencies = hardware.coreMaximumFrequenciesKhz.filter { it > 0L }
    if (frequencies.isNotEmpty()) {
        Text(
            "Maximale Kerntakte: ${frequencies.joinToString { "${it / 1_000} MHz" }}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text(
        "RAM frei: ${formatBytes(hardware.availableMemoryBytes)} von ${formatBytes(hardware.totalMemoryBytes)} · " +
            "App-PSS: ${formatBytes(hardware.appPssBytes)}",
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Akku: ${if (hardware.batteryPercent >= 0) "${hardware.batteryPercent} %" else "unbekannt"} · " +
            "Laden: ${yesNo(hardware.charging)} · Wärme: ${thermalStatusLabel(hardware.thermalStatus)}",
        style = MaterialTheme.typography.bodySmall
    )
    hardware.devices.forEach { device ->
        Text(
            "${device.type}: ${device.description}" +
                if (device.totalBytes > 0L) " · ${formatBytes(device.freeBytes)} frei" else "",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun ExpandableSettingsCard(
    storageKey: String,
    title: String,
    preferences: AiPerformanceUiPreferences,
    onReset: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember(storageKey) { mutableStateOf(preferences.isExpanded(storageKey)) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                onClick = {
                    expanded = !expanded
                    preferences.setExpanded(storageKey, expanded)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (expanded) "▾  $title" else "▸  $title",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (expanded) {
                content()
                onReset?.let { reset ->
                    OutlinedButton(onClick = reset, modifier = Modifier.fillMaxWidth()) {
                        Text("Auf Standard zurücksetzen")
                    }
                }
            }
        }
    }
}

@Composable
internal fun NumberSetting(
    title: String,
    value: Int,
    description: String,
    enabled: Boolean = true,
    onValueChanged: (Int) -> Unit
) {
    val options = defaultNumberOptions(title)
    NumberChoiceSetting(
        title = title,
        value = value,
        description = description,
        regularOptions = if (options.isEmpty()) listOf(value) else options,
        enabled = enabled,
        onValueChanged = onValueChanged
    )
}

@Composable
internal fun TextSetting(
    title: String,
    value: String,
    placeholder: String,
    onValueChanged: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChanged,
        label = { Text(title) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun BooleanSetting(
    title: String,
    description: String,
    value: Boolean,
    enabled: Boolean = true,
    onValueChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value, onCheckedChange = onValueChanged, enabled = enabled)
    }
}

@Composable
internal fun <T> ChoiceSetting(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    enabled: Boolean = true,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(label)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optionValue, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(if (optionValue == selected) "✓  $optionLabel" else optionLabel) },
                        onClick = {
                            expanded = false
                            onSelected(optionValue)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThermalChoice(
    title: String,
    selected: Int,
    minimum: Int = 0,
    maximum: Int = 6,
    onSelected: (Int) -> Unit
) {
    val safeMinimum = minimum.coerceIn(0, 6)
    val safeMaximum = maximum.coerceIn(safeMinimum, 6)
    ChoiceSetting(
        title = title,
        selected = selected,
        options = (safeMinimum..safeMaximum).map { it to thermalStatusLabel(it) },
        onSelected = onSelected
    )
}

@Composable
private fun BenchmarkResultCard(result: AiBenchmarkResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Benchmark-Ergebnis", style = MaterialTheme.typography.titleMedium)
            Text("Backend: ${result.runs.lastOrNull()?.runtimeReport?.activeBackend ?: "–"}")
            Text("CPU-Pfad: ${result.runs.lastOrNull()?.runtimeReport?.activeCpuBackend ?: "–"}")
            result.runs.lastOrNull()?.runtimeReport?.let { report ->
                Text("Angefordert: ${report.requestedBackend}")
                if (report.fallbackUsed) Text("Rückfall: Ja · aktiv ${report.activeBackend}")
            }
            Text("Modellladen: Ø ${result.averageLoadMs} ms")
            Text("Erstes Token: Ø ${result.averageFirstTokenMs} ms")
            Text("Prompt: Ø ${decimal(result.averagePromptTokensPerSecond)} Tokens/s")
            Text("Ausgabe: Ø ${decimal(result.averageOutputTokensPerSecond)} Tokens/s")
            Text("Gesamtdauer: Ø ${result.averageTotalMs} ms")
            Text("Maximales App-PSS: ${formatBytes(result.maximumPssBytes)}")
            result.runs.forEach { run ->
                Text(
                    "Lauf ${run.runNumber}: ${decimal(run.outputTokensPerSecond)} Tokens/s · " +
                        thermalStatusLabel(run.thermalStatus),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun yesNo(value: Boolean): String = if (value) "Ja" else "Nein"

private fun decimal(value: Double): String = String.format(Locale.GERMANY, "%.2f", value)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val gib = bytes / 1_073_741_824.0
    return if (gib >= 1.0) {
        String.format(Locale.GERMANY, "%.2f GB", gib)
    } else {
        String.format(Locale.GERMANY, "%.0f MB", bytes / 1_048_576.0)
    }
}
