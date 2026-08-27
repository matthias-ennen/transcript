package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.LocalAiBackend
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.ai.LocalAiCpuBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDiagnosticsBenchmarkPlanTest {
    @Test
    fun controlledCpuBaselineUsesKnownReferenceProfile() {
        val configuration = controlledCpuBaseline(
            source = LocalAiConfiguration(
                backend = LocalAiBackend.HYBRID,
                gpuLayers = 4,
                batchSize = 32,
                microBatchSize = 32
            ),
            processorCount = 8
        )

        assertEquals(LocalAiBackend.CPU, configuration.backend)
        assertEquals(LocalAiCpuBackend.STANDARD, configuration.cpuBackend)
        assertEquals(4_096, configuration.contextSize)
        assertEquals(4, configuration.generationThreads)
        assertEquals(6, configuration.promptThreads)
        assertEquals(1_024, configuration.batchSize)
        assertEquals(512, configuration.microBatchSize)
        assertEquals(128, configuration.maximumOutputTokens)
    }

    @Test
    fun threadPackageBuildsThreeInterleavedCandidatesAndNineRuns() {
        val plan = buildAiDiagnosticsBenchmarkPlan(
            benchmarkPackage = AiDiagnosticsBenchmarkPackage.CPU_THREADS,
            selectedModel = AiModel.BALANCED_Q4,
            installedModels = listOf(AiModel.BALANCED_Q4),
            processorCount = 8,
            maximumFrequenciesKhz = emptyList(),
            transcriptText = "",
            loadConfiguration = { LocalAiConfiguration() }
        )

        assertEquals(3, plan.variants.size)
        assertEquals(3, plan.repetitionsPerVariant)
        assertEquals(9, plan.totalMeasuredRuns)
        assertEquals(
            setOf(4 to 6, 4 to 4, 6 to 6),
            plan.variants.map {
                it.configuration.generationThreads to it.configuration.promptThreads
            }.toSet()
        )
    }

    @Test
    fun experimentalHybridPackageKeepsAndroidSafetyCorridor() {
        val plan = buildAiDiagnosticsBenchmarkPlan(
            benchmarkPackage = AiDiagnosticsBenchmarkPackage.HYBRID_EXPERIMENTAL,
            selectedModel = AiModel.BALANCED_Q4,
            installedModels = listOf(AiModel.BALANCED_Q4),
            processorCount = 8,
            maximumFrequenciesKhz = emptyList(),
            transcriptText = "",
            loadConfiguration = { LocalAiConfiguration() }
        )
        val hybrid = plan.variants.single { it.configuration.backend == LocalAiBackend.HYBRID }
            .configuration

        assertEquals(4, hybrid.gpuLayers)
        assertEquals(LocalAiConfiguration.SAFE_ANDROID_GPU_BATCH_SIZE, hybrid.batchSize)
        assertEquals(LocalAiConfiguration.SAFE_ANDROID_GPU_BATCH_SIZE, hybrid.microBatchSize)
        assertEquals(false, hybrid.offloadKqv)
        assertEquals(false, hybrid.offloadOperations)
        assertTrue(hybrid.automaticCpuFallback)
        assertEquals(null, hybrid.androidGpuSafetyError())
    }

    @Test(expected = IllegalArgumentException::class)
    fun practicePackageRequiresTranscriptExcerpt() {
        buildAiDiagnosticsBenchmarkPlan(
            benchmarkPackage = AiDiagnosticsBenchmarkPackage.PRACTICE_TRANSCRIPT,
            selectedModel = AiModel.BALANCED_Q4,
            installedModels = listOf(AiModel.BALANCED_Q4),
            processorCount = 8,
            maximumFrequenciesKhz = emptyList(),
            transcriptText = "",
            loadConfiguration = { LocalAiConfiguration() }
        )
    }

    @Test
    fun medianAndSpreadAreDeterministic() {
        assertEquals(100L, medianMilliseconds(listOf(90L, 100L, 110L)))
        assertEquals(105L, medianMilliseconds(listOf(100L, 110L)))
        assertEquals(20.0, spreadPercent(listOf(90L, 100L, 110L)), 0.001)
    }
}
