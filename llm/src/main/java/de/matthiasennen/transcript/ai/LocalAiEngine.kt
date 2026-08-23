package de.matthiasennen.transcript.ai

import java.io.Closeable

data class LocalAiGenerationMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptProcessingMs: Long,
    val timeToFirstTokenMs: Long,
    val answerGenerationMs: Long,
    val totalInferenceMs: Long,
    val finishReason: String,
    val thinkingDisabled: Boolean
)

data class LocalAiGenerationResult(
    val text: String,
    val metrics: LocalAiGenerationMetrics
)

/**
 * Small lifecycle wrapper around llama.cpp. The model is mapped once. Free test
 * prompts share one in-memory message conversation until it is explicitly reset;
 * every chat turn receives a fresh native compute context. Transcript correction
 * keeps one group context cached and resets to that exact base before every target
 * segment.
 */
class LocalAiEngine(
    modelPath: String,
    val configuration: LocalAiConfiguration = LocalAiConfiguration()
) : Closeable {
    private val normalizedConfiguration = configuration.normalized()
    private var handle: Long = LocalAiNative.create(
        modelPath = modelPath,
        contextSize = normalizedConfiguration.contextSize,
        generationThreads = normalizedConfiguration.generationThreads,
        promptThreads = normalizedConfiguration.promptThreads,
        batchSize = normalizedConfiguration.batchSize,
        microBatchSize = normalizedConfiguration.microBatchSize,
        flashAttention = normalizedConfiguration.flashAttention.ordinal,
        loadMode = normalizedConfiguration.loadMode.ordinal,
        backend = normalizedConfiguration.backend.ordinal,
        cpuBackend = normalizedConfiguration.cpuBackend.ordinal,
        gpuDeviceIndex = normalizedConfiguration.gpuDeviceIndex,
        gpuLayers = normalizedConfiguration.gpuLayers,
        offloadKqv = normalizedConfiguration.offloadKqv,
        offloadOperations = normalizedConfiguration.offloadOperations,
        automaticCpuFallback = normalizedConfiguration.automaticCpuFallback,
        cpuCoreMask = normalizedConfiguration.cpuCoreMask,
        strictCpuPlacement = normalizedConfiguration.strictCpuPlacement,
        threadPriority = normalizedConfiguration.threadPriority.ordinal,
        threadPollingPercent = normalizedConfiguration.threadPollingPercent,
        kleidiSmeUnits = normalizedConfiguration.kleidiSmeUnits,
        kleidiChunkMultiplier = normalizedConfiguration.kleidiChunkMultiplier,
        nativeLibrarySearchPath = nativeLibrarySearchPath()
    )

    init {
        check(handle != 0L) { "Das lokale KI-Modell konnte nicht geladen werden." }
    }

    private fun generate(prompt: String, maximumOutputTokens: Int): LocalAiGenerationResult {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Der KI-Auftrag darf nicht leer sein." }
        val values = LocalAiNative.generate(handle, prompt, maximumOutputTokens)
            ?: error(
                LocalAiNative.lastError(handle)
                    ?.takeIf(String::isNotBlank)
                    ?: "Das lokale KI-Modell hat keinen verwertbaren Text erzeugt."
            )
        check(values.size == GENERATION_RESULT_FIELD_COUNT) {
            "Die lokale KI hat unvollständige Diagnosewerte geliefert."
        }
        val text = values[0].trim().takeIf(String::isNotEmpty)
            ?: error("Das lokale KI-Modell hat keinen verwertbaren Text erzeugt.")
        return LocalAiGenerationResult(
            text = text,
            metrics = LocalAiGenerationMetrics(
                promptTokens = values[1].toIntOrNull() ?: 0,
                generatedTokens = values[2].toIntOrNull() ?: 0,
                promptProcessingMs = values[3].toLongOrNull() ?: 0L,
                timeToFirstTokenMs = values[4].toLongOrNull() ?: 0L,
                answerGenerationMs = values[5].toLongOrNull() ?: 0L,
                totalInferenceMs = values[6].toLongOrNull() ?: 0L,
                finishReason = values[7],
                thinkingDisabled = values[8].toBooleanStrictOrNull() ?: false
            )
        )
    }

    fun generateTest(
        prompt: String,
        maximumOutputTokens: Int = normalizedConfiguration.maximumOutputTokens
    ): LocalAiGenerationResult = generate(
        prompt = "[[FREE_TEST]]$prompt",
        maximumOutputTokens = maximumOutputTokens
    )

    fun runtimeReport(): LocalAiRuntimeReport {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        val values = LocalAiNative.runtimeReport(handle)
            ?: error("Die aktive KI-Laufzeit konnte nicht ausgelesen werden.")
        check(values.size == RUNTIME_REPORT_FIELD_COUNT)
        return LocalAiRuntimeReport(
            requestedBackend = values[0],
            activeBackend = values[1],
            activeCpuBackend = values[2],
            gpuDevice = values[3],
            modelLayers = values[4].toIntOrNull() ?: 0,
            requestedGpuLayers = values[5].toIntOrNull() ?: 0,
            fallbackUsed = values[6].toBooleanStrictOrNull() ?: false,
            loadMode = values[7]
        )
    }

    fun hasTestConversation(): Boolean {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        return LocalAiNative.hasTestConversation(handle)
    }

    fun resetTestConversation() {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        LocalAiNative.resetTestConversation(handle)
    }

    fun prepareCorrectionContext(prompt: String) {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Der Gesprächskontext darf nicht leer sein." }
        check(LocalAiNative.prepareCorrectionContext(handle, prompt)) {
            LocalAiNative.lastError(handle)
                ?.takeIf(String::isNotBlank)
                ?: "Der gemeinsame Gesprächskontext konnte nicht vorbereitet werden."
        }
    }

    fun correctSegment(prompt: String, maximumOutputTokens: Int): String {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Das Zielsegment darf nicht leer sein." }
        val result = LocalAiNative.generateCorrection(handle, prompt, maximumOutputTokens)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (result != null) return result
        LocalAiNative.lastError(handle)
            ?.takeIf(String::isNotBlank)
            ?.let { error(it) }
        return "{\"result\":\"\"}"
    }

    /**
     * Returns and clears the native correction trace accumulated since the
     * previous call. The trace contains technical sizes/status only; transcript
     * text itself is not persisted by the native layer.
     */
    fun consumeCorrectionDiagnostics(): List<String> {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        return LocalAiNative.consumeCorrectionDiagnostics(handle)?.toList().orEmpty()
    }

    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) LocalAiNative.release(activeHandle)
    }

    companion object {
        private const val GENERATION_RESULT_FIELD_COUNT = 9
        private const val RUNTIME_REPORT_FIELD_COUNT = 8

        @Volatile
        private var configuredNativeLibraryDirectory: String = ""

        fun configureNativeLibraryDirectory(directory: String?) {
            val normalized = directory?.trim().orEmpty()
            if (normalized.isNotEmpty()) configuredNativeLibraryDirectory = normalized
        }

        private fun nativeLibrarySearchPath(): String = linkedSetOf(
            configuredNativeLibraryDirectory,
            System.getProperty("java.library.path").orEmpty()
        ).filter(String::isNotBlank).joinToString(separator = ":")

        internal fun preferredThreadCount(
            availableProcessors: Int = Runtime.getRuntime().availableProcessors()
        ): Int = LocalAiConfiguration.preferredThreadCount(availableProcessors)

        fun runtimeCapabilitiesJson(): String =
            LocalAiNative.runtimeCapabilities(nativeLibrarySearchPath())

        fun inspectModelLayerCount(modelPath: String): Int =
            LocalAiNative.inspectModelLayerCount(modelPath, nativeLibrarySearchPath())
    }
}

