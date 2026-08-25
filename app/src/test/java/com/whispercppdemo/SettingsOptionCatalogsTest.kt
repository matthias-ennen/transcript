package de.matthiasennen.transcript

import de.matthiasennen.transcript.ai.LocalAiBackend
import de.matthiasennen.transcript.ui.main.SettingsOptionCatalogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOptionCatalogsTest {
    @Test
    fun whisperAndVadCatalogsContainMeaningfulUpperBoundsAndDefaults() {
        assertCatalog(SettingsOptionCatalogs.whisperBeamSize, 1, 20, 5)
        assertCatalog(SettingsOptionCatalogs.whisperBestOf, 1, 20, 2)
        assertCatalog(SettingsOptionCatalogs.whisperTemperaturePercent, 0, 100, 0)
        assertEquals(500, SettingsOptionCatalogs.whisperMaximumSegmentCharacters.last())
        assertTrue(0 in SettingsOptionCatalogs.whisperMaximumSegmentCharacters)
        assertFalse(1 in SettingsOptionCatalogs.whisperMaximumSegmentCharacters)
        assertCatalog(SettingsOptionCatalogs.whisperLogProbabilityPercent, -500, 0, -100)
        assertCatalog(SettingsOptionCatalogs.whisperNoSpeechPercent, 0, 100, 60)
        assertCatalog(SettingsOptionCatalogs.whisperEntropyPercent, 0, 500, 240)

        assertCatalog(SettingsOptionCatalogs.vadThresholdPercent, 10, 90, 50)
        assertCatalog(SettingsOptionCatalogs.vadSpeechDurationMs, 50, 2_000, 250)
        assertTrue(100 in SettingsOptionCatalogs.vadSpeechDurationMs)
        assertCatalog(SettingsOptionCatalogs.vadMaximumSpeechSeconds, 30, 600, 300)
        assertCatalog(SettingsOptionCatalogs.vadPaddingMs, 0, 1_000, 100)
    }

    @Test
    fun aiCatalogsContainUsefulMaximumDefaultsAndNoDuplicates() {
        assertCatalog(SettingsOptionCatalogs.aiContextSize, 1_024, 32_768, 4_096)
        assertCatalog(SettingsOptionCatalogs.aiThreadPollingPercent, 0, 100, 50)
        assertCatalog(SettingsOptionCatalogs.aiKleidiSmeUnits, -1, 64, -1)
        assertCatalog(SettingsOptionCatalogs.aiKleidiChunkMultiplier, 0, 64, 0)
        assertCatalog(SettingsOptionCatalogs.aiGpuLayerPercent, 5, 100, 100)
        assertCatalog(SettingsOptionCatalogs.aiMinimumFreeMemoryMb, 128, 8_192, 512)
        assertCatalog(SettingsOptionCatalogs.aiMaximumMemoryPercent, 40, 95, 80)
        assertCatalog(SettingsOptionCatalogs.aiMaximumVulkanMemoryPercent, 25, 95, 80)
        assertCatalog(SettingsOptionCatalogs.aiGpuLayersReducedPerStep, 1, 128, 8)
        assertCatalog(SettingsOptionCatalogs.aiCoolingPauseSeconds, 0, 300, 15)
        assertCatalog(SettingsOptionCatalogs.benchmarkWarmupRuns, 0, 5, 1)
        assertCatalog(SettingsOptionCatalogs.benchmarkMeasuredRuns, 1, 10, 3)
        assertCatalog(SettingsOptionCatalogs.benchmarkPromptCharacters, 128, 8_192, 512)
        assertCatalog(SettingsOptionCatalogs.benchmarkOutputTokens, 32, 512, 96)
        assertCatalog(SettingsOptionCatalogs.benchmarkPauseSeconds, 0, 120, 5)
        assertCatalog(SettingsOptionCatalogs.benchmarkMinimumBatteryPercent, 0, 100, 30)
    }

    @Test
    fun processorCatalogsRespectDetectedCoreCount() {
        assertEquals(listOf(0, 1), SettingsOptionCatalogs.processorThreads(1, includeAutomatic = true))
        listOf(1, 2, 4, 8, 12).forEach { processors ->
            val options = SettingsOptionCatalogs.processorThreads(processors)
            assertEquals(1, options.first())
            assertEquals(processors, options.last())
            assertEquals(processors, options.size)
        }
        assertEquals(64, SettingsOptionCatalogs.processorThreads(128).last())
    }

    @Test
    fun dependentAiCatalogsAlwaysContainTheirCurrentUsefulMaximum() {
        assertEquals(3_072, SettingsOptionCatalogs.aiBatchSize(3_072).last())
        assertTrue(3_072 in SettingsOptionCatalogs.aiBatchSize(3_072))
        assertEquals(768, SettingsOptionCatalogs.aiMicroBatchSize(768).last())
        assertTrue(768 in SettingsOptionCatalogs.aiMicroBatchSize(768))
        assertEquals(1_536, SettingsOptionCatalogs.aiMaximumOutputTokens(3_072).last())
        assertTrue(1_536 in SettingsOptionCatalogs.aiMaximumOutputTokens(3_072))

        assertEquals(32_768, SettingsOptionCatalogs.aiBatchSize(32_768).last())
        assertEquals(32_768, SettingsOptionCatalogs.aiMicroBatchSize(32_768).last())
        assertEquals(16_384, SettingsOptionCatalogs.aiMaximumOutputTokens(32_768).last())
    }

    @Test
    fun gpuCatalogsUseActualModelLayerCountAndKeepExactLayerCanonical() {
        assertEquals(listOf(-1, 1, 2, 3, 4), SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.VULKAN, 4))
        assertEquals(listOf(1, 2, 3, 4), SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.HYBRID, 4))
        assertTrue(SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.CPU, 4).isEmpty())
        assertTrue(SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.VULKAN, 0).isEmpty())

        // 0 % would mean zero GPU layers and is therefore not a meaningful regular option
        // for either explicit GPU backend. 100 % is the useful maximum and is always present.
        assertFalse(0 in SettingsOptionCatalogs.aiGpuLayerPercent)
        assertEquals(100, SettingsOptionCatalogs.aiGpuLayerPercent.last())
        assertEquals(-1, SettingsOptionCatalogs.gpuLayersForPercent(LocalAiBackend.VULKAN, 100, 32))
        assertEquals(32, SettingsOptionCatalogs.gpuLayersForPercent(LocalAiBackend.HYBRID, 100, 32))
        assertEquals(16, SettingsOptionCatalogs.gpuLayersForPercent(LocalAiBackend.HYBRID, 50, 32))
        assertEquals(100, SettingsOptionCatalogs.gpuPercentForLayers(LocalAiBackend.VULKAN, -1, 32))
        assertEquals(50, SettingsOptionCatalogs.gpuPercentForLayers(LocalAiBackend.HYBRID, 16, 32))
    }

    @Test
    fun cpuCoreMaskRoundTripsArbitrarySelectionsAndAllCores() {
        assertEquals(setOf(0, 2, 5), SettingsOptionCatalogs.parseCpuCoreMask("0,2,5", 8))
        assertEquals("0,2,5", SettingsOptionCatalogs.serializeCpuCoreMask(setOf(5, 0, 2), 8))
        assertEquals("", SettingsOptionCatalogs.serializeCpuCoreMask((0 until 8).toSet(), 8))
        assertEquals((0 until 8).toSet(), SettingsOptionCatalogs.parseCpuCoreMask("", 8))
        assertEquals(setOf(0, 7), SettingsOptionCatalogs.parseCpuCoreMask("0,7,99,-1,foo", 8))
    }

    @Test
    fun legacyCurrentValueCanRemainVisibleWithoutChangingRegularCatalog() {
        val regular = listOf(10, 20, 30)
        assertEquals(listOf(10, 17, 20, 30), SettingsOptionCatalogs.withCurrentValue(regular, 17))
        assertEquals(regular, SettingsOptionCatalogs.withCurrentValue(regular, 20))
    }

    private fun assertCatalog(options: List<Int>, minimum: Int, maximum: Int, defaultValue: Int) {
        assertTrue(options.isNotEmpty())
        assertEquals(minimum, options.first())
        assertEquals(maximum, options.last())
        assertTrue(defaultValue in options)
        assertEquals(options.size, options.distinct().size)
        assertEquals(options.sorted(), options)
    }
}
