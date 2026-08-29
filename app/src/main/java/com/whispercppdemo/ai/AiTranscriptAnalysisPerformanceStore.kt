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
        modelAlreadyLoaded: Boolean,
        preAnalysisMs: Long,
        analysisWallMs: Long,
        postAnalysisMs: Long,
        generationPerformance: List<AiTranscriptAnalysisGenerationPerformance>,
        startingAppPssBytes: Long,
        peakAppPssBytes: Long,
        endingAppPssBytes: Long,
        maximumThermalStatus: Int,
        resourceSampleCount: Int
    ) {
        latest = Entry(
            key = key(result),
            snapshot = AiTranscriptAnalysisPerformanceSnapshot(
                modelLoadMs = result.modelLoadMs,
                modelAlreadyLoaded = modelAlreadyLoaded,
                totalInferenceMs = result.totalInferenceMs,
                totalDurationMs = result.totalDurationMs,
                preAnalysisMs = preAnalysisMs.coerceAtLeast(0L),
                analysisWallMs = analysisWallMs.coerceAtLeast(0L),
                postAnalysisMs = postAnalysisMs.coerceAtLeast(0L),
                generationCount = result.generationCount,
                sourceChunkCount = result.sourceChunkCount,
                configuration = configuration.normalized(),
                runtimeReport = runtimeReport,
                generationPerformance = generationPerformance.toList(),
                startingAppPssBytes = startingAppPssBytes.coerceAtLeast(0L),
                peakAppPssBytes = peakAppPssBytes.coerceAtLeast(0L),
                endingAppPssBytes = endingAppPssBytes.coerceAtLeast(0L),
                maximumThermalStatus = maximumThermalStatus,
                resourceSampleCount = resourceSampleCount.coerceAtLeast(0)
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
