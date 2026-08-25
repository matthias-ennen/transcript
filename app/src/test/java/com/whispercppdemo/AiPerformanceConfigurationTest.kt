package de.matthiasennen.transcript

import de.matthiasennen.transcript.ai.LocalAiBackend
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.shouldRetryWithCpu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AiPerformanceConfigurationTest {
    @Test
    fun normalizedClampsDependentBatchAndThermalValues() {
        val normalized = LocalAiConfiguration(
            contextSize = 2_048,
            batchSize = 4_096,
            microBatchSize = 8_192,
            thermalWarningStatus = 5,
            thermalThrottleStatus = 2,
            thermalStopStatus = 1
        ).normalized(availableProcessors = 8)

        assertEquals(2_048, normalized.batchSize)
        assertEquals(2_048, normalized.microBatchSize)
        assertEquals(5, normalized.thermalWarningStatus)
        assertEquals(5, normalized.thermalThrottleStatus)
        assertEquals(5, normalized.thermalStopStatus)
    }

    @Test
    fun cpuBackendNeverKeepsGpuLayers() {
        val normalized = LocalAiConfiguration(
            backend = LocalAiBackend.CPU,
            gpuLayers = 12
        ).normalized()

        assertEquals(0, normalized.gpuLayers)
        assertEquals(0, normalized.gpuLayerPercent)
        assertEquals(false, normalized.offloadKqv)
        assertEquals(false, normalized.offloadOperations)
    }

    @Test
    fun automaticBackendIsGuaranteedToStayVulkanFree() {
        val normalized = LocalAiConfiguration(
            backend = LocalAiBackend.AUTO,
            gpuLayers = -1,
            gpuLayerPercent = 100,
            offloadKqv = true,
            offloadOperations = true
        ).normalized()

        assertEquals(0, normalized.gpuLayers)
        assertEquals(0, normalized.gpuLayerPercent)
        assertEquals(false, normalized.offloadKqv)
        assertEquals(false, normalized.offloadOperations)
    }

    @Test
    fun vulkanBackendMayKeepExplicitGpuOffloads() {
        val normalized = LocalAiConfiguration(
            backend = LocalAiBackend.VULKAN,
            offloadKqv = true,
            offloadOperations = true
        ).normalized()

        assertEquals(-1, normalized.gpuLayers)
        assertEquals(true, normalized.offloadKqv)
        assertEquals(true, normalized.offloadOperations)
    }

    @Test
    fun runtimeKeyChangesOnlyForNativeRuntimeFields() {
        val base = LocalAiConfiguration()
        assertEquals(
            base.runtimeKey(),
            base.copy(benchmarkMeasuredRuns = 8, minimumFreeMemoryMb = 1_024).runtimeKey()
        )
        assertNotEquals(base.runtimeKey(), base.copy(contextSize = 8_192).runtimeKey())
    }

    @Test
    fun modelCatalogExposesSixVariantsWithExpectedKleidiCompatibility() {
        assertEquals(6, AiModel.entries.size)
        assertEquals(6, AiModel.entries.map { it.id }.toSet().size)
        assertEquals(6, AiModel.entries.map { it.fileName }.toSet().size)

        assertEquals(true, AiModel.QUICK.kleidiAiCompatible)
        assertEquals(true, AiModel.QUICK_Q8.kleidiAiCompatible)
        assertEquals(false, AiModel.BALANCED.kleidiAiCompatible)
        assertEquals(true, AiModel.BALANCED_Q4.kleidiAiCompatible)
        assertEquals(false, AiModel.PRECISE.kleidiAiCompatible)
        assertEquals(true, AiModel.PRECISE_Q4.kleidiAiCompatible)
    }

    @Test
    fun legacyModelIdsStillResolveToTheSameVariants() {
        assertEquals(AiModel.QUICK, AiModel.fromId("qwen35-08b-q4"))
        assertEquals(AiModel.BALANCED, AiModel.fromId("qwen35-2b-q4km"))
        assertEquals(AiModel.PRECISE, AiModel.fromId("qwen35-4b-q4km"))
    }

    @Test
    fun deviceLostRetriesOnlyExplicitGpuBackendsOnceViaCpu() {
        assertEquals(
            true,
            shouldRetryWithCpu(
                LocalAiConfiguration(backend = LocalAiBackend.VULKAN),
                "VULKAN_DEVICE_LOST: vk::DeviceLostError"
            )
        )
        assertEquals(
            false,
            shouldRetryWithCpu(
                LocalAiConfiguration(backend = LocalAiBackend.CPU),
                "VULKAN_DEVICE_LOST: vk::DeviceLostError"
            )
        )
        assertEquals(
            false,
            shouldRetryWithCpu(
                LocalAiConfiguration(
                    backend = LocalAiBackend.HYBRID,
                    automaticCpuFallback = false
                ),
                "VULKAN_DEVICE_LOST: vk::DeviceLostError"
            )
        )
    }
}
