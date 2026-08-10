package de.matthiasennen.transcript.ai

enum class LocalAiBackend { AUTO, CPU, VULKAN, HYBRID }

enum class LocalAiCpuBackend { AUTO, STANDARD, KLEIDIAI }

enum class LocalAiFlashAttention { AUTO, ENABLED, DISABLED }

enum class LocalAiLoadMode { AUTO, MMAP, READ, MLOCK, MMAP_MLOCK }

enum class LocalAiThreadPriority { LOW, NORMAL, MEDIUM, HIGH }

/**
 * Every field in this object is consumed either by llama.cpp itself or by an
 * Android guard that runs before inference. Values are normalized once at the
 * app boundary, so the native layer never receives impossible combinations.
 */
data class LocalAiConfiguration(
    val contextSize: Int = 4_096,
    val generationThreads: Int = preferredThreadCount(),
    val promptThreads: Int = preferredThreadCount(),
    val batchSize: Int = 1_024,
    val microBatchSize: Int = 512,
    val maximumOutputTokens: Int = 512,
    val flashAttention: LocalAiFlashAttention = LocalAiFlashAttention.AUTO,
    val loadMode: LocalAiLoadMode = LocalAiLoadMode.AUTO,
    val backend: LocalAiBackend = LocalAiBackend.AUTO,
    val cpuBackend: LocalAiCpuBackend = LocalAiCpuBackend.AUTO,
    val gpuDeviceIndex: Int = 0,
    val gpuLayers: Int = 0,
    val gpuLayerPercent: Int = 0,
    val offloadKqv: Boolean = true,
    val offloadOperations: Boolean = true,
    val automaticCpuFallback: Boolean = true,
    val cpuCoreMask: String = "",
    val strictCpuPlacement: Boolean = false,
    val threadPriority: LocalAiThreadPriority = LocalAiThreadPriority.NORMAL,
    val threadPollingPercent: Int = 50,
    val kleidiSmeUnits: Int = -1,
    val kleidiChunkMultiplier: Int = 0,
    val minimumFreeMemoryMb: Int = 512,
    val maximumMemoryPercent: Int = 80,
    val thermalWarningStatus: Int = 2,
    val thermalThrottleStatus: Int = 3,
    val thermalStopStatus: Int = 4,
    val throttledThreads: Int = 2,
    val gpuLayersReducedPerStep: Int = 8,
    val coolingPauseSeconds: Int = 15,
    val benchmarkWarmupRuns: Int = 1,
    val benchmarkMeasuredRuns: Int = 3,
    val benchmarkPromptCharacters: Int = 512,
    val benchmarkOutputTokens: Int = 96,
    val benchmarkPauseSeconds: Int = 5,
    val benchmarkMinimumBatteryPercent: Int = 30,
    val benchmarkRequiresCharging: Boolean = false,
    val benchmarkMaximumThermalStatus: Int = 3
) {
    fun normalized(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): LocalAiConfiguration {
        val processorLimit = availableProcessors.coerceIn(1, 64)
        val normalizedContext = contextSize.coerceIn(1_024, 32_768)
        val normalizedBatch = batchSize.coerceIn(32, normalizedContext)
        val normalizedMicroBatch = microBatchSize.coerceIn(16, normalizedBatch)
        val normalizedWarning = thermalWarningStatus.coerceIn(0, 6)
        val normalizedThrottle = thermalThrottleStatus.coerceIn(normalizedWarning, 6)
        val normalizedStop = thermalStopStatus.coerceIn(normalizedThrottle, 6)
        val normalizedGpuPercent = gpuLayerPercent.coerceIn(0, 100)
        val normalizedGpuLayers = when (backend) {
            LocalAiBackend.CPU, LocalAiBackend.AUTO -> 0
            LocalAiBackend.VULKAN -> if (gpuLayers == 0) -1 else gpuLayers.coerceIn(-1, 512)
            LocalAiBackend.HYBRID -> gpuLayers.coerceIn(1, 512)
        }
        return copy(
            contextSize = normalizedContext,
            generationThreads = generationThreads.coerceIn(1, processorLimit),
            promptThreads = promptThreads.coerceIn(1, processorLimit),
            batchSize = normalizedBatch,
            microBatchSize = normalizedMicroBatch,
            maximumOutputTokens = maximumOutputTokens.coerceIn(32, normalizedContext / 2),
            gpuDeviceIndex = gpuDeviceIndex.coerceAtLeast(0),
            gpuLayers = normalizedGpuLayers,
            gpuLayerPercent = normalizedGpuPercent,
            threadPollingPercent = threadPollingPercent.coerceIn(0, 100),
            kleidiSmeUnits = kleidiSmeUnits.coerceIn(-1, 64),
            kleidiChunkMultiplier = kleidiChunkMultiplier.coerceIn(0, 64),
            minimumFreeMemoryMb = minimumFreeMemoryMb.coerceIn(128, 8_192),
            maximumMemoryPercent = maximumMemoryPercent.coerceIn(40, 95),
            thermalWarningStatus = normalizedWarning,
            thermalThrottleStatus = normalizedThrottle,
            thermalStopStatus = normalizedStop,
            throttledThreads = throttledThreads.coerceIn(1, processorLimit),
            gpuLayersReducedPerStep = gpuLayersReducedPerStep.coerceIn(1, 128),
            coolingPauseSeconds = coolingPauseSeconds.coerceIn(0, 300),
            benchmarkWarmupRuns = benchmarkWarmupRuns.coerceIn(0, 5),
            benchmarkMeasuredRuns = benchmarkMeasuredRuns.coerceIn(1, 10),
            benchmarkPromptCharacters = benchmarkPromptCharacters.coerceIn(128, 8_192),
            benchmarkOutputTokens = benchmarkOutputTokens.coerceIn(32, 512),
            benchmarkPauseSeconds = benchmarkPauseSeconds.coerceIn(0, 120),
            benchmarkMinimumBatteryPercent = benchmarkMinimumBatteryPercent.coerceIn(0, 100),
            benchmarkMaximumThermalStatus = benchmarkMaximumThermalStatus.coerceIn(0, 6)
        )
    }

    /** Fields that change the native model mapping or compute context. */
    fun runtimeKey(): String = normalized().let { value ->
        listOf(
            value.contextSize,
            value.generationThreads,
            value.promptThreads,
            value.batchSize,
            value.microBatchSize,
            value.maximumOutputTokens,
            value.flashAttention,
            value.loadMode,
            value.backend,
            value.cpuBackend,
            value.gpuDeviceIndex,
            value.gpuLayers,
            value.gpuLayerPercent,
            value.offloadKqv,
            value.offloadOperations,
            value.automaticCpuFallback,
            value.cpuCoreMask,
            value.strictCpuPlacement,
            value.threadPriority,
            value.threadPollingPercent,
            value.kleidiSmeUnits,
            value.kleidiChunkMultiplier
        ).joinToString("|")
    }

    companion object {
        fun preferredThreadCount(
            availableProcessors: Int = Runtime.getRuntime().availableProcessors()
        ): Int = (availableProcessors - 2).coerceIn(2, 6)
    }
}

data class LocalAiRuntimeReport(
    val requestedBackend: String,
    val activeBackend: String,
    val activeCpuBackend: String,
    val gpuDevice: String,
    val modelLayers: Int,
    val requestedGpuLayers: Int,
    val fallbackUsed: Boolean,
    val loadMode: String
)

