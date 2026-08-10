package de.matthiasennen.transcript.ai

import android.os.SystemClock
import java.io.File

internal data class AiEngineSessionInfo(
    val modelAlreadyLoaded: Boolean,
    val modelLoadMs: Long
)

internal data class AiEngineSessionResult<T>(
    val value: T,
    val info: AiEngineSessionInfo
)

internal object AiEngineSessionManager {
    private var engine: LocalAiEngine? = null
    private var modelId: String? = null
    private var modelPath: String? = null

    @Synchronized
    fun isLoaded(model: AiModel, file: File): Boolean =
        engine != null && modelId == model.id && modelPath == file.absolutePath

    @Synchronized
    fun hasTestConversation(model: AiModel, file: File): Boolean =
        isLoaded(model, file) && requireNotNull(engine).hasTestConversation()

    @Synchronized
    fun <T> withModel(
        model: AiModel,
        file: File,
        block: (LocalAiEngine, AiEngineSessionInfo) -> T
    ): AiEngineSessionResult<T> {
        val alreadyLoaded = isLoaded(model, file)
        var loadMs = 0L
        if (!alreadyLoaded) {
            releaseLocked()
            val startedAt = SystemClock.elapsedRealtime()
            val loadedEngine = LocalAiEngine(file.absolutePath)
            loadMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            engine = loadedEngine
            modelId = model.id
            modelPath = file.absolutePath
        }
        val info = AiEngineSessionInfo(
            modelAlreadyLoaded = alreadyLoaded,
            modelLoadMs = loadMs
        )
        return AiEngineSessionResult(
            value = block(requireNotNull(engine), info),
            info = info
        )
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
    }
}
