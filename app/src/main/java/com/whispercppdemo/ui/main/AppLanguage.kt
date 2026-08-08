package de.matthiasennen.transcript.ui.main

import android.content.Context

internal enum class AppLanguage(
    val preferenceValue: String,
    val flag: String,
    val displayName: String
) {
    GERMAN(
        preferenceValue = "de",
        flag = "🇩🇪",
        displayName = "Deutsch"
    ),
    ENGLISH(
        preferenceValue = "en",
        flag = "🇬🇧",
        displayName = "English"
    );

    companion object {
        fun fromPreferenceValue(value: String?): AppLanguage =
            entries.firstOrNull { it.preferenceValue == value } ?: GERMAN
    }
}

internal object AppLanguagePreference {
    private const val PREFERENCES_NAME = "simple_transcript_ui"
    private const val LANGUAGE_KEY = "app_language"

    fun load(context: Context): AppLanguage = AppLanguage.fromPreferenceValue(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null)
    )

    fun save(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, language.preferenceValue)
            .apply()
    }
}
