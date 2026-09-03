package de.matthiasennen.transcript.song

import android.os.Debug
import android.os.SystemClock
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
    private val diagnosticFile: File,
    private val threads: Int,
    private val gpuRequested: Boolean
) : Closeable {
    private var firstInferenceCompleted = false
    private var inferenceCount = 0
    private var totalInferenceMs = 0L

    fun separateVocals(interleavedStereo44100: FloatArray): FloatArray {
        check(sessionPtr != 0L) { "Die native Kim-Session wurde bereits freigegeben." }
        require(interleavedStereo44100.isNotEmpty() && interleavedStereo44100.size % 2 == 0) {
            "Kim Vocal 2 Native/GGUF benötigt Stereo-Audio."
        }

        val windowNumber = ++inferenceCount
        val framesPerChannel = interleavedStereo44100.size / 2
        val tensorBytes = interleavedStereo44100.size.toLong() * Float.SIZE_BYTES
        val isFirstInference = !firstInferenceCompleted
        if (isFirstInference) {
            logMemorySnapshot(
                stage = "native-before-first-inference",
                diagnosticFile = diagnosticFile,
                tensorBytes = tensorBytes,
                details = "threads=$threads | gpuRequested=$gpuRequested"
            )
        }
        logMemorySnapshot(
            stage = "native-window-$windowNumber-start",
            diagnosticFile = diagnosticFile,
            tensorBytes = tensorBytes,
            details = "frames=$framesPerChannel | threads=$threads | gpuRequested=$gpuRequested"
        )

        val startedAtMs = SystemClock.elapsedRealtime()
        val output = CrispSongSeparatorNative.separateVocals(sessionPtr, interleavedStereo44100)
            ?: error(nativeFailure("Kim Vocal 2 Native/GGUF konnte den Audioabschnitt nicht trennen."))
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        totalInferenceMs += elapsedMs
        check(output.size == interleavedStereo44100.size) {
            "Kim Vocal 2 Native/GGUF lieferte ${output.size / 2} statt ${interleavedStereo44100.size / 2} Samples je Kanal."
        }

        logMemorySnapshot(
            stage = "native-window-$windowNumber-finished",
            diagnosticFile = diagnosticFile,
            tensorBytes = output.size.toLong() * Float.SIZE_BYTES,
            details = "elapsedMs=$elapsedMs | frames=${output.size / 2} | threads=$threads | gpuRequested=$gpuRequested"
        )
        if (isFirstInference) {
            firstInferenceCompleted = true
            logMemorySnapshot(
                stage = "native-after-first-inference",
                diagnosticFile = diagnosticFile,
                tensorBytes = output.size.toLong() * Float.SIZE_BYTES,
                details = "elapsedMs=$elapsedMs | threads=$threads | gpuRequested=$gpuRequested"
            )
        }
        return output
    }

    override fun close() {
        val ptr = sessionPtr
        sessionPtr = 0L
        if (ptr != 0L) {
            if (inferenceCount > 0) {
                val averageMs = totalInferenceMs / inferenceCount.coerceAtLeast(1)
                logMemorySnapshot(
                    stage = "native-summary",
                    diagnosticFile = diagnosticFile,
                    details = "windows=$inferenceCount | totalInferenceMs=$totalInferenceMs | " +
                        "averageWindowMs=$averageMs | threads=$threads | gpuRequested=$gpuRequested"
                )
            }
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

            // OpenBLAS remains the CPU fallback. The pinned CrispASR runtime is
            // now also built with Vulkan; on a usable Android GPU it selects the
            // fused Mel-Band-RoFormer graph, otherwise it falls back to CPU.
            val threads = requestedThreads.coerceIn(1, 4)
            val preferGpu = true
            val ptr = CrispSongSeparatorNative.open(
                modelPath = modelFile.absolutePath,
                threads = threads,
                preferGpu = preferGpu
            )
            check(ptr != 0L) {
                nativeFailure("Kim Vocal 2 Native/GGUF konnte nicht geladen werden.")
            }
            try {
                val sampleRate = CrispSongSeparatorNative.sampleRate(ptr)
                check(sampleRate == REQUIRED_SAMPLE_RATE) {
                    "Kim Vocal 2 Native/GGUF erwartet $sampleRate Hz statt $REQUIRED_SAMPLE_RATE Hz."
                }
                logMemorySnapshot(
                    stage = "native-after-session-load",
                    diagnosticFile = diagnosticFile,
                    details = "threads=$threads | gpuRequested=$preferGpu"
                )
                return CrispSongSeparatorRuntime(
                    sessionPtr = ptr,
                    diagnosticFile = diagnosticFile,
                    threads = threads,
                    gpuRequested = preferGpu
                )
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
            tensorBytes: Long = 0L,
            details: String? = null
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
                if (!details.isNullOrBlank()) {
                    append(" | ")
                    append(details)
                }
            }
            Log.i(MEMORY_TAG, line)
            runCatching { diagnosticFile.appendText("$line\n") }
        }

        private fun formatMiB(bytes: Long): String =
            String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
    }
}
