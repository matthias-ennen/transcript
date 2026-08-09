package de.matthiasennen.transcript.ai

import java.io.Closeable

/**
 * Small lifecycle wrapper around llama.cpp. A model is mapped once; every call
 * receives a fresh context so transcript groups cannot leak into each other.
 */
class LocalAiEngine(
    modelPath: String,
    contextSize: Int = 8_192,
    threadCount: Int = preferredThreadCount()
) : Closeable {
    private var handle: Long = LocalAiNative.create(modelPath, contextSize, threadCount)

    init {
        check(handle != 0L) { "Das lokale KI-Modell konnte nicht geladen werden." }
    }

    fun generate(prompt: String, maximumOutputTokens: Int): String {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Der KI-Auftrag darf nicht leer sein." }
        return LocalAiNative.generate(handle, prompt, maximumOutputTokens)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Das lokale KI-Modell hat keinen verwertbaren Text erzeugt.")
    }

    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) LocalAiNative.release(activeHandle)
    }

    companion object {
        internal fun preferredThreadCount(
            availableProcessors: Int = Runtime.getRuntime().availableProcessors()
        ): Int = (availableProcessors - 2).coerceIn(2, 6)
    }
}

internal object LocalAiNative {
    init {
        System.loadLibrary("transcript_llm")
    }

    external fun create(modelPath: String, contextSize: Int, threadCount: Int): Long
    external fun generate(handle: Long, prompt: String, maximumOutputTokens: Int): String?
    external fun release(handle: Long)
}
