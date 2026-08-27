package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.LocalAiBackend
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.ai.LocalAiCpuBackend
import de.matthiasennen.transcript.ai.LocalAiFlashAttention
import de.matthiasennen.transcript.ai.LocalAiLoadMode
import de.matthiasennen.transcript.ai.LocalAiThreadPriority
import kotlin.math.roundToInt

internal const val AI_DIAGNOSTICS_STANDARD_PROMPT =
    "Fasse in fünf kurzen Stichpunkten zusammen, welche Faktoren die Laufzeit einer lokalen KI auf einem Smartphone beeinflussen. Antworte auf Deutsch."

internal enum class AiDiagnosticsBenchmarkPackage(
    val label: String,
    val description: String,
    val repetitionsPerVariant: Int,
    val pauseSeconds: Int,
    val requiresTranscript: Boolean = false
) {
    CPU_BASELINE(
        label = "CPU – Grundbenchmark",
        description = "Misst die kontrollierte Standard-CPU-Konfiguration mit dem festen Referenzprompt.",
        repetitionsPerVariant = 3,
        pauseSeconds = 5
    ),
    CPU_THREADS(
        label = "CPU – Threads",
        description = "Vergleicht sinnvolle Kombinationen für Textausgabe und Texteingabe interleaved gegeneinander.",
        repetitionsPerVariant = 3,
        pauseSeconds = 5
    ),
    CPU_BATCH(
        label = "CPU – Batch/Micro-Batch",
        description = "Vergleicht drei praxisnahe CPU-Batchgrößen bei ansonsten identischer Konfiguration.",
        repetitionsPerVariant = 3,
        pauseSeconds = 5
    ),
    CPU_SCHEDULING(
        label = "CPU – Scheduling",
        description = "Vergleicht Priorität, Polling und – falls Kerntakte bekannt sind – eine strikte Bindung an die schnellsten Kerne.",
        repetitionsPerVariant = 3,
        pauseSeconds = 5
    ),
    MODEL_COMPARISON(
        label = "Modellvergleich",
        description = "Vergleicht das gewählte Modell mit bis zu zwei installierten, für Tempo relevanten Q4_0-Kandidaten.",
        repetitionsPerVariant = 2,
        pauseSeconds = 8
    ),
    PRACTICE_TRANSCRIPT(
        label = "Praxisbenchmark 0–2 Minuten",
        description = "Bereinigt den realen Transkriptabschnitt von 0 bis 2 Minuten in einem lokalen KI-Aufruf.",
        repetitionsPerVariant = 1,
        pauseSeconds = 0,
        requiresTranscript = true
    ),
    COMBINED_POSTPROCESSING(
        label = "Kombinierte Nachbearbeitung",
        description = "Erledigt Bereinigung, Titel, Zusammenfassung, Aufgaben und Schlagwörter für 0–2 Minuten in einem einzigen Aufruf.",
        repetitionsPerVariant = 1,
        pauseSeconds = 0,
        requiresTranscript = true
    ),
    HYBRID_EXPERIMENTAL(
        label = "Vulkan/Hybrid – experimentell",
        description = "Vergleicht Standard-CPU mit dem abgesicherten Hybridpfad bei vier GPU-Schichten. Voll-Vulkan bleibt gesperrt.",
        repetitionsPerVariant = 2,
        pauseSeconds = 10
    )
}

internal data class AiDiagnosticsBenchmarkVariant(
    val id: String,
    val label: String,
    val model: AiModel,
    val configuration: LocalAiConfiguration,
    val prompt: String,
    val outputTokens: Int
)

internal data class AiDiagnosticsBenchmarkPlan(
    val benchmarkPackage: AiDiagnosticsBenchmarkPackage,
    val selectedModel: AiModel,
    val variants: List<AiDiagnosticsBenchmarkVariant>
) {
    val repetitionsPerVariant: Int
        get() = benchmarkPackage.repetitionsPerVariant

    val totalMeasuredRuns: Int
        get() = variants.size * repetitionsPerVariant
}

