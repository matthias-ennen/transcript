package de.matthiasennen.transcript.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Debug
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale

/**
 * Small lifecycle wrapper around ONNX Runtime. Separator-specific tensor packing
 * stays outside this class; all three models can share the same bounded runtime.
 *
 * Kim Vocal 2 uses a conservative low-memory profile on Android because the
 * model is substantially larger than the other separator stages.
 */
internal class OnnxSongSeparatorRuntime private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val lowMemoryMode: Boolean,
    private val diagnosticFile: File?
) : Closeable {
    private var firstInferenceCompleted = false

    val inputNames: Set<String>
        get() = session.inputNames

    val outputNames: Set<String>
        get() = session.outputNames

    fun inputShape(inputName: String = inputNames.single()): LongArray {
        require(inputName in inputNames) { "Unbekannter ONNX-Eingang: $inputName" }
        val tensorInfo = session.inputInfo[inputName]?.info as? TensorInfo
            ?: error("Der ONNX-Eingang $inputName ist kein Tensor.")
        return tensorInfo.shape.copyOf()
    }

    fun runFloat(
        input: FloatArray,
        shape: LongArray,
        inputName: String = inputNames.single()
    ): FloatArray {
        require(inputName in inputNames) { "Unbekannter ONNX-Eingang: $inputName" }
        require(shape.fold(1L, Long::times) == input.size.toLong()) {
            "ONNX-Tensorform passt nicht zur Eingabe."
        }

        val isFirstInference = lowMemoryMode && !firstInferenceCompleted
        if (isFirstInference) {
            logMemorySnapshot(
                stage = "before-first-inference",
                diagnosticFile = diagnosticFile,
                tensorBytes = input.size.toLong() * Float.SIZE_BYTES
            )
        }

        val values = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                check(result.size() > 0) { "Das Separator-Modell lieferte keine Ausgabe." }
                val output = result[0] as? OnnxTensor
                    ?: error("Das Separator-Modell lieferte keinen Float-Tensor.")
                val buffer = output.floatBuffer
                    ?: error("Die Separator-Ausgabe kann nicht als Float gelesen werden.")
                FloatArray(buffer.remaining()).also(buffer::get)
            }
        }

        if (isFirstInference) {
            firstInferenceCompleted = true
            logMemorySnapshot(
                stage = "after-first-inference",
                diagnosticFile = diagnosticFile,
                tensorBytes = values.size.toLong() * Float.SIZE_BYTES
            )
        }
        return values
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val KIM_MODEL_FILE = "kim-vocal-2.onnx"
        private const val KIM_DIAGNOSTIC_FILE = "kim-memory-diagnostics.log"
        private const val MEMORY_TAG = "KimVocal2Memory"

        fun open(modelFile: File, intraOpThreads: Int): OnnxSongSeparatorRuntime {
            require(modelFile.isFile) { "Separator-Modell fehlt: ${modelFile.name}" }

            val lowMemoryMode = modelFile.name == KIM_MODEL_FILE
            val safeThreads = if (lowMemoryMode) 1 else intraOpThreads.coerceIn(1, 8)
            val diagnosticFile = if (lowMemoryMode) {
                File(modelFile.parentFile, KIM_DIAGNOSTIC_FILE).also { file ->
                    runCatching {
                        if (file.exists()) file.delete()
                        file.parentFile?.mkdirs()
                    }
                }
            } else {
                null
            }

            if (lowMemoryMode) {
                logMemorySnapshot(
                    stage = "before-session-load",
                    diagnosticFile = diagnosticFile
                )
            }

            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(safeThreads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                if (lowMemoryMode) {
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                }
            }
            val environment = OrtEnvironment.getEnvironment()
            return try {
                val session = environment.createSession(modelFile.absolutePath, options)
                if (lowMemoryMode) {
                    logMemorySnapshot(
                        stage = "after-session-load",
                        diagnosticFile = diagnosticFile
                    )
                }
                OnnxSongSeparatorRuntime(
                    environment = environment,
                    session = session,
                    lowMemoryMode = lowMemoryMode,
                    diagnosticFile = diagnosticFile
                )
            } finally {
                options.close()
            }
        }

        private fun logMemorySnapshot(
            stage: String,
            diagnosticFile: File?,
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
            runCatching { diagnosticFile?.appendText("$line\n") }
        }

        private fun formatMiB(bytes: Long): String =
            String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
    }
}
