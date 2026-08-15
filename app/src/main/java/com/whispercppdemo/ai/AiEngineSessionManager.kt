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
        configurationKey == configuration.normalized().runtimeKey()

    @Synchronized
    fun hasTestConversation(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration
    ): Boolean = isLoaded(model, file, configuration) && requireNotNull(engine).hasTestConversation()

    @Synchronized
    fun runtimeReport(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration
    ): LocalAiRuntimeReport? = if (isLoaded(model, file, configuration)) {
        requireNotNull(engine).runtimeReport()
    } else {
        null
    }

    @Synchronized
    fun <T> withModel(
        model: AiModel,
        file: File,
        configuration: LocalAiConfiguration,
        block: (LocalAiEngine, AiEngineSessionInfo) -> T
    ): AiEngineSessionResult<T> {
        val normalized = configuration.normalized()
        return try {
            runWithConfiguration(
                model = model,
                file = file,
                activeConfiguration = normalized,
                sessionConfigurationKey = normalized.runtimeKey(),
                cpuFallbackUsed = false,
                block = block
            )
        } catch (failure: Throwable) {
            val canFallback = shouldRetryWithCpu(normalized, failure.message)
            if (!canFallback) throw failure
            releaseLocked()
            runWithConfiguration(
                model = model,
                file = file,
                activeConfiguration = normalized.cpuFallback(),
                sessionConfigurationKey = normalized.runtimeKey(),
                cpuFallbackUsed = true,
                block = block
            )
        }
    }

    private fun <T> runWithConfiguration(
        model: AiModel,
        file: File,
        activeConfiguration: LocalAiConfiguration,
        sessionConfigurationKey: String,
        cpuFallbackUsed: Boolean,
        block: (LocalAiEngine, AiEngineSessionInfo) -> T
    ): AiEngineSessionResult<T> {
        val alreadyLoaded = engine != null && modelId == model.id &&
            modelPath == file.absolutePath && configurationKey == sessionConfigurationKey
        var loadMs = 0L
        if (!alreadyLoaded) {
            releaseLocked()
            val startedAt = SystemClock.elapsedRealtime()
            val loadedEngine = LocalAiEngine(file.absolutePath, activeConfiguration)
            loadMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            engine = loadedEngine
            modelId = model.id
            modelPath = file.absolutePath
            // A CPU fallback satisfies the original requested runtime configuration. Keeping
            // that request key prevents the next prompt from discarding the safe fallback and
            // attempting the failed Vulkan configuration again.
            configurationKey = sessionConfigurationKey
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
