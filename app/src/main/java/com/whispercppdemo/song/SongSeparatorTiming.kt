package de.matthiasennen.transcript.song

internal const val SONG_SEPARATOR_STEP_MS = 8_000L
internal const val SONG_SEPARATOR_WINDOW_MS = 11_000L
internal const val NATIVE_GGUF_SEPARATOR_STEP_MS = 6_000L
internal const val NATIVE_GGUF_SEPARATOR_WINDOW_MS = 8_000L
internal const val NATIVE_GGUF_SAMPLES_PER_CHANNEL = SONG_SAMPLE_RATE * 8

internal data class SongSeparatorTiming(
    val windowMs: Long,
    val stepMs: Long,
    val inputFrames44100: Int
)

internal fun songSeparatorTiming(model: SongSeparationModel): SongSeparatorTiming =
    if (model == SongSeparationModel.NATIVE_GGUF) {
        SongSeparatorTiming(
            windowMs = NATIVE_GGUF_SEPARATOR_WINDOW_MS,
            stepMs = NATIVE_GGUF_SEPARATOR_STEP_MS,
            inputFrames44100 = NATIVE_GGUF_SAMPLES_PER_CHANNEL
        )
    } else {
        SongSeparatorTiming(
            windowMs = SONG_SEPARATOR_WINDOW_MS,
            stepMs = SONG_SEPARATOR_STEP_MS,
            inputFrames44100 = KIM_SAMPLES_PER_CHANNEL
        )
    }
