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
        assertEquals(20, SettingsOptionCatalogs.whisperBeamSize.last())
        assertTrue(5 in SettingsOptionCatalogs.whisperBeamSize)
        assertEquals(500, SettingsOptionCatalogs.whisperMaximumSegmentCharacters.last())
        assertTrue(0 in SettingsOptionCatalogs.whisperMaximumSegmentCharacters)
        assertFalse(1 in SettingsOptionCatalogs.whisperMaximumSegmentCharacters)
        assertEquals(90, SettingsOptionCatalogs.vadThresholdPercent.last())
        assertEquals(2_000, SettingsOptionCatalogs.vadSpeechDurationMs.last())
        assertEquals(600, SettingsOptionCatalogs.vadMaximumSpeechSeconds.last())
        assertEquals(1_000, SettingsOptionCatalogs.vadPaddingMs.last())
    }

    @Test
    fun processorCatalogsRespectDetectedCoreCount() {
        assertEquals(listOf(0, 1), SettingsOptionCatalogs.processorThreads(1, includeAutomatic = true))
        assertEquals((1..8).toList(), SettingsOptionCatalogs.processorThreads(8))
        assertEquals(64, SettingsOptionCatalogs.processorThreads(128).last())
    }

    @Test
    fun dependentAiCatalogsAlwaysContainTheirCurrentUsefulMaximum() {
        assertTrue(3_072 in SettingsOptionCatalogs.aiBatchSize(3_072))
        assertEquals(3_072, SettingsOptionCatalogs.aiBatchSize(3_072).last())
        assertTrue(768 in SettingsOptionCatalogs.aiMicroBatchSize(768))
        assertEquals(768, SettingsOptionCatalogs.aiMicroBatchSize(768).last())
        assertTrue(1_536 in SettingsOptionCatalogs.aiMaximumOutputTokens(3_072))
        assertEquals(1_536, SettingsOptionCatalogs.aiMaximumOutputTokens(3_072).last())
    }

    @Test
    fun gpuCatalogsUseActualModelLayerCountAndKeepExactLayerCanonical() {
        assertEquals(listOf(-1, 1, 2, 3, 4), SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.VULKAN, 4))
        assertEquals(listOf(1, 2, 3, 4), SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.HYBRID, 4))
        assertTrue(SettingsOptionCatalogs.aiGpuLayers(LocalAiBackend.VULKAN, 0).isEmpty())
        assertFalse(0 in SettingsOptionCatalogs.aiGpuLayerPercent)
        assertEquals(100, SettingsOptionCatalogs.aiGpuLayerPercent.last())
        assertEquals(-1, SettingsOptionCatalogs.gpuLayersForPercent(LocalAiBackend.VULKAN, 100, 32))
        assertEquals(32, SettingsOptionCatalogs.gpuLayersForPercent(LocalAiBackend.HYBRID, 100, 32))
        assertEquals(100, SettingsOptionCatalogs.gpuPercentForLayers(LocalAiBackend.VULKAN, -1, 32))
    }

    @Test
    fun cpuCoreMaskRoundTripsArbitrarySelectionsAndAllCores() {
        assertEquals(setOf(0, 2, 5), SettingsOptionCatalogs.parseCpuCoreMask("0,2,5", 8))
        assertEquals("0,2,5", SettingsOptionCatalogs.serializeCpuCoreMask(setOf(5, 0, 2), 8))
        assertEquals("", SettingsOptionCatalogs.serializeCpuCoreMask((0 until 8).toSet(), 8))
        assertEquals((0 until 8).toSet(), SettingsOptionCatalogs.parseCpuCoreMask("", 8))
    }

    @Test
    fun legacyCurrentValueCanRemainVisibleWithoutChangingRegularCatalog() {
        val regular = listOf(10, 20, 30)
        assertEquals(listOf(10, 17, 20, 30), SettingsOptionCatalogs.withCurrentValue(regular, 17))
        assertEquals(regular, SettingsOptionCatalogs.withCurrentValue(regular, 20))
    }
}
