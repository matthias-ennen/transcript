package de.matthiasennen.transcript.song

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Small lifecycle wrapper around ONNX Runtime. Separator-specific tensor packing
 * stays outside this class; all three models can share the same bounded runtime.
 */
internal class OnnxSongSeparatorRuntime private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession
) : Closeable {
    val inputNames: Set<String>
        get() = session.inputNames

    val outputNames: Set<String>
        get() = session.outputNames

    fun runFloat(
        input: FloatArray,
        shape: LongArray,
        inputName: String = inputNames.single()
    ): FloatArray {
        require(inputName in inputNames) { "Unbekannter ONNX-Eingang: $inputName" }
        require(shape.fold(1L, Long::times) == input.size.toLong()) {
            "ONNX-Tensorform passt nicht zur Eingabe."
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                check(result.size() > 0) { "Das Separator-Modell lieferte keine Ausgabe." }
                val output = result[0] as? OnnxTensor
                    ?: error("Das Separator-Modell lieferte keinen Float-Tensor.")
                val buffer = output.floatBuffer
                    ?: error("Die Separator-Ausgabe kann nicht als Float gelesen werden.")
                return FloatArray(buffer.remaining()).also(buffer::get)
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        fun open(modelFile: File, intraOpThreads: Int): OnnxSongSeparatorRuntime {
            require(modelFile.isFile) { "Separator-Modell fehlt: ${modelFile.name}" }
            val safeThreads = intraOpThreads.coerceIn(1, 8)
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(safeThreads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val environment = OrtEnvironment.getEnvironment()
            return try {
                OnnxSongSeparatorRuntime(
                    environment = environment,
                    session = environment.createSession(modelFile.absolutePath, options)
                )
            } finally {
                options.close()
            }
        }
    }
}
