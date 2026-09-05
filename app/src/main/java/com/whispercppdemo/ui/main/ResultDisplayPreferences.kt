package de.matthiasennen.transcript.ui.main

import android.content.Context

internal object ResultDisplayPreferences {
    private const val PREFERENCES_NAME = "simple_transcript_ui"
    private const val ADVANCED_DIAGNOSTICS_KEY = "advanced_transcription_diagnostics"

    fun load(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(ADVANCED_DIAGNOSTICS_KEY, false)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ADVANCED_DIAGNOSTICS_KEY, enabled)
            .apply()
    }
}