internal object LocalAiNative {
    init {
        System.loadLibrary("transcript_llm")
    }

    external fun create(
        modelPath: String,
        contextSize: Int,
        generationThreads: Int,
        promptThreads: Int,
        batchSize: Int,
        microBatchSize: Int,
        flashAttention: Int,
        loadMode: Int,
        backend: Int,
        cpuBackend: Int,
        gpuDeviceIndex: Int,
        gpuLayers: Int,
        offloadKqv: Boolean,
        offloadOperations: Boolean,
        automaticCpuFallback: Boolean,
        cpuCoreMask: String,
        strictCpuPlacement: Boolean,
        threadPriority: Int,
        threadPollingPercent: Int,
        kleidiSmeUnits: Int,
        kleidiChunkMultiplier: Int,
        nativeLibrarySearchPath: String
    ): Long
    external fun generate(handle: Long, prompt: String, maximumOutputTokens: Int): Array<String>?
    external fun lastError(handle: Long): String?
    external fun hasTestConversation(handle: Long): Boolean
    external fun resetTestConversation(handle: Long)
    external fun prepareCorrectionContext(handle: Long, prompt: String): Boolean
    external fun generateCorrection(
        handle: Long,
        prompt: String,
        maximumOutputTokens: Int
    ): String?
    external fun consumeCorrectionDiagnostics(handle: Long): Array<String>?
    external fun runtimeReport(handle: Long): Array<String>?
    external fun runtimeCapabilities(nativeLibrarySearchPath: String): String
    external fun inspectModelLayerCount(modelPath: String, nativeLibrarySearchPath: String): Int
    external fun release(handle: Long)
}
