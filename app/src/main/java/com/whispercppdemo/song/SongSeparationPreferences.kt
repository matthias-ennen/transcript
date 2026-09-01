package de.matthiasennen.transcript.song

import android.content.Context

private const val PREFERENCES_NAME = "song_separation_preferences"
private const val SELECTED_MODEL_KEY = "selected_song_separation_model"

/**
 * Volatile mirror used only while a new immutable worker configuration is created.
 * The encoded TranscriptionJobConfiguration remains the source of truth afterwards,
 * including service/process restarts.
 */
object SongSeparationRuntime {
    @Volatile
    var currentModel: SongSeparationModel = SongSeparationModel.BALANCED
}

class SongSeparationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSelectedModel(): SongSeparationModel =
        SongSeparationModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null)).also {
            SongSeparationRuntime.currentModel = it
        }

    fun saveSelectedModel(model: SongSeparationModel) {
        SongSeparationRuntime.currentModel = model
        preferences.edit().putString(SELECTED_MODEL_KEY, model.id).apply()
    }
}
