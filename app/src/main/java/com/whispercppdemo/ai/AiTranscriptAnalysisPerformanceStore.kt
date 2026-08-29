package de.matthiasennen.transcript.ai

/**
 * Keeps the exact performance metadata that belongs to the currently completed transcript
 * analysis. Analysis results themselves are process-local as well, so one latest snapshot is
 * sufficient and avoids reconstructing diagnostics later from mutable preferences/global state.
 */
internal object AiTranscriptAnalysisPerformanceStore {
    private data class Key(
        val model: AiModel,
        val action: AiTranscriptAnalysisAction,
        val sourceFingerprint: String,
        val totalDurationMs: Long
    )

    private data class Entry(
        val key: Key,
        val snapshot: AiTranscriptAnalysisPerformanceSnapshot
    )

    @Volatile
    private var latest: Entry? = null

    fun capture(
        result: AiTranscriptAnalysisResult,
        configuration: LocalAiConfiguration,
        runtimeReport: LocalAiRuntimeReport?,
        lastGenerationMetrics: LocalAiGenerationMetrics?
    ) {
        latest = Entry(
            key = key(result),
            snapshot = AiTranscriptAnalysisPerformanceSnapshot(
                modelLoadMs = result.modelLoadMs,
                totalInferenceMs = result.totalInferenceMs,
                totalDurationMs = result.totalDurationMs,
                generationCount = result.generationCount,
                sourceChunkCount = result.sourceChunkCount,
                configuration = configuration.normalized(),
                runtimeReport = runtimeReport,
                lastGenerationMetrics = lastGenerationMetrics
            )
        )
    }

    fun snapshotFor(result: AiTranscriptAnalysisResult): AiTranscriptAnalysisPerformanceSnapshot? =
        latest?.takeIf { it.key == key(result) }?.snapshot

    internal fun clearForTest() {
        latest = null
    }

    private fun key(result: AiTranscriptAnalysisResult) = Key(
        model = result.model,
        action = result.action,
        sourceFingerprint = result.sourceFingerprint,
        totalDurationMs = result.totalDurationMs
    )
}
