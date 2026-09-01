package de.matthiasennen.transcript.song

import android.content.Context

enum class TranscriptionMode(val label: String) {
    SPEECH("Sprache"),
    SONG("Song")
}

/**
 * Current mode for the active workflow. Recording may force SPEECH here without
 * overwriting the user's persisted manual default.
 */
object TranscriptionModeRuntime {
    @Volatile
    var current: TranscriptionMode = TranscriptionMode.SPEECH
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
