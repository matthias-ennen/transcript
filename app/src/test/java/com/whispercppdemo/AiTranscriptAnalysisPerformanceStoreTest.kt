package de.matthiasennen.transcript.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptAnalysisPerformanceStoreTest {
    @Test
    fun `snapshot is tied to exact completed analysis result and freezes full run diagnostics`() {
        AiTranscriptAnalysisPerformanceStore.clearForTest()
        val result = result(action = AiTranscriptAnalysisAction.SUMMARY, durationMs = 65_000L)
        val configuration = LocalAiConfiguration(
            contextSize = 4_096,
            generationThreads = 4,
            promptThreads = 6,
            batchSize = 256,
            microBatchSize = 128
        )
        val normalizedConfiguration = configuration.normalized()
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
        val phases = listOf(
            AiTranscriptAnalysisGenerationPerformance(
                label = "Das vollständige Transkript wird lokal ausgewertet.",
                phaseDurationMs = 53_000L,
                metrics = metrics
            )
        )

        AiTranscriptAnalysisPerformanceStore.capture(
            result = result,
            configuration = configuration,
            runtimeReport = runtime,
            modelAlreadyLoaded = true,
            preAnalysisMs = 3_000L,
            analysisWallMs = 53_000L,
            postAnalysisMs = 8_000L,
            generationPerformance = phases,
            startingAppPssBytes = 400L * 1_048_576L,
            peakAppPssBytes = 900L * 1_048_576L,
            endingAppPssBytes = 850L * 1_048_576L,
            maximumThermalStatus = 2,
            resourceSampleCount = 4
        )

        val snapshot = AiTranscriptAnalysisPerformanceStore.snapshotFor(result)
        requireNotNull(snapshot)
        assertEquals(normalizedConfiguration.generationThreads, snapshot.configuration.generationThreads)
        assertEquals(normalizedConfiguration.promptThreads, snapshot.configuration.promptThreads)
        assertEquals(normalizedConfiguration.batchSize, snapshot.configuration.batchSize)
        assertEquals("CPU", snapshot.runtimeReport?.activeBackend)
        assertEquals(true, snapshot.modelAlreadyLoaded)
        assertEquals(1, snapshot.generationPerformance.size)
        assertEquals(320, snapshot.generationPerformance.single().metrics.promptTokens)
        assertEquals(900L * 1_048_576L, snapshot.peakAppPssBytes)
        assertEquals(2, snapshot.maximumThermalStatus)

        val lines = aiTranscriptAnalysisPerformanceLines(snapshot)
        assertTrue(lines.any { it.contains("Max. Messwert 900 MB") })
        assertTrue(lines.any { it.contains("Lauf 1/1") })
        assertTrue(lines.any { it.contains("Prefill") && it.contains("Tok/s") })

        assertNull(
            AiTranscriptAnalysisPerformanceStore.snapshotFor(
                result(action = AiTranscriptAnalysisAction.TODOS, durationMs = 65_000L)
            )
        )
        AiTranscriptAnalysisPerformanceStore.clearForTest()
    }

    @Test
    fun `duration memory and token rate formatting stay deterministic`() {
        assertEquals("999 ms", formatAiDuration(999L))
        assertEquals("1,0 s", formatAiDuration(1_000L))
        assertEquals("512 MB", formatAiMemory(512L * 1_048_576L))
        assertEquals("10,0 Tok/s", formatAiTokenRate(100, 10_000L))
        assertEquals("–", formatAiTokenRate(0, 10_000L))
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
        totalInferenceMs = 50_305L,
        totalDurationMs = durationMs,
        cpuFallbackUsed = false
    )
}
