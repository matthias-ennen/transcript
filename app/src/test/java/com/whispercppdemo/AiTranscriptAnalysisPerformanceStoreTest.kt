package de.matthiasennen.transcript.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiTranscriptAnalysisPerformanceStoreTest {
    @Test
    fun `snapshot is tied to exact completed analysis result`() {
        AiTranscriptAnalysisPerformanceStore.clearForTest()
        val result = result(action = AiTranscriptAnalysisAction.SUMMARY, durationMs = 12_000L)
        val configuration = LocalAiConfiguration(
            contextSize = 4_096,
            generationThreads = 4,
            promptThreads = 6,
            batchSize = 256,
            microBatchSize = 128
        )
        val runtime = LocalAiRuntimeReport(
            requestedBackend = "CPU",
            activeBackend = "CPU",
            activeCpuBackend = "STANDARD",
            gpuDevice = "",
            modelLayers = 24,
            requestedGpuLayers = 0,
            fallbackUsed = false,
            loadMode = "MMAP"
        )
        val metrics = LocalAiGenerationMetrics(
            promptTokens = 320,
            generatedTokens = 96,
            chatTemplateMs = 1L,
            tokenizationMs = 2L,
            contextCreationMs = 300L,
            promptDecodeMs = 20_000L,
            promptProcessingMs = 20_303L,
            timeToFirstTokenMs = 20_305L,
            answerGenerationMs = 30_000L,
            totalInferenceMs = 50_305L,
            finishReason = "eog",
            thinkingDisabled = true
        )

        AiTranscriptAnalysisPerformanceStore.capture(result, configuration, runtime, metrics)

        val snapshot = AiTranscriptAnalysisPerformanceStore.snapshotFor(result)
        requireNotNull(snapshot)
        assertEquals(4, snapshot.configuration.generationThreads)
        assertEquals(6, snapshot.configuration.promptThreads)
        assertEquals(256, snapshot.configuration.batchSize)
        assertEquals("CPU", snapshot.runtimeReport?.activeBackend)
        assertEquals(320, snapshot.lastGenerationMetrics?.promptTokens)

        assertNull(
            AiTranscriptAnalysisPerformanceStore.snapshotFor(
                result(action = AiTranscriptAnalysisAction.TODOS, durationMs = 12_000L)
            )
        )
        AiTranscriptAnalysisPerformanceStore.clearForTest()
    }

    private fun result(
        action: AiTranscriptAnalysisAction,
        durationMs: Long
    ) = AiTranscriptAnalysisResult(
        action = action,
        model = AiModel.BALANCED_Q4,
        text = "Ergebnis",
        sourceFileName = "test.wav",
        sourceFingerprint = "fingerprint",
        sourceChunkCount = 1,
        generationCount = 1,
        modelLoadMs = 1_000L,
        totalInferenceMs = 8_000L,
        totalDurationMs = durationMs,
        cpuFallbackUsed = false
    )
}