internal fun buildAiDiagnosticsBenchmarkPlan(
    benchmarkPackage: AiDiagnosticsBenchmarkPackage,
    selectedModel: AiModel,
    installedModels: List<AiModel>,
    processorCount: Int,
    maximumFrequenciesKhz: List<Long>,
    transcriptText: String,
    loadConfiguration: (AiModel) -> LocalAiConfiguration
): AiDiagnosticsBenchmarkPlan {
    require(selectedModel in installedModels) { "Das gewählte Testmodell ist nicht installiert." }
    if (benchmarkPackage.requiresTranscript) {
        require(transcriptText.isNotBlank()) {
            "Für dieses Testpaket muss zuerst ein Transkript mit Inhalt zwischen 0 und 2 Minuten vorliegen."
        }
    }

    fun cpuBaseline(model: AiModel): LocalAiConfiguration = controlledCpuBaseline(
        source = loadConfiguration(model),
        processorCount = processorCount
    )

    val variants = when (benchmarkPackage) {
        AiDiagnosticsBenchmarkPackage.CPU_BASELINE -> {
            val config = cpuBaseline(selectedModel)
            listOf(
                speedVariant(
                    id = "cpu-baseline",
                    label = "Standard-CPU ${config.generationThreads}/${config.promptThreads} · ${config.batchSize}/${config.microBatchSize}",
                    model = selectedModel,
                    configuration = config
                )
            )
        }

        AiDiagnosticsBenchmarkPackage.CPU_THREADS -> {
            val baseline = cpuBaseline(selectedModel)
            listOf(
                4 to 6,
                4 to 4,
                6 to 6
            ).map { (generation, prompt) ->
                baseline.copy(
                    generationThreads = generation.coerceAtMost(processorCount.coerceAtLeast(1)),
                    promptThreads = prompt.coerceAtMost(processorCount.coerceAtLeast(1))
                ).normalized(processorCount)
            }.distinctBy { it.generationThreads to it.promptThreads }
                .map { config ->
                    speedVariant(
                        id = "threads-${config.generationThreads}-${config.promptThreads}",
                        label = "Threads ${config.generationThreads} Ausgabe / ${config.promptThreads} Eingabe",
                        model = selectedModel,
                        configuration = config
                    )
                }
        }

        AiDiagnosticsBenchmarkPackage.CPU_BATCH -> {
            val baseline = cpuBaseline(selectedModel)
            listOf(
                1_024 to 512,
                512 to 256,
                256 to 128
            ).map { (batch, microBatch) ->
                baseline.copy(batchSize = batch, microBatchSize = microBatch)
                    .normalized(processorCount)
            }.distinctBy { it.batchSize to it.microBatchSize }
                .map { config ->
                    speedVariant(
                        id = "batch-${config.batchSize}-${config.microBatchSize}",
                        label = "Batch ${config.batchSize} / Micro-Batch ${config.microBatchSize}",
                        model = selectedModel,
                        configuration = config
                    )
                }
        }

        AiDiagnosticsBenchmarkPackage.CPU_SCHEDULING -> {
            val baseline = cpuBaseline(selectedModel)
            buildList {
                add(
                    speedVariant(
                        id = "scheduling-normal",
                        label = "Normal · Polling 50 %",
                        model = selectedModel,
                        configuration = baseline.copy(
                            cpuCoreMask = "",
                            strictCpuPlacement = false,
                            threadPriority = LocalAiThreadPriority.NORMAL,
                            threadPollingPercent = 50
                        ).normalized(processorCount)
                    )
                )
                add(
                    speedVariant(
                        id = "scheduling-high",
                        label = "Hohe Priorität · Polling 50 %",
                        model = selectedModel,
                        configuration = baseline.copy(
                            cpuCoreMask = "",
                            strictCpuPlacement = false,
                            threadPriority = LocalAiThreadPriority.HIGH,
                            threadPollingPercent = 50
                        ).normalized(processorCount)
                    )
                )
                add(
                    speedVariant(
                        id = "scheduling-no-polling",
                        label = "Normal · Polling 0 %",
                        model = selectedModel,
                        configuration = baseline.copy(
                            cpuCoreMask = "",
                            strictCpuPlacement = false,
                            threadPriority = LocalAiThreadPriority.NORMAL,
                            threadPollingPercent = 0
                        ).normalized(processorCount)
                    )
                )
                fastestCoreMask(
                    processorCount = processorCount,
                    maximumFrequenciesKhz = maximumFrequenciesKhz,
                    neededCores = maxOf(baseline.generationThreads, baseline.promptThreads)
                )?.let { mask ->
                    add(
                        speedVariant(
                            id = "scheduling-fast-cores",
                            label = "Schnellste Kerne · strikt · Polling 50 %",
                            model = selectedModel,
                            configuration = baseline.copy(
                                cpuCoreMask = mask,
                                strictCpuPlacement = true,
                                threadPriority = LocalAiThreadPriority.NORMAL,
                                threadPollingPercent = 50
                            ).normalized(processorCount)
                        )
                    )
                }
            }
        }

        AiDiagnosticsBenchmarkPackage.MODEL_COMPARISON -> {
            comparisonModels(selectedModel, installedModels).map { model ->
                speedVariant(
                    id = "model-${model.id}",
                    label = model.modelLabel,
                    model = model,
                    configuration = cpuBaseline(model)
                )
            }
        }

        AiDiagnosticsBenchmarkPackage.PRACTICE_TRANSCRIPT -> {
            val config = cpuBaseline(selectedModel).copy(maximumOutputTokens = 768)
                .normalized(processorCount)
            listOf(
                AiDiagnosticsBenchmarkVariant(
                    id = "practice-transcript",
                    label = "0–2 Minuten · Transkript bereinigen",
                    model = selectedModel,
                    configuration = config,
                    prompt = practiceTranscriptPrompt(transcriptText),
                    outputTokens = 768
                )
            )
        }

        AiDiagnosticsBenchmarkPackage.COMBINED_POSTPROCESSING -> {
            val config = cpuBaseline(selectedModel).copy(maximumOutputTokens = 896)
                .normalized(processorCount)
            listOf(
                AiDiagnosticsBenchmarkVariant(
                    id = "combined-postprocessing",
                    label = "0–2 Minuten · alle Nachbearbeitungen in einem Aufruf",
                    model = selectedModel,
                    configuration = config,
                    prompt = combinedPostProcessingPrompt(transcriptText),
                    outputTokens = 896
                )
            )
        }

        AiDiagnosticsBenchmarkPackage.HYBRID_EXPERIMENTAL -> {
            val cpu = cpuBaseline(selectedModel)
            val gpuPercent = if (
                selectedModel == AiModel.PRECISE || selectedModel == AiModel.PRECISE_Q4
            ) 13 else 17
            val hybrid = cpu.copy(
                backend = LocalAiBackend.HYBRID,
                gpuLayers = 4,
                gpuLayerPercent = gpuPercent,
                offloadKqv = false,
                offloadOperations = false,
                automaticCpuFallback = true
            ).normalized(processorCount)
            listOf(
                speedVariant(
                    id = "hybrid-reference-cpu",
                    label = "Referenz · Standard-CPU",
                    model = selectedModel,
                    configuration = cpu
                ),
                speedVariant(
                    id = "hybrid-4-layers",
                    label = "Hybrid · 4 GPU-Schichten · Sicherheitsprofil",
                    model = selectedModel,
                    configuration = hybrid
                )
            )
        }
    }

    return AiDiagnosticsBenchmarkPlan(
        benchmarkPackage = benchmarkPackage,
        selectedModel = selectedModel,
        variants = variants
    )
}

