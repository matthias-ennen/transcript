package de.matthiasennen.transcript.ai

import android.content.Context

enum class AiPostProcessingStrategy(val displayLabel: String) {
    SEGMENTWISE("Segmentweise"),
    SECTIONWISE("Abschnittsweise")
}

class AiPostProcessingStrategyPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(
        "ai_postprocessing_strategy_v1",
        Context.MODE_PRIVATE
    )

    fun load(): AiPostProcessingStrategy = runCatching {
        AiPostProcessingStrategy.valueOf(
  preferences.getString(KEY_STRATEGY, null) ?: DEFAULT_STRATEGY.name
        )
    }.getOrDefault(DEFAULT_STRATEGY)

    fun save(strategy: AiPostProcessingStrategy) {
        preferences.edit().putString(KEY_STRATEGY, strategy.name).apply()
    }

    private companion object {
        const val KEY_STRATEGY = "strategy"
        val DEFAULT_STRATEGY = AiPostProcessingStrategy.SEGMENTWISE
    }
}
