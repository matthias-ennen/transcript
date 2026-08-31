package de.matthiasennen.transcript.song

import android.content.Context

enum class TranscriptionMode(val label: String) {
    SPEECH("Sprache"),
    SONG("Song")
}

private const val PREFERENCES_NAME = "transcription_mode_preferences"
private const val MANUAL_MODE_KEY = "manual_transcription_mode"

class TranscriptionModePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadManualMode(): TranscriptionMode = runCatching {
        TranscriptionMode.valueOf(preferences.getString(MANUAL_MODE_KEY, null).orEmpty())
    }.getOrDefault(TranscriptionMode.SPEECH)

    fun saveManualMode(mode: TranscriptionMode) {
        preferences.edit().putString(MANUAL_MODE_KEY, mode.name).apply()
    }
}
