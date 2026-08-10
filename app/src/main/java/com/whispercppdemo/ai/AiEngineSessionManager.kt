package de.matthiasennen.transcript.ai

import android.os.SystemClock
import java.io.File

internal data class AiEngineSessionInfo(
    val modelAlreadyLoaded: Boolean,
    val modelLoadMs: Long,
    val cpuFallbackUsed: Boolean = false
)

internal data class AiEngineSessionResult<T>(
    val value: T,
    val info: AiEngineSessionInfo
)

internal fun shouldRetryWithCpu(
    configuration: LocalAiConfiguration,
    failureMessage: String?
): Boolean = configuration.automaticCpuFallback &&
    (configuration.backend == de.matthiasennen.transcript.ai.LocalAiBackend.VULKAN ||
        configuration.backend == de.matthiasennen.transcript.ai.LocalAiBackend.HYBRID) &&
    failureMessage.orEmpty().contains("VULKAN_DEVICE_LOST", ignoreCase = true)

internal object AiEngineSessionManager {
    private var engine: LocalAiEngine? = null
    private var modelId: String? = null
    private var modelPath: String? = null
    private var configurationKey: String? = null

    @Synchronized
    fun isLoaded(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration
    ): Boolean = engine != null && modelId == model.id && modelPath == file.absolutePath &&
        configurationKey == configuration.runtimeKey()

    @Synchronized
    fun hasTestConversation(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration
    ): Boolean = isLoaded(model, file, configuration) && requireNotNull(engine).hasTestConversation()

    @Synchronized
    fun <T> withModel(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration,
        block: (LocalAiEngine, AiEngineSessionInfo) -> T
    ): AiEngineSessionResult<T> {
        val normalized = configuration.normalized()
        return try {
            runWithConfiguration(model, file, normalized, false, block)
        } catch (failure: Throwable) {
            val canFallback = shouldRetryWithCpu(normalized, failure.message)
            if (!canFallback) throw failure
            releaseLocked()
            runWithConfiguration(model, file, normalized.cpuFallback(), true, block)
        }
    }

    private fun <T> runWithConfiguration(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration,
        cpuFallbackUsed: Boolean,
        block: (LocalAiEngine, AiEngineSessionInfo) -> T
    ): AiEngineSessionResult<T> {
        val alreadyLoaded = isLoaded(model, file, configuration)
        var loadMs = 0L
        if (!alreadyLoaded) {
            releaseLocked()
            val startedAt = SystemClock.elapsedRealtime()
            val loadedEngine = LocalAiEngine(file.absolutePath, configuration)
            loadMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            engine = loadedEngine
            modelId = model.id
            modelPath = file.absolutePath
            configurationKey = configuration.runtimeKey()
        }
        val info = AiEngineSessionInfo(alreadyLoaded, loadMs, cpuFallbackUsed)
        return AiEngineSessionResult(block(requireNotNull(engine), info), info)
    }

    @Synchronized
    fun releaseIfDifferent(model: AiModel) {
        if (modelId != null && modelId != model.id) releaseLocked()
    }

    @Synchronized
    fun release(model: AiModel? = null) {
        if (model == null || modelId == model.id) releaseLocked()
    }

    @Synchronized
    fun resetTestConversation() {
        engine?.resetTestConversation()
    }

    private fun releaseLocked() {
        engine?.close()
        engine = null
        modelId = null
        modelPath = null
        configurationKey = null
    }
}
