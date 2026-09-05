package com.whispercpp.whisper

import androidx.annotation.Keep

/**
 * Minimal JNI bridge for Transcript's native Kim Vocal 2 / GGUF separator.
 * CrispASR itself is loaded lazily by the native shim so normal Whisper use
 * does not pull the source-separation runtime into memory.
 */
@Keep
object CrispSongSeparatorNative {
    init {
        System.loadLibrary("transcript_song_native")
    }

    @Keep
    external fun open(modelPath: String, threads: Int, preferGpu: Boolean): Long

    @Keep
    external fun sampleRate(sessionPtr: Long): Int

    @Keep
    external fun separateVocals(sessionPtr: Long, interleavedStereo: FloatArray): FloatArray?

    @Keep
    external fun close(sessionPtr: Long)

    @Keep
    external fun lastError(): String
}