internal fun controlledCpuBaseline(
    source: LocalAiConfiguration,
    processorCount: Int
): LocalAiConfiguration {
    val processors = processorCount.coerceAtLeast(1)
    return source.copy(
        contextSize = 4_096,
        generationThreads = 4.coerceAtMost(processors),
        promptThreads = 6.coerceAtMost(processors),
        batchSize = 1_024,
        microBatchSize = 512,
        maximumOutputTokens = 128,
        flashAttention = LocalAiFlashAttention.AUTO,
        loadMode = LocalAiLoadMode.AUTO,
        backend = LocalAiBackend.CPU,
        cpuBackend = LocalAiCpuBackend.STANDARD,
        gpuDeviceIndex = 0,
        gpuLayers = 0,
        gpuLayerPercent = 0,
        offloadKqv = false,
        offloadOperations = false,
        automaticCpuFallback = true,
        cpuCoreMask = "",
        strictCpuPlacement = false,
        threadPriority = LocalAiThreadPriority.NORMAL,
        threadPollingPercent = 50
    ).normalized(processors)
}

private fun speedVariant(
    id: String,
    label: String,
    model: AiModel,
    configuration: LocalAiConfiguration
): AiDiagnosticsBenchmarkVariant = AiDiagnosticsBenchmarkVariant(
    id = id,
    label = label,
    model = model,
    configuration = configuration,
    prompt = AI_DIAGNOSTICS_STANDARD_PROMPT,
    outputTokens = 128
)

