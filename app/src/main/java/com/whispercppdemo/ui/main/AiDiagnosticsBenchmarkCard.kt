package de.matthiasennen.transcript.ui.main

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.BuildConfig
import de.matthiasennen.transcript.ai.AiEngineSessionManager
import de.matthiasennen.transcript.ai.AiHardwareProbe
import de.matthiasennen.transcript.ai.AiHardwareSnapshot
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiPerformancePreferences
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.ai.LocalAiRuntimeReport
import de.matthiasennen.transcript.ai.thermalStatusLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object AiDiagnosticsBenchmarkSession {
    var isRunning by mutableStateOf(false)
        private set

    fun start() {
        isRunning = true
    }

    fun stop() {
        isRunning = false
    }
}

private data class AiDiagnosticsBenchmarkProgress(
    val completedRuns: Int,
    val totalRuns: Int,
    val currentVariant: String,
    val currentRound: Int,
    val repetitions: Int,
    val thermalStatus: Int,
    val lastTotalMs: Long? = null
) {
    val fraction: Float
        get() = if (totalRuns <= 0) 0f else completedRuns.toFloat() / totalRuns
}

private data class AiDiagnosticsBenchmarkRunResult(
    val variantId: String,
    val variantLabel: String,
    val model: AiModel,
    val repetition: Int,
    val configuration: LocalAiConfiguration,
    val modelLoadMs: Long,
    val modelAlreadyLoaded: Boolean,
    val cpuFallbackUsed: Boolean,
    val promptTokens: Int,
    val generatedTokens: Int,
    val chatTemplateMs: Long,
    val tokenizationMs: Long,
    val contextCreationMs: Long,
    val promptDecodeMs: Long,
    val promptProcessingMs: Long,
    val timeToFirstTokenMs: Long,
    val answerGenerationMs: Long,
    val nativeInferenceMs: Long,
    val totalMs: Long,
    val finishReason: String,
    val thinkingDisabled: Boolean,
    val thermalBefore: Int,
    val thermalAfter: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val appPssBytes: Long,
    val runtimeReport: LocalAiRuntimeReport?,
    val responseText: String,
    val error: String? = null
) {
    val successful: Boolean
        get() = error == null

    val outsideNativeMs: Long
        get() = (totalMs - nativeInferenceMs).coerceAtLeast(0L)
}

private data class AiDiagnosticsBenchmarkResult(
    val plan: AiDiagnosticsBenchmarkPlan,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val hardware: AiHardwareSnapshot,
    val installedModels: List<AiModel>,
    val runs: List<AiDiagnosticsBenchmarkRunResult>,
    val abortedReason: String? = null
)

