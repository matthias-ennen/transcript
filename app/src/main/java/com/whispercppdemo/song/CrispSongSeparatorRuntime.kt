package de.matthiasennen.transcript.song

import android.os.Debug
import android.util.Log
import com.whispercpp.whisper.CrispSongSeparatorNative
import java.io.Closeable
import java.io.File
import java.util.Locale

/**
 * Persistent native CrispASR session for the Kim Vocal 2 F16 GGUF variant.
 * The session is kept alive across bounded song windows and released before
 * Whisper is allowed to load, matching the existing separator lifecycle.
 */
internal class CrispSongSeparatorRuntime private constructor(
    private var sessionPtr: Long,
    private val diagnosticFile: File
) : Closeable {
    private var firstInferenceCompleted = false

    fun separateVocals(interleavedStereo44100: FloatArray): FloatArray {
        check(sessionPtr != 0L) { "Die native Kim-Session wurde bereits freigegeben." }
        require(interleavedStereo44100.isNotEmpty() && interleavedStereo44100.size % 2 == 0) {
            "Kim Vocal 2 Native/GGUF benötigt Stereo-Audio."
        }
        val isFirstInference = !firstInferenceCompleted
        if (isFirstInference) {
            logMemorySnapshot(
                stage = "native-before-first-inference",
                diagnosticFile = diagnosticFile,
                tensorBytes = interleavedStereo44100.size.toLong() * Float.SIZE_BYTES
            )
        }

        val output = CrispSongSeparatorNative.separateVocals(sessionPtr, interleavedStereo44100)
            ?: error(nativeFailure("Kim Vocal 2 Native/GGUF konnte den Audioabschnitt nicht trennen."))
        check(output.size == interleavedStereo44100.size) {
            "Kim Vocal 2 Native/GGUF lieferte ${output.size / 2} statt ${interleavedStereo44100.size / 2} Samples je Kanal."
        }

        if (isFirstInference) {
            firstInferenceCompleted = true
            logMemorySnapshot(
                stage = "native-after-first-inference",
                diagnosticFile = diagnosticFile,
                tensorBytes = output.size.toLong() * Float.SIZE_BYTES
            )
        }
        return output
    }

    override fun close() {
        val ptr = sessionPtr
        sessionPtr = 0L
        if (ptr != 0L) {
            runCatching { CrispSongSeparatorNative.close(ptr) }
                .onFailure { Log.w(MEMORY_TAG, "Native Kim session close failed", it) }
        }
    }

    companion object {
        private const val KIM_DIAGNOSTIC_FILE = "kim-memory-diagnostics.log"
        private const val MEMORY_TAG = "KimVocal2NativeMemory"
        private const val REQUIRED_SAMPLE_RATE = 44_100

        fun open(modelFile: File, requestedThreads: Int): CrispSongSeparatorRuntime {
            require(modelFile.isFile) { "Separator-Modell fehlt: ${modelFile.name}" }
            val diagnosticFile = File(modelFile.parentFile, KIM_DIAGNOSTIC_FILE).also { file ->
                runCatching {
                    if (file.exists()) file.delete()
                    file.parentFile?.mkdirs()
                }
            }
            logMemorySnapshot("native-before-session-load", diagnosticFile)

            // Memory stability comes before throughput for the first Android
            // integration. We can raise this after the Xiaomi reference run.
            val threads = requestedThreads.coerceIn(1, 1)
            val ptr = CrispSongSeparatorNative.open(modelFile.absolutePath, threads)
            check(ptr != 0L) {
                nativeFailure("Kim Vocal 2 Native/GGUF konnte nicht geladen werden.")
            }
            try {
                val sampleRate = CrispSongSeparatorNative.sampleRate(ptr)
                check(sampleRate == REQUIRED_SAMPLE_RATE) {
                    "Kim Vocal 2 Native/GGUF erwartet $sampleRate Hz statt $REQUIRED_SAMPLE_RATE Hz."
                }
                logMemorySnapshot("native-after-session-load", diagnosticFile)
                return CrispSongSeparatorRuntime(ptr, diagnosticFile)
            } catch (failure: Throwable) {
                runCatching { CrispSongSeparatorNative.close(ptr) }
                throw failure
            }
        }

        private fun nativeFailure(fallback: String): String =
            runCatching { CrispSongSeparatorNative.lastError().trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: fallback

        private fun logMemorySnapshot(
            stage: String,
            diagnosticFile: File,
            tensorBytes: Long = 0L
        ) {
            val runtime = Runtime.getRuntime()
            val javaUsed = runtime.totalMemory() - runtime.freeMemory()
            val javaCommitted = runtime.totalMemory()
            val javaMax = runtime.maxMemory()
            val nativeAllocated = Debug.getNativeHeapAllocatedSize()
            val totalPssBytes = Debug.getPss().coerceAtLeast(0L) * 1024L
            val line = buildString {
                append(stage)
                append(" | pss=")
                append(formatMiB(totalPssBytes))
                append(" MiB | javaUsed=")
                append(formatMiB(javaUsed))
                append(" MiB | javaCommitted=")
                append(formatMiB(javaCommitted))
                append(" MiB | javaMax=")
                append(formatMiB(javaMax))
                append(" MiB | nativeAllocated=")
                append(formatMiB(nativeAllocated))
                append(" MiB")
                if (tensorBytes > 0L) {
                    append(" | tensor=")
                    append(formatMiB(tensorBytes))
                    append(" MiB")
                }
            }
            Log.i(MEMORY_TAG, line)
            runCatching { diagnosticFile.appendText("$line\n") }
        }

        private fun formatMiB(bytes: Long): String =
            String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
    }
}
