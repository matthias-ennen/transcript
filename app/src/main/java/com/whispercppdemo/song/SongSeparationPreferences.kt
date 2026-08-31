package de.matthiasennen.transcript.song

import android.content.Context

private const val PREFERENCES_NAME = "song_separation_preferences"
private const val SELECTED_MODEL_KEY = "selected_song_separation_model"

class SongSeparationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSelectedModel(): SongSeparationModel =
        SongSeparationModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null))

    fun saveSelectedModel(model: SongSeparationModel) {
        preferences.edit().putString(SELECTED_MODEL_KEY, model.id).apply()
    }
}