private fun comparisonModels(
    selectedModel: AiModel,
    installedModels: List<AiModel>
): List<AiModel> {
    val installed = installedModels.toSet()
    return buildList {
        add(selectedModel)
        listOf(AiModel.QUICK, AiModel.BALANCED_Q4, AiModel.PRECISE_Q4)
            .filter { it in installed }
            .forEach(::add)
    }.distinct().take(3)
}

private fun fastestCoreMask(
    processorCount: Int,
    maximumFrequenciesKhz: List<Long>,
    neededCores: Int
): String? {
    if (processorCount <= 1 || maximumFrequenciesKhz.size < processorCount) return null
    val indexed = maximumFrequenciesKhz.take(processorCount).withIndex()
        .filter { it.value > 0L }
    if (indexed.size < processorCount) return null
    val count = neededCores.coerceIn(1, processorCount)
    val selected = indexed.sortedWith(
        compareByDescending<IndexedValue<Long>> { it.value }.thenBy { it.index }
    ).take(count).map { it.index }.sorted()
    if (selected.size == processorCount) return null
    return selected.joinToString(",")
}

private fun practiceTranscriptPrompt(transcriptText: String): String = """
    Bereinige das folgende automatisch erzeugte deutsche Transkript sorgfältig.
    Korrigiere offensichtliche Erkennungsfehler, Zeichensetzung und Satzgrenzen, ohne neue Inhalte zu erfinden.
    Gib ausschließlich das vollständig bereinigte Transkript zurück.

    TRANSKRIPT 0–2 MINUTEN:
    $transcriptText
""".trimIndent()

private fun combinedPostProcessingPrompt(transcriptText: String): String = """
    Bearbeite das folgende deutsche Transkript in genau einem Durchgang.
    Erfinde keine Fakten. Gib die Antwort mit genau diesen fünf Überschriften zurück:
    BEREINIGTES TRANSKRIPT
    TITEL
    ZUSAMMENFASSUNG
    AUFGABEN
    SCHLAGWÖRTER

    Unter BEREINIGTES TRANSKRIPT: korrigiere Erkennungsfehler, Zeichensetzung und Satzgrenzen.
    Unter TITEL: ein kurzer sachlicher Titel.
    Unter ZUSAMMENFASSUNG: höchstens fünf kurze Stichpunkte.
    Unter AUFGABEN: nur tatsächlich erkennbare Aufgaben, sonst "Keine".
    Unter SCHLAGWÖRTER: höchstens acht passende Begriffe.

    TRANSKRIPT 0–2 MINUTEN:
    $transcriptText
""".trimIndent()

internal fun medianMilliseconds(values: List<Long>): Long {
    if (values.isEmpty()) return 0L
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        ((sorted[middle - 1] + sorted[middle]) / 2.0).roundToInt().toLong()
    }
}

internal fun spreadPercent(values: List<Long>): Double {
    if (values.size < 2) return 0.0
    val median = medianMilliseconds(values)
    if (median <= 0L) return 0.0
    return (values.maxOrNull()!! - values.minOrNull()!!) * 100.0 / median
}