@Composable
internal fun AiDiagnosticsBenchmarkCard(state: TranscriptUiState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val inventory = remember(context) {
        ModelInventory(context.filesDir).also(ModelInventory::ensureDirectories)
    }
    val performancePreferences = remember(context) { AiPerformancePreferences(context) }
    val installedModels = remember(state.aiModelInstallations) {
        state.aiModelInstallations.filter { it.isInstalled }.map { it.model }
    }
    var selectedModel by remember(installedModels) {
        mutableStateOf(
            state.selectedAiModel.takeIf { it in installedModels }
                ?: installedModels.firstOrNull()
                ?: state.selectedAiModel
        )
    }
    var selectedPackage by remember {
        mutableStateOf(AiDiagnosticsBenchmarkPackage.CPU_BASELINE)
    }
    var progress by remember { mutableStateOf<AiDiagnosticsBenchmarkProgress?>(null) }
    var report by remember { mutableStateOf("") }
    var benchmarkMessage by remember { mutableStateOf<String?>(null) }
    var benchmarkJob by remember { mutableStateOf<Job?>(null) }
    var copyMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(installedModels, state.selectedAiModel) {
        if (selectedModel !in installedModels) {
            selectedModel = state.selectedAiModel.takeIf { it in installedModels }
                ?: installedModels.firstOrNull()
                ?: state.selectedAiModel
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            benchmarkJob?.cancel()
            AiDiagnosticsBenchmarkSession.stop()
            AiEngineSessionManager.release()
        }
    }

    val transcriptText = remember(state.rawWhisperSegments, state.segments) {
        aiDiagnosticsTranscriptExcerpt(state)
    }
    val processors = state.aiHardwareSnapshot?.processorCount
        ?: Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val frequencies = state.aiHardwareSnapshot?.coreMaximumFrequenciesKhz.orEmpty()
    val plan = if (selectedModel in installedModels) {
        runCatching {
            buildAiDiagnosticsBenchmarkPlan(
                benchmarkPackage = selectedPackage,
                selectedModel = selectedModel,
                installedModels = installedModels,
                processorCount = processors,
                maximumFrequenciesKhz = frequencies,
                transcriptText = transcriptText,
                loadConfiguration = performancePreferences::load
            )
        }.getOrNull()
    } else {
        null
    }
    val missingTranscript = selectedPackage.requiresTranscript && transcriptText.isBlank()
    val running = AiDiagnosticsBenchmarkSession.isRunning
    val canStart = !running && !state.isBusy && !state.isAiModelPreloading &&
        plan != null && plan.variants.isNotEmpty() && !missingTranscript

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Automatischer KI-Benchmark", style = MaterialTheme.typography.titleSmall)
            Text(
                "Testmodell und Testpaket werden nur für diesen Benchmark verwendet. Die normalen KI-Einstellungen und das im Alltag ausgewählte Modell werden nicht umgestellt.",
                style = MaterialTheme.typography.bodySmall
            )

            if (installedModels.isEmpty()) {
                Text(
                    "Für den Benchmark ist noch kein lokales KI-Modell vollständig installiert.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                ChoiceSetting(
                    title = "Testmodell",
                    selected = selectedModel,
                    options = installedModels.map { it to it.modelLabel },
                    enabled = !running,
                    onSelected = {
                        selectedModel = it
                        report = ""
                        benchmarkMessage = null
                        copyMessage = null
                    }
                )
                ChoiceSetting(
                    title = "Testpaket",
                    selected = selectedPackage,
                    options = AiDiagnosticsBenchmarkPackage.entries.map { it to it.label },
                    enabled = !running,
                    onSelected = {
                        selectedPackage = it
                        report = ""
                        benchmarkMessage = null
                        copyMessage = null
                    }
                )
                Text(selectedPackage.description, style = MaterialTheme.typography.bodySmall)
                plan?.let {
                    Text(
                        "Geplant: ${it.variants.size} Variante(n) · ${it.repetitionsPerVariant} Messung(en) je Variante · ${it.totalMeasuredRuns} Lauf/Läufe insgesamt. Varianten werden rundenweise abgewechselt.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (selectedPackage.requiresTranscript) {
                    if (missingTranscript) {
                        Text(
                            "Für dieses Paket zuerst ein Transkript erzeugen oder laden. Verwendet wird automatisch der Abschnitt 0–2 Minuten.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(
                            "Praxistext bereit: ${transcriptText.length} Zeichen aus 0–2 Minuten.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (running) {
                progress?.let { current ->
                    LinearProgressIndicator(
                        progress = current.fraction.coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${current.currentVariant} · Runde ${current.currentRound}/${current.repetitions} · ${current.completedRuns}/${current.totalRuns}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        buildString {
                            append("Thermal: ${thermalStatusLabel(current.thermalStatus)}")
                            current.lastTotalMs?.let { append(" · letzter Lauf: ${formatSeconds(it)} s") }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = {
                        benchmarkJob?.cancel()
                        benchmarkMessage = "Benchmark wird abgebrochen …"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Benchmark abbrechen")
                }
            } else {
                Button(
                    onClick = {
                        val activePlan = plan ?: return@Button
                        report = ""
                        copyMessage = null
                        benchmarkMessage = "Benchmark wird vorbereitet …"
                        progress = AiDiagnosticsBenchmarkProgress(
                            completedRuns = 0,
                            totalRuns = activePlan.totalMeasuredRuns,
                            currentVariant = "Vorbereitung",
                            currentRound = 0,
                            repetitions = activePlan.repetitionsPerVariant,
                            thermalStatus = state.aiDiagnosticsThermalStatus ?: -1
                        )
                        AiDiagnosticsBenchmarkSession.start()
                        benchmarkJob = scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    runAiDiagnosticsBenchmark(
                                        context = context.applicationContext,
                                        inventory = inventory,
                                        plan = activePlan,
                                        installedModels = installedModels,
                                        onProgress = { update ->
                                            withContext(Dispatchers.Main) {
                                                progress = update
                                            }
                                        }
                                    )
                                }
                                report = formatAiDiagnosticsBenchmarkReport(result)
                                benchmarkMessage = if (result.abortedReason == null) {
                                    "Benchmark abgeschlossen. Der vollständige Bericht kann jetzt kopiert werden."
                                } else {
                                    "Benchmark vorzeitig beendet. Der Teilbericht enthält den Abbruchgrund und alle bis dahin gemessenen Werte."
                                }
                            } catch (_: CancellationException) {
                                benchmarkMessage = "Benchmark abgebrochen."
                            } catch (failure: Throwable) {
                                benchmarkMessage = failure.localizedMessage
                                    ?: "Benchmark konnte nicht abgeschlossen werden."
                            } finally {
                                AiEngineSessionManager.release()
                                AiDiagnosticsBenchmarkSession.stop()
                                benchmarkJob = null
                            }
                        }
                    },
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Benchmark starten")
                }
            }

            benchmarkMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            if (report.isNotBlank()) {
                Text("Ergebnisbericht", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Diese Textkachel enthält die vollständigen Einstellungen, Einzelmessungen und Zusammenfassungen. Mit einem Klick wird der gesamte Bericht kopiert.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = report,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 10,
                    maxLines = 18,
                    label = { Text("Kopierbarer Benchmarkbericht") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(report))
                        copyMessage = "Vollständiger Benchmarkbericht wurde kopiert."
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ergebnis kopieren")
                }
                copyMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private suspend fun runAiDiagnosticsBenchmark(
    context: Context,
    inventory: ModelInventory,
    plan: AiDiagnosticsBenchmarkPlan,
    installedModels: List<AiModel>,
    onProgress: suspend (AiDiagnosticsBenchmarkProgress) -> Unit
): AiDiagnosticsBenchmarkResult {
    val startedAtEpochMs = System.currentTimeMillis()
    val benchmarkStarted = SystemClock.elapsedRealtime()
    val hardwareAtStart = AiHardwareProbe.read(context)
    val runs = mutableListOf<AiDiagnosticsBenchmarkRunResult>()
    var completedRuns = 0
    var lastTotalMs: Long? = null
    var abortedReason: String? = null

    outer@ for (roundIndex in 0 until plan.repetitionsPerVariant) {
        for (variant in plan.variants) {
            currentCoroutineContext().ensureActive()
            val configuration = variant.configuration.normalized(hardwareAtStart.processorCount)
            val before = AiHardwareProbe.read(context)
            benchmarkGuardError(before, configuration)?.let { guard ->
                abortedReason = guard
                break@outer
            }

            onProgress(
                AiDiagnosticsBenchmarkProgress(
                    completedRuns = completedRuns,
                    totalRuns = plan.totalMeasuredRuns,
                    currentVariant = variant.label,
                    currentRound = roundIndex + 1,
                    repetitions = plan.repetitionsPerVariant,
                    thermalStatus = before.thermalStatus,
                    lastTotalMs = lastTotalMs
                )
            )

            val modelFile = inventory.aiFile(variant.model)
            val runResult = if (!modelFile.isFile || modelFile.length() < variant.model.minimumBytes) {
                failedRun(
                    variant = variant,
                    repetition = roundIndex + 1,
                    hardware = before,
                    message = "${variant.model.modelLabel} ist nicht vollständig installiert."
                )
            } else {
                runCatching {
                    AiHardwareProbe.checkMemory(context, modelFile, configuration)
                    AiEngineSessionManager.release()
                    val preload = AiEngineSessionManager.withModel(
                        variant.model,
                        modelFile,
                        configuration
                    ) { _, _ -> Unit }
                    currentCoroutineContext().ensureActive()
                    val timedStart = SystemClock.elapsedRealtime()
                    val session = AiEngineSessionManager.withModel(
                        variant.model,
                        modelFile,
                        configuration
                    ) { engine, _ ->
                        engine.resetTestConversation()
                        val generation = engine.generateTest(variant.prompt, variant.outputTokens)
                        Triple(generation, engine.runtimeReport(), AiHardwareProbe.read(context))
                    }
                    val totalMs = (SystemClock.elapsedRealtime() - timedStart).coerceAtLeast(0L)
                    val (generation, runtimeReport, after) = session.value
                    val metrics = generation.metrics
                    AiDiagnosticsBenchmarkRunResult(
                        variantId = variant.id,
                        variantLabel = variant.label,
                        model = variant.model,
                        repetition = roundIndex + 1,
                        configuration = configuration,
                        modelLoadMs = preload.info.modelLoadMs,
                        modelAlreadyLoaded = preload.info.modelAlreadyLoaded,
                        cpuFallbackUsed = preload.info.cpuFallbackUsed ||
                            session.info.cpuFallbackUsed || runtimeReport.fallbackUsed,
                        promptTokens = metrics.promptTokens,
                        generatedTokens = metrics.generatedTokens,
                        chatTemplateMs = metrics.chatTemplateMs,
                        tokenizationMs = metrics.tokenizationMs,
                        contextCreationMs = metrics.contextCreationMs,
                        promptDecodeMs = metrics.promptDecodeMs,
                        promptProcessingMs = metrics.promptProcessingMs,
                        timeToFirstTokenMs = metrics.timeToFirstTokenMs,
                        answerGenerationMs = metrics.answerGenerationMs,
                        nativeInferenceMs = metrics.totalInferenceMs,
                        totalMs = totalMs,
                        finishReason = metrics.finishReason,
                        thinkingDisabled = metrics.thinkingDisabled,
                        thermalBefore = before.thermalStatus,
                        thermalAfter = after.thermalStatus,
                        batteryPercent = before.batteryPercent,
                        charging = before.charging,
                        appPssBytes = after.appPssBytes,
                        runtimeReport = runtimeReport,
                        responseText = generation.text
                    )
                }.getOrElse { failure ->
                    failedRun(
                        variant = variant,
                        repetition = roundIndex + 1,
                        hardware = before,
                        message = failure.localizedMessage ?: failure::class.java.simpleName
                    )
                }
            }

            runs += runResult
            lastTotalMs = runResult.totalMs.takeIf { runResult.successful }
            completedRuns += 1
            AiEngineSessionManager.release()
            onProgress(
                AiDiagnosticsBenchmarkProgress(
                    completedRuns = completedRuns,
                    totalRuns = plan.totalMeasuredRuns,
                    currentVariant = variant.label,
                    currentRound = roundIndex + 1,
                    repetitions = plan.repetitionsPerVariant,
                    thermalStatus = runResult.thermalAfter,
                    lastTotalMs = lastTotalMs
                )
            )
            if (completedRuns < plan.totalMeasuredRuns && plan.benchmarkPackage.pauseSeconds > 0) {
                delay(plan.benchmarkPackage.pauseSeconds * 1_000L)
            }
        }
    }

    AiEngineSessionManager.release()
    return AiDiagnosticsBenchmarkResult(
        plan = plan,
        startedAtEpochMs = startedAtEpochMs,
        durationMs = (SystemClock.elapsedRealtime() - benchmarkStarted).coerceAtLeast(0L),
        hardware = hardwareAtStart,
        installedModels = installedModels,
        runs = runs,
        abortedReason = abortedReason
    )
}

private fun benchmarkGuardError(
    hardware: AiHardwareSnapshot,
    configuration: LocalAiConfiguration
): String? = when {
    hardware.batteryPercent >= 0 &&
        hardware.batteryPercent < configuration.benchmarkMinimumBatteryPercent ->
        "Benchmark gestoppt: Akkustand ${hardware.batteryPercent} % liegt unter ${configuration.benchmarkMinimumBatteryPercent} %."
    configuration.benchmarkRequiresCharging && !hardware.charging ->
        "Benchmark gestoppt: Das aktuelle Leistungsprofil verlangt ein angeschlossenes Ladegerät."
    hardware.thermalStatus >= 0 &&
        hardware.thermalStatus > configuration.benchmarkMaximumThermalStatus ->
        "Benchmark gestoppt: Thermalstatus ${thermalStatusLabel(hardware.thermalStatus)} überschreitet die Benchmarkgrenze ${thermalStatusLabel(configuration.benchmarkMaximumThermalStatus)}."
    else -> null
}

private fun failedRun(
    variant: AiDiagnosticsBenchmarkVariant,
    repetition: Int,
    hardware: AiHardwareSnapshot,
    message: String
): AiDiagnosticsBenchmarkRunResult = AiDiagnosticsBenchmarkRunResult(
    variantId = variant.id,
    variantLabel = variant.label,
    model = variant.model,
    repetition = repetition,
    configuration = variant.configuration,
    modelLoadMs = 0L,
    modelAlreadyLoaded = false,
    cpuFallbackUsed = false,
    promptTokens = 0,
    generatedTokens = 0,
    chatTemplateMs = 0L,
    tokenizationMs = 0L,
    contextCreationMs = 0L,
    promptDecodeMs = 0L,
    promptProcessingMs = 0L,
    timeToFirstTokenMs = 0L,
    answerGenerationMs = 0L,
    nativeInferenceMs = 0L,
    totalMs = 0L,
    finishReason = "error",
    thinkingDisabled = false,
    thermalBefore = hardware.thermalStatus,
    thermalAfter = hardware.thermalStatus,
    batteryPercent = hardware.batteryPercent,
    charging = hardware.charging,
    appPssBytes = hardware.appPssBytes,
    runtimeReport = null,
    responseText = "",
    error = message
)

private fun formatAiDiagnosticsBenchmarkReport(result: AiDiagnosticsBenchmarkResult): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)
        .format(Date(result.startedAtEpochMs))
    val successfulRuns = result.runs.filter { it.successful }
    val summaries = result.plan.variants.mapNotNull { variant ->
        val runs = successfulRuns.filter { it.variantId == variant.id }
        runs.takeIf { it.isNotEmpty() }?.let { variant to it }
    }
    val fastest = summaries.minByOrNull { (_, runs) ->
        medianMilliseconds(runs.map { it.totalMs })
    }

    return buildString {
        appendLine("TRANSCRIPT – AUTOMATISCHER KI-BENCHMARK")
        appendLine("==========================================")
        appendLine("Build: ${BuildConfig.VERSION_NAME} · VersionCode ${BuildConfig.VERSION_CODE}")
        appendLine("Gerät: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Zeitpunkt: $date")
        appendLine("Testpaket: ${result.plan.benchmarkPackage.label}")
        appendLine("Ausgewähltes Testmodell: ${result.plan.selectedModel.modelLabel}")
        appendLine("Geplant: ${result.plan.totalMeasuredRuns} Läufe · ausgeführt: ${result.runs.size}")
        appendLine("Paketdauer inklusive Pausen/Laden: ${formatSeconds(result.durationMs)} s")
        appendLine("Reihenfolge: rundenweise/interleaved")
        appendLine()
        appendLine("HARDWARE")
        appendLine("CPU-Kerne: ${result.hardware.processorCount}")
        appendLine("ABI: ${result.hardware.supportedAbis}")
        appendLine("CPU-Variante: ${result.hardware.cpuVariant}")
        appendLine("KleidiAI nutzbar: ${yesNo(result.hardware.kleidiAiUsable)}")
        appendLine("Vulkan verfügbar: ${yesNo(result.hardware.vulkanAvailable)}")
        appendLine("Vulkan-Gerät: ${result.hardware.vulkanDevices.joinToString { it.description }.ifBlank { "–" }}")
        appendLine("RAM frei/gesamt: ${formatMiB(result.hardware.availableMemoryBytes)} / ${formatMiB(result.hardware.totalMemoryBytes)}")
        appendLine("Akku: ${result.hardware.batteryPercent}% · Laden: ${yesNo(result.hardware.charging)}")
        appendLine("Thermal zu Beginn: ${thermalStatusLabel(result.hardware.thermalStatus)}")
        appendLine("Installierte Testmodelle: ${result.installedModels.joinToString { it.modelLabel }}")
        appendLine()

        result.plan.variants.forEach { variant ->
            val runs = result.runs.filter { it.variantId == variant.id }
            val successful = runs.filter { it.successful }
            appendLine("VARIANTE: ${variant.label}")
            appendLine("Modell: ${variant.model.modelLabel}")
            appendConfiguration(variant.configuration)
            if (successful.isEmpty()) {
                appendLine("Ergebnis: keine erfolgreiche Messung")
            } else {
                appendLine("Erfolgreiche Messungen: ${successful.size}/${runs.size}")
                appendLine("TTFT Median: ${medianMilliseconds(successful.map { it.timeToFirstTokenMs })} ms")
                appendLine("Prompt/Prefill Median: ${medianMilliseconds(successful.map { it.promptProcessingMs })} ms")
                appendLine("Generierung Median: ${medianMilliseconds(successful.map { it.answerGenerationMs })} ms")
                appendLine("Native Inferenz Median: ${medianMilliseconds(successful.map { it.nativeInferenceMs })} ms")
                appendLine("E2E Median: ${medianMilliseconds(successful.map { it.totalMs })} ms")
                appendLine("E2E Mittelwert: ${averageMilliseconds(successful.map { it.totalMs })} ms")
                appendLine("E2E Streuung (Max-Min/Median): ${formatDecimal(spreadPercent(successful.map { it.totalMs }))} %")
                appendLine("Max. App-PSS: ${formatMiB(successful.maxOf { it.appPssBytes })}")
                appendLine("Fallbacks: ${successful.count { it.cpuFallbackUsed }}")
            }
            runs.forEach { run ->
                if (run.error != null) {
                    appendLine("Lauf ${run.repetition}: FEHLER · ${run.error}")
                } else {
                    appendLine(
                        "Lauf ${run.repetition}: E2E ${run.totalMs} ms · TTFT ${run.timeToFirstTokenMs} ms · " +
                            "Prefill ${run.promptProcessingMs} ms · Generierung ${run.answerGenerationMs} ms · " +
                            "Native ${run.nativeInferenceMs} ms · außerhalb Native ${run.outsideNativeMs} ms"
                    )
                    appendLine(
                        "  Tokens ${run.promptTokens}/${run.generatedTokens} · Modellladen ${run.modelLoadMs} ms · " +
                            "Thermal ${thermalStatusLabel(run.thermalBefore)} → ${thermalStatusLabel(run.thermalAfter)} · " +
                            "Fallback ${yesNo(run.cpuFallbackUsed)}"
                    )
                    appendLine(
                        "  Backend angefordert/aktiv: ${run.runtimeReport?.requestedBackend ?: "–"} / " +
                            "${run.runtimeReport?.activeBackend ?: "–"} · CPU ${run.runtimeReport?.activeCpuBackend ?: "–"}"
                    )
                    appendLine(
                        "  Detail: Template ${run.chatTemplateMs} ms · Tokenisierung ${run.tokenizationMs} ms · " +
                            "Context ${run.contextCreationMs} ms · Prompt-Decode ${run.promptDecodeMs} ms · " +
                            "Ende ${run.finishReason} · Thinking deaktiviert ${yesNo(run.thinkingDisabled)}"
                    )
                }
            }
            if (
                result.plan.benchmarkPackage == AiDiagnosticsBenchmarkPackage.PRACTICE_TRANSCRIPT ||
                result.plan.benchmarkPackage == AiDiagnosticsBenchmarkPackage.COMBINED_POSTPROCESSING
            ) {
                successful.firstOrNull()?.responseText?.takeIf { it.isNotBlank() }?.let { response ->
                    appendLine("KI-ERGEBNIS ZUR QUALITÄTSPRÜFUNG:")
                    appendLine(response.take(8_000))
                }
            }
            appendLine()
        }

        appendLine("GESAMTBEWERTUNG")
        if (fastest == null) {
            appendLine("Keine erfolgreiche Laufzeitmessung vorhanden.")
        } else if (summaries.size == 1) {
            appendLine("Gemessene Variante: ${fastest.first.label} · E2E Median ${medianMilliseconds(fastest.second.map { it.totalMs })} ms")
        } else {
            appendLine(
                "Schnellste gemessene Variante nach E2E-Median: ${fastest.first.label} · " +
                    "${medianMilliseconds(fastest.second.map { it.totalMs })} ms"
            )
            appendLine("Hinweis: Bei Modell- und Praxistests bewertet diese Aussage nur die Geschwindigkeit, nicht die Textqualität.")
        }
        result.abortedReason?.let { appendLine("Abbruchgrund: $it") }
        val errors = result.runs.mapNotNull { it.error }
        appendLine("Fehler: ${errors.size}")
        errors.distinct().forEach { appendLine("- $it") }
    }
}

private fun StringBuilder.appendConfiguration(configuration: LocalAiConfiguration) {
    val normalized = configuration.normalized()
    appendLine(
        "Konfiguration: Kontext ${normalized.contextSize} · Threads ${normalized.generationThreads}/${normalized.promptThreads} · " +
            "Batch ${normalized.batchSize}/${normalized.microBatchSize} · Output ${normalized.maximumOutputTokens}"
    )
    appendLine(
        "Backend ${normalized.backend} · CPU ${normalized.cpuBackend} · Flash ${normalized.flashAttention} · " +
            "Laden ${normalized.loadMode} · GPU-Schichten ${normalized.gpuLayers} · GPU-Anteil ${normalized.gpuLayerPercent}%"
    )
    appendLine(
        "Offload KQV ${yesNo(normalized.offloadKqv)} · Ops ${yesNo(normalized.offloadOperations)} · " +
            "CPU-Rückfall ${yesNo(normalized.automaticCpuFallback)} · Kernmaske ${normalized.cpuCoreMask.ifBlank { "automatisch" }} · " +
            "strikt ${yesNo(normalized.strictCpuPlacement)} · Priorität ${normalized.threadPriority} · Polling ${normalized.threadPollingPercent}%"
    )
}

private fun aiDiagnosticsTranscriptExcerpt(state: TranscriptUiState): String {
    val source = state.rawWhisperSegments.takeIf { it.isNotEmpty() } ?: state.segments
    return source.asSequence()
        .filter { it.startMs < 120_000L }
        .map { it.text.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
}

private fun averageMilliseconds(values: List<Long>): Long =
    if (values.isEmpty()) 0L else values.sum() / values.size

private fun formatSeconds(milliseconds: Long): String =
    String.format(Locale.GERMANY, "%.3f", milliseconds / 1_000.0)

private fun formatDecimal(value: Double): String =
    String.format(Locale.GERMANY, "%.2f", value)

private fun formatMiB(bytes: Long): String =
    String.format(Locale.GERMANY, "%.0f MB", bytes / 1_048_576.0)

private fun yesNo(value: Boolean): String = if (value) "Ja" else "Nein"
