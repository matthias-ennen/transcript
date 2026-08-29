package de.matthiasennen.transcript.ai

internal data class AiTranscriptAnalysisGenerationPerformance(
    val label: String,
    val phaseDurationMs: Long,
    val metrics: LocalAiGenerationMetrics
) {
    val phaseOverheadMs: Long
        get() = (phaseDurationMs - metrics.totalInferenceMs).coerceAtLeast(0L)
}

internal data class AiTranscriptAnalysisPerformanceSnapshot(
    val modelLoadMs: Long,
    val modelAlreadyLoaded: Boolean,
    val totalInferenceMs: Long,
    val totalDurationMs: Long,
    val preAnalysisMs: Long,
    val analysisWallMs: Long,
    val postAnalysisMs: Long,
    val generationCount: Int,
    val sourceChunkCount: Int,
    val configuration: LocalAiConfiguration,
    val runtimeReport: LocalAiRuntimeReport?,
    val generationPerformance: List<AiTranscriptAnalysisGenerationPerformance>,
    val startingAppPssBytes: Long,
    val peakAppPssBytes: Long,
    val endingAppPssBytes: Long,
    val maximumThermalStatus: Int,
    val resourceSampleCount: Int
) {
    val unaccountedMs: Long
        get() = (totalDurationMs - modelLoadMs - totalInferenceMs).coerceAtLeast(0L)
}

internal fun aiTranscriptAnalysisPerformanceLines(
    snapshot: AiTranscriptAnalysisPerformanceSnapshot
): List<String> {
    val lines = mutableListOf<String>()
    lines += "Service gesamt ${formatAiDuration(snapshot.totalDurationMs)} · " +
        "Modell ${formatAiDuration(snapshot.modelLoadMs)}" +
        (if (snapshot.modelAlreadyLoaded) " (bereits geladen)" else "") +
        " · Inferenz ${formatAiDuration(snapshot.totalInferenceMs)} · " +
        "Rest ${formatAiDuration(snapshot.unaccountedMs)}"
    lines += "Ablauf: Vorbereitung ${formatAiDuration(snapshot.preAnalysisMs)} · " +
        "Analyse-Wanduhr ${formatAiDuration(snapshot.analysisWallMs)} · " +
        "Nachbereitung ${formatAiDuration(snapshot.postAnalysisMs)}"

    if (snapshot.resourceSampleCount > 0) {
        lines += "RAM (App-PSS): Start ${formatAiMemory(snapshot.startingAppPssBytes)} · " +
            "Max. Messwert ${formatAiMemory(snapshot.peakAppPssBytes)} · " +
            "Ende ${formatAiMemory(snapshot.endingAppPssBytes)} · " +
            "${snapshot.resourceSampleCount} Messpunkte"
    }
    if (snapshot.maximumThermalStatus >= 0) {
        lines += "Wärme: höchster gemessener Status " +
            "${thermalStatusLabel(snapshot.maximumThermalStatus)} (${snapshot.maximumThermalStatus})"
    }

    val configuration = snapshot.configuration.normalized()
    snapshot.runtimeReport?.let { runtime ->
        lines += "Runtime: ${runtime.activeBackend} · CPU ${runtime.activeCpuBackend} · " +
            "Laden ${runtime.loadMode} · GPU-Schichten ${runtime.requestedGpuLayers}/${runtime.modelLayers} · " +
            "Fallback ${if (runtime.fallbackUsed) "ja" else "nein"}"
    }
    lines += "Profil: Kontext ${configuration.contextSize} · " +
        "Batch ${configuration.batchSize}/${configuration.microBatchSize} · " +
        "Threads Prompt/Ausgabe ${configuration.promptThreads}/${configuration.generationThreads} · " +
        "${snapshot.sourceChunkCount} Quellteil(e) · ${snapshot.generationCount} KI-Lauf/Läufe"

    snapshot.generationPerformance.forEachIndexed { index, phase ->
        val metrics = phase.metrics
        lines += "Lauf ${index + 1}/${snapshot.generationCount}: ${phase.label} · " +
            "Wanduhr ${formatAiDuration(phase.phaseDurationMs)} · " +
            "Inferenz ${formatAiDuration(metrics.totalInferenceMs)} · " +
            "Overhead ${formatAiDuration(phase.phaseOverheadMs)}"
        lines += "Prompt ${formatAiDuration(metrics.promptProcessingMs)} · " +
            "1. Token ${formatAiDuration(metrics.timeToFirstTokenMs)} · " +
            "Ausgabe ${formatAiDuration(metrics.answerGenerationMs)} · " +
            "${metrics.promptTokens}→${metrics.generatedTokens} Tokens"
        lines += "Prefill ${formatAiDuration(metrics.promptDecodeMs)} " +
            "(${formatAiTokenRate(metrics.promptTokens, metrics.promptDecodeMs)}) · " +
            "Generation ${formatAiTokenRate(metrics.generatedTokens, metrics.answerGenerationMs)}"
        lines += "Details: Template ${formatAiDuration(metrics.chatTemplateMs)} · " +
            "Tokenisierung ${formatAiDuration(metrics.tokenizationMs)} · " +
            "Kontext ${formatAiDuration(metrics.contextCreationMs)} · " +
            "Prompt-Decode ${formatAiDuration(metrics.promptDecodeMs)} · Ende ${metrics.finishReason}"
    }
    if (snapshot.generationPerformance.size != snapshot.generationCount) {
        lines += "Hinweis: ${snapshot.generationPerformance.size}/${snapshot.generationCount} Einzelmessungen konnten diesem Lauf zugeordnet werden."
    }
    return lines
}

internal fun formatAiDuration(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    if (safe < 1_000L) return "$safe ms"
    val tenths = (safe + 50L) / 100L
    return "${tenths / 10L},${tenths % 10L} s"
}

internal fun formatAiMemory(bytes: Long): String {
    if (bytes <= 0L) return "–"
    val mib = (bytes + 524_288L) / 1_048_576L
    return "$mib MB"
}

internal fun formatAiTokenRate(tokens: Int, milliseconds: Long): String {
    if (tokens <= 0 || milliseconds <= 0L) return "–"
    val tenthsPerSecond = (tokens.toLong() * 10_000L + milliseconds / 2L) / milliseconds
    return "${tenthsPerSecond / 10L},${tenthsPerSecond % 10L} Tok/s"
}
