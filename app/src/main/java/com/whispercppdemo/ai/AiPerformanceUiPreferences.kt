package de.matthiasennen.transcript.ai

import android.content.Context

private const val UI_PREFERENCES = "local_ai_performance_ui_v1"

class AiPerformanceUiPreferences(context: Context, preferencesName: String = UI_PREFERENCES) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun isExpanded(section: String): Boolean =
        preferences.getBoolean("expanded_$section", false)

    fun setExpanded(section: String, expanded: Boolean) {
        preferences.edit().putBoolean("expanded_$section", expanded).apply()
    }
}
