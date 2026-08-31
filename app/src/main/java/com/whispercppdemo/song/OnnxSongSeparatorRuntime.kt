package de.matthiasennen.transcript.song

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File

/**
 * Thin lifecycle wrapper around ONNX Runtime.
 *
 * Model-specific pre/post-processing deliberately stays outside this class so UMXHQ,
 * Spleeter and Mel-Band RoFormer can share one Android runtime without pretending that
 * their tensor contracts are identical.
 */
internal class OnnxSongSeparatorRuntime private constructor(
    private val session: OrtSession
) : Closeable {
    val inputNames: Set<String>
        get() = session.inputNames

    val outputNames: Set<String>
        get() = session.outputNames

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
            return try {
                OnnxSongSeparatorRuntime(
                    OrtEnvironment.getEnvironment().createSession(modelFile.absolutePath, options)
                )
            } finally {
                options.close()
            }
        }
    }
}
