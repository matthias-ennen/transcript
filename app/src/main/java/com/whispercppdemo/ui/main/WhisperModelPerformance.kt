package de.matthiasennen.transcript.ui.main

/**
 * Static, model-only guidance for the central settings page. It intentionally does not
 * inspect hardware or decoder settings, because it is a simple orientation hint.
 */
internal fun WhisperModel.settingsPerformanceMessage(): String = when (this) {
    WhisperModel.TINY,
    WhisperModel.BASE ->
        "Leistungsbedarf: niedrig · Das gewählte Modell arbeitet vergleichsweise schnell."
    WhisperModel.SMALL_Q5_1 ->
        "Leistungsbedarf: mittel · Laufzeit und Gerätewärme können steigen."
    WhisperModel.LARGE_V3_TURBO_Q5_0,
    WhisperModel.LARGE_V3_Q5_0 ->
        "Leistungsbedarf: hoch · Das gewählte Modell kann das Gerät stark belasten."
}
