package de.matthiasennen.transcript.ai

import android.content.Context

private const val PREFERENCES_NAME = "local_ai_preferences"
private const val ENABLED_KEY = "enabled"
private const val AUTOMATIC_KEY = "automatic"
private const val SELECTED_MODEL_KEY = "selected_model"

data class AiPreferencesSnapshot(
    val enabled: Boolean,
    val automatic: Boolean,
    val selectedModel: AiModel
)

class AiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AiPreferencesSnapshot {
        val enabled = preferences.getBoolean(ENABLED_KEY, false)
        return AiPreferencesSnapshot(
            enabled = enabled,
            automatic = enabled && preferences.getBoolean(AUTOMATIC_KEY, false),
            selectedModel = AiModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null))
        )
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(ENABLED_KEY, enabled)
            .apply()
    }

    fun setAutomatic(automatic: Boolean) {
        preferences.edit()
            .putBoolean(AUTOMATIC_KEY, automatic)
            .apply()
    }

    fun setSelectedModel(model: AiModel) {
        preferences.edit()
            .putString(SELECTED_MODEL_KEY, model.id)
            .apply()
    }
}
