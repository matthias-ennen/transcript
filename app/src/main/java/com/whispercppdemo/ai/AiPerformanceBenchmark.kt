package de.matthiasennen.transcript.ai

data class AiBenchmarkRun(
    val runNumber: Int,
    val modelLoadMs: Long,
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptProcessingMs: Long,
    val timeToFirstTokenMs: Long,
    val answerGenerationMs: Long,
    val totalMs: Long,
    val appPssBytes: Long,
    val thermalStatus: Int,
    val runtimeReport: LocalAiRuntimeReport
) {
    val promptTokensPerSecond: Double
        get() = if (promptProcessingMs > 0L) promptTokens * 1_000.0 / promptProcessingMs else 0.0

    val outputTokensPerSecond: Double
        get() = if (answerGenerationMs > 0L) generatedTokens * 1_000.0 / answerGenerationMs else 0.0
}

data class AiBenchmarkResult(
    val model: AiModel,
    val configuration: LocalAiConfiguration,
    val runs: List<AiBenchmarkRun>
) {
    val averageLoadMs: Long get() = runs.map(AiBenchmarkRun::modelLoadMs).average().toLong()
    val averageFirstTokenMs: Long get() = runs.map(AiBenchmarkRun::timeToFirstTokenMs).average().toLong()
    val averageTotalMs: Long get() = runs.map(AiBenchmarkRun::totalMs).average().toLong()
    val averagePromptTokensPerSecond: Double
        get() = runs.map(AiBenchmarkRun::promptTokensPerSecond).average()
    val averageOutputTokensPerSecond: Double
        get() = runs.map(AiBenchmarkRun::outputTokensPerSecond).average()
    val maximumPssBytes: Long get() = runs.maxOfOrNull(AiBenchmarkRun::appPssBytes) ?: 0L
}

