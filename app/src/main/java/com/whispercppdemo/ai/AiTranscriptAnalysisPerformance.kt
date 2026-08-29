package de.matthiasennen.transcript.ai

internal data class AiTranscriptAnalysisPerformanceSnapshot(
    val modelLoadMs: Long,
    val totalInferenceMs: Long,
    val totalDurationMs: Long,
    val generationCount: Int,
    val sourceChunkCount: Int,
    val configuration: LocalAiConfiguration,
    val runtimeReport: LocalAiRuntimeReport?,
    val lastGenerationMetrics: LocalAiGenerationMetrics?
) {
    val unaccountedMs: Long
        get() = (totalDurationMs - modelLoadMs - totalInferenceMs).coerceAtLeast(0L)
}

internal fun aiTranscriptAnalysisPerformanceLines(
    snapshot: AiTranscriptAnalysisPerformanceSnapshot
): List<String> {
    val lines = mutableListOf<String>()
    lines += "Gesamt ${formatAiDuration(snapshot.totalDurationMs)} · " +
        "Modell ${formatAiDuration(snapshot.modelLoadMs)} · " +
        "Inferenz ${formatAiDuration(snapshot.totalInferenceMs)} · " +
        "sonstige Zeit ${formatAiDuration(snapshot.unaccountedMs)}"

    snapshot.lastGenerationMetrics?.let { metrics ->
        lines += "Letzter KI-Lauf: Prompt ${formatAiDuration(metrics.promptProcessingMs)} · " +
            "1. Token ${formatAiDuration(metrics.timeToFirstTokenMs)} · " +
            "Ausgabe ${formatAiDuration(metrics.answerGenerationMs)} · " +
            "${metrics.promptTokens}→${metrics.generatedTokens} Tokens"
        lines += "Details letzter Lauf: Template ${formatAiDuration(metrics.chatTemplateMs)} · " +
            "Tokenisierung ${formatAiDuration(metrics.tokenizationMs)} · " +
            "Kontext ${formatAiDuration(metrics.contextCreationMs)} · " +
            "Prompt-Decode ${formatAiDuration(metrics.promptDecodeMs)}"
    }

    val configuration = snapshot.configuration.normalized()
    snapshot.runtimeReport?.let { runtime ->
        lines += "Runtime: ${runtime.activeBackend} · CPU ${runtime.activeCpuBackend} · " +
            "Laden ${runtime.loadMode} · GPU-Schichten ${runtime.requestedGpuLayers}/${runtime.modelLayers}"
    }
    lines += "Profil: Kontext ${configuration.contextSize} · " +
        "Batch ${configuration.batchSize}/${configuration.microBatchSize} · " +
        "Threads ${configuration.promptThreads}/${configuration.generationThreads} · " +
        "${snapshot.sourceChunkCount} Quellteil(e) · ${snapshot.generationCount} KI-Lauf/Läufe"

    if (snapshot.generationCount > 1 && snapshot.lastGenerationMetrics != null) {
        lines += "Hinweis: Die Detailzeiten beziehen sich auf den letzten KI-Lauf; die Inferenzzeit oben umfasst alle Läufe."
    }
    return lines
}

internal fun formatAiDuration(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    if (safe < 1_000L) return "$safe ms"
    val tenths = (safe + 50L) / 100L
    return "${tenths / 10L},${tenths % 10L} s"
}
