package de.matthiasennen.transcript

import de.matthiasennen.transcript.ai.LocalAiBackend
import de.matthiasennen.transcript.ai.LocalAiConfiguration
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
}
