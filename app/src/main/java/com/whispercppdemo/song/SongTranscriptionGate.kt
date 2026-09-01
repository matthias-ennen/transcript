package de.matthiasennen.transcript.song

/**
 * Product gate used before a transcription starts.
 * Speech never depends on optional song models. Song mode requires the currently
 * selected separator to be installed; there is deliberately no silent fallback.
 */
internal fun canStartTranscriptionMode(
    mode: TranscriptionMode,
    selectedSongModelInstalled: Boolean
): Boolean = mode == TranscriptionMode.SPEECH || selectedSongModelInstalled
