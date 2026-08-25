package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.LocalAiBackend
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Curated, testable option catalogs for the controlled settings UI.
 *
 * The catalogs deliberately cover the practically useful tuning range rather than
 * blindly exposing every integer accepted by normalization. The meaningful upper
 * bound and the current default are always represented by the callers; legacy
 * values can be added with [withCurrentValue] without changing the regular catalog.
 */
internal object SettingsOptionCatalogs {
    fun steppedRange(start: Int, endInclusive: Int, step: Int): List<Int> {
        require(step > 0)
        require(start <= endInclusive)
        val values = (start..endInclusive step step).toMutableList()
        if (values.lastOrNull() != endInclusive) values += endInclusive
        return values.distinct()
    }

    fun withCurrentValue(options: List<Int>, current: Int): List<Int> =
        if (current in options) options else (options + current).distinct().sorted()

    fun processorThreads(processorCount: Int, includeAutomatic: Boolean = false): List<Int> {
        val limit = processorCount.coerceIn(1, 64)
        return (if (includeAutomatic) listOf(0) else emptyList()) + (1..limit)
    }

    val whisperBeamSize: List<Int> = (1..20).toList()
    val whisperBestOf: List<Int> = (1..20).toList()
    val whisperTemperaturePercent: List<Int> = steppedRange(0, 100, 5)
    val whisperMaximumSegmentCharacters: List<Int> = listOf(0) + steppedRange(10, 500, 10)
    val whisperLogProbabilityPercent: List<Int> = steppedRange(-500, 0, 10)
    val whisperNoSpeechPercent: List<Int> = steppedRange(0, 100, 5)
    val whisperEntropyPercent: List<Int> = steppedRange(0, 500, 10)

    val vadThresholdPercent: List<Int> = steppedRange(10, 90, 5)
    val vadSpeechDurationMs: List<Int> = steppedRange(50, 2_000, 50)
    val vadMaximumSpeechSeconds: List<Int> = steppedRange(30, 600, 10)
    val vadPaddingMs: List<Int> = steppedRange(0, 1_000, 25)

    val aiContextSize: List<Int> = steppedRange(1_024, 32_768, 1_024)

    private val batchBase = listOf(32, 64, 128, 256, 512, 1_024, 2_048, 4_096, 8_192, 16_384, 32_768)
    private val microBatchBase = listOf(16, 32, 64, 128, 256, 512, 1_024, 2_048, 4_096, 8_192, 16_384, 32_768)
    private val outputTokenBase = listOf(32, 64, 96, 128, 192, 256, 384, 512, 768, 1_024, 1_536, 2_048, 3_072, 4_096, 6_144, 8_192, 12_288, 16_384)

    fun aiBatchSize(contextSize: Int): List<Int> {
        val maximum = contextSize.coerceIn(32, 32_768)
        return (batchBase.filter { it <= maximum } + maximum).distinct().sorted()
    }

    fun aiMicroBatchSize(batchSize: Int): List<Int> {
        val maximum = batchSize.coerceIn(16, 32_768)
        return (microBatchBase.filter { it <= maximum } + maximum).distinct().sorted()
    }

    fun aiMaximumOutputTokens(contextSize: Int): List<Int> {
        val maximum = (contextSize.coerceIn(1_024, 32_768) / 2).coerceAtLeast(32)
        return (outputTokenBase.filter { it <= maximum } + maximum).distinct().sorted()
    }

    val aiThreadPollingPercent: List<Int> = steppedRange(0, 100, 5)
    val aiKleidiSmeUnits: List<Int> = (-1..64).toList()
    val aiKleidiChunkMultiplier: List<Int> = (0..64).toList()

    fun aiGpuLayers(backend: LocalAiBackend, modelLayerCount: Int): List<Int> {
        if (modelLayerCount <= 0) return emptyList()
        return when (backend) {
            LocalAiBackend.VULKAN -> listOf(-1) + (1..modelLayerCount)
            LocalAiBackend.HYBRID -> (1..modelLayerCount).toList()
            LocalAiBackend.AUTO, LocalAiBackend.CPU -> emptyList()
        }
    }

    /** 0 % is intentionally not a regular option: both explicit GPU backends require at least one layer. */
    val aiGpuLayerPercent: List<Int> = steppedRange(5, 100, 5)

    fun gpuPercentForLayers(backend: LocalAiBackend, gpuLayers: Int, modelLayerCount: Int): Int {
        if (modelLayerCount <= 0) return 0
        if (backend == LocalAiBackend.VULKAN && gpuLayers == -1) return 100
        return ((gpuLayers.coerceAtLeast(1) * 100.0) / modelLayerCount)
            .roundToInt()
            .coerceIn(1, 100)
    }

    fun gpuLayersForPercent(backend: LocalAiBackend, percent: Int, modelLayerCount: Int): Int? {
        if (modelLayerCount <= 0) return null
        val normalizedPercent = percent.coerceIn(1, 100)
        if (backend == LocalAiBackend.VULKAN && normalizedPercent == 100) return -1
        return ceil(modelLayerCount * normalizedPercent / 100.0)
            .toInt()
            .coerceIn(1, modelLayerCount)
    }

    val aiMinimumFreeMemoryMb: List<Int> = listOf(128, 256, 384, 512, 768, 1_024, 1_536, 2_048, 3_072, 4_096, 6_144, 8_192)
    val aiMaximumMemoryPercent: List<Int> = steppedRange(40, 95, 1)
    val aiMaximumVulkanMemoryPercent: List<Int> = steppedRange(25, 95, 1)
    val aiGpuLayersReducedPerStep: List<Int> = listOf(1, 2, 4, 8, 16, 24, 32, 48, 64, 96, 128)
    val aiCoolingPauseSeconds: List<Int> = listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300)

    val benchmarkWarmupRuns: List<Int> = (0..5).toList()
    val benchmarkMeasuredRuns: List<Int> = (1..10).toList()
    val benchmarkPromptCharacters: List<Int> = steppedRange(128, 8_192, 128)
    val benchmarkOutputTokens: List<Int> = steppedRange(32, 512, 32)
    val benchmarkPauseSeconds: List<Int> = listOf(0, 1, 2, 3) + steppedRange(5, 120, 5)
    val benchmarkMinimumBatteryPercent: List<Int> = steppedRange(0, 100, 5)

    fun parseCpuCoreMask(value: String, processorCount: Int): Set<Int> {
        val limit = processorCount.coerceIn(1, 64)
        if (value.isBlank()) return (0 until limit).toSet()
        return value.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until limit }
            .toSet()
    }

    fun serializeCpuCoreMask(selectedCores: Set<Int>, processorCount: Int): String {
        val limit = processorCount.coerceIn(1, 64)
        val valid = selectedCores.filter { it in 0 until limit }.toSortedSet()
        return if (valid.size == limit) "" else valid.joinToString(",")
    }
}
