package de.matthiasennen.transcript.ai

import android.content.Context
import org.json.JSONObject

private const val PERFORMANCE_PREFERENCES = "local_ai_performance_profiles_v1"
private const val PROFILE_SCHEMA = 2

class AiPerformancePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(
        PERFORMANCE_PREFERENCES,
        Context.MODE_PRIVATE
    )

    init {
        val editor = preferences.edit()
        AiModel.entries.forEach { editor.remove("working_profile_${it.id}") }
        editor.apply()
    }

    fun load(model: AiModel): LocalAiConfiguration {
        val stored = preferences.getString(key(model), null) ?: return LocalAiConfiguration()
        val normalized = runCatching { configurationFromJson(JSONObject(stored)) }
            .getOrElse { LocalAiConfiguration() }
            .normalized()
        val normalizedJson = configurationToJson(normalized).toString()
        if (stored != normalizedJson) {
            preferences.edit().putString(key(model), normalizedJson).apply()
        }
        removeLegacyWorkingProfile(model)
        return normalized
    }

    fun save(model: AiModel, configuration: LocalAiConfiguration): LocalAiConfiguration {
        val normalized = configuration.normalized()
        preferences.edit()
            .putString(key(model), configurationToJson(normalized).toString())
            .remove("working_profile_${model.id}")
            .apply()
        return normalized
    }

    fun reset(model: AiModel): LocalAiConfiguration {
        preferences.edit()
            .remove(key(model))
            .remove("working_profile_${model.id}")
            .apply()
        return LocalAiConfiguration().normalized()
    }

    fun copy(source: AiModel, target: AiModel): LocalAiConfiguration = save(target, load(source))

    fun exportJson(model: AiModel): String = JSONObject()
        .put("schema", PROFILE_SCHEMA)
        .put("modelId", model.id)
        .put("modelLabel", model.modelLabel)
        .put("configuration", configurationToJson(load(model)))
        .toString(2)

    fun importJson(model: AiModel, value: String): LocalAiConfiguration {
        val root = JSONObject(value)
        val schema = root.optInt("schema", 1)
        require(schema in 1..PROFILE_SCHEMA) { "Unbekannte Profilversion: $schema" }
        val configuration = root.optJSONObject("configuration") ?: root
        return save(model, configurationFromJson(configuration))
    }

    private fun key(model: AiModel): String = "profile_${model.id}"

    private fun removeLegacyWorkingProfile(model: AiModel) {
        preferences.edit().remove("working_profile_${model.id}").apply()
    }
}

private fun configurationToJson(value: LocalAiConfiguration): JSONObject = JSONObject()
    .put("contextSize", value.contextSize)
    .put("generationThreads", value.generationThreads)
    .put("promptThreads", value.promptThreads)
    .put("batchSize", value.batchSize)
    .put("microBatchSize", value.microBatchSize)
    .put("maximumOutputTokens", value.maximumOutputTokens)
    .put("flashAttention", value.flashAttention.name)
    .put("loadMode", value.loadMode.name)
    .put("backend", value.backend.name)
    .put("cpuBackend", value.cpuBackend.name)
    .put("gpuDeviceIndex", value.gpuDeviceIndex)
    .put("gpuLayers", value.gpuLayers)
    .put("gpuLayerPercent", value.gpuLayerPercent)
    .put("offloadKqv", value.offloadKqv)
    .put("offloadOperations", value.offloadOperations)
    .put("automaticCpuFallback", value.automaticCpuFallback)
    .put("cpuCoreMask", value.cpuCoreMask)
    .put("strictCpuPlacement", value.strictCpuPlacement)
    .put("threadPriority", value.threadPriority.name)
    .put("threadPollingPercent", value.threadPollingPercent)
    .put("kleidiSmeUnits", value.kleidiSmeUnits)
    .put("kleidiChunkMultiplier", value.kleidiChunkMultiplier)
    .put("minimumFreeMemoryMb", value.minimumFreeMemoryMb)
    .put("maximumMemoryPercent", value.maximumMemoryPercent)
    .put("maximumVulkanMemoryPercent", value.maximumVulkanMemoryPercent)
    .put("thermalWarningStatus", value.thermalWarningStatus)
    .put("thermalThrottleStatus", value.thermalThrottleStatus)
    .put("thermalStopStatus", value.thermalStopStatus)
    .put("throttledThreads", value.throttledThreads)
    .put("gpuLayersReducedPerStep", value.gpuLayersReducedPerStep)
    .put("coolingPauseSeconds", value.coolingPauseSeconds)
    .put("benchmarkWarmupRuns", value.benchmarkWarmupRuns)
    .put("benchmarkMeasuredRuns", value.benchmarkMeasuredRuns)
    .put("benchmarkPromptCharacters", value.benchmarkPromptCharacters)
    .put("benchmarkOutputTokens", value.benchmarkOutputTokens)
    .put("benchmarkPauseSeconds", value.benchmarkPauseSeconds)
    .put("benchmarkMinimumBatteryPercent", value.benchmarkMinimumBatteryPercent)
    .put("benchmarkRequiresCharging", value.benchmarkRequiresCharging)
    .put("benchmarkMaximumThermalStatus", value.benchmarkMaximumThermalStatus)

private fun configurationFromJson(json: JSONObject): LocalAiConfiguration {
    val defaults = LocalAiConfiguration()
    return LocalAiConfiguration(
        contextSize = json.optInt("contextSize", defaults.contextSize),
        generationThreads = json.optInt("generationThreads", defaults.generationThreads),
        promptThreads = json.optInt("promptThreads", defaults.promptThreads),
        batchSize = json.optInt("batchSize", defaults.batchSize),
        microBatchSize = json.optInt("microBatchSize", defaults.microBatchSize),
        maximumOutputTokens = json.optInt("maximumOutputTokens", defaults.maximumOutputTokens),
        flashAttention = enumValue(json, "flashAttention", defaults.flashAttention),
        loadMode = enumValue(json, "loadMode", defaults.loadMode),
        backend = enumValue(json, "backend", defaults.backend),
        cpuBackend = enumValue(json, "cpuBackend", defaults.cpuBackend),
        gpuDeviceIndex = json.optInt("gpuDeviceIndex", defaults.gpuDeviceIndex),
        gpuLayers = json.optInt("gpuLayers", defaults.gpuLayers),
        gpuLayerPercent = json.optInt("gpuLayerPercent", defaults.gpuLayerPercent),
        offloadKqv = json.optBoolean("offloadKqv", defaults.offloadKqv),
        offloadOperations = json.optBoolean("offloadOperations", defaults.offloadOperations),
        automaticCpuFallback = json.optBoolean(
            "automaticCpuFallback",
            defaults.automaticCpuFallback
        ),
        cpuCoreMask = json.optString("cpuCoreMask", defaults.cpuCoreMask),
        strictCpuPlacement = json.optBoolean(
            "strictCpuPlacement",
            defaults.strictCpuPlacement
        ),
        threadPriority = enumValue(json, "threadPriority", defaults.threadPriority),
        threadPollingPercent = json.optInt(
            "threadPollingPercent",
            defaults.threadPollingPercent
        ),
        kleidiSmeUnits = json.optInt("kleidiSmeUnits", defaults.kleidiSmeUnits),
        kleidiChunkMultiplier = json.optInt(
            "kleidiChunkMultiplier",
            defaults.kleidiChunkMultiplier
        ),
        minimumFreeMemoryMb = json.optInt("minimumFreeMemoryMb", defaults.minimumFreeMemoryMb),
        maximumMemoryPercent = json.optInt("maximumMemoryPercent", defaults.maximumMemoryPercent),
        maximumVulkanMemoryPercent = json.optInt(
            "maximumVulkanMemoryPercent",
            defaults.maximumVulkanMemoryPercent
        ),
        thermalWarningStatus = json.optInt(
            "thermalWarningStatus",
            defaults.thermalWarningStatus
        ),
        thermalThrottleStatus = json.optInt(
            "thermalThrottleStatus",
            defaults.thermalThrottleStatus
        ),
        thermalStopStatus = json.optInt("thermalStopStatus", defaults.thermalStopStatus),
        throttledThreads = json.optInt("throttledThreads", defaults.throttledThreads),
        gpuLayersReducedPerStep = json.optInt(
            "gpuLayersReducedPerStep",
            defaults.gpuLayersReducedPerStep
        ),
        coolingPauseSeconds = json.optInt("coolingPauseSeconds", defaults.coolingPauseSeconds),
        benchmarkWarmupRuns = json.optInt("benchmarkWarmupRuns", defaults.benchmarkWarmupRuns),
        benchmarkMeasuredRuns = json.optInt(
            "benchmarkMeasuredRuns",
            defaults.benchmarkMeasuredRuns
        ),
        benchmarkPromptCharacters = json.optInt(
            "benchmarkPromptCharacters",
            defaults.benchmarkPromptCharacters
        ),
        benchmarkOutputTokens = json.optInt(
            "benchmarkOutputTokens",
            defaults.benchmarkOutputTokens
        ),
        benchmarkPauseSeconds = json.optInt(
            "benchmarkPauseSeconds",
            defaults.benchmarkPauseSeconds
        ),
        benchmarkMinimumBatteryPercent = json.optInt(
            "benchmarkMinimumBatteryPercent",
            defaults.benchmarkMinimumBatteryPercent
        ),
        benchmarkRequiresCharging = json.optBoolean(
            "benchmarkRequiresCharging",
            defaults.benchmarkRequiresCharging
        ),
        benchmarkMaximumThermalStatus = json.optInt(
            "benchmarkMaximumThermalStatus",
            defaults.benchmarkMaximumThermalStatus
        )
    ).normalized()
}

private inline fun <reified T : Enum<T>> enumValue(
    json: JSONObject,
    key: String,
    fallback: T
): T = enumValues<T>().firstOrNull { it.name == json.optString(key) } ?: fallback
