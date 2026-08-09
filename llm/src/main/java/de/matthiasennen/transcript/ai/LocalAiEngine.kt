package de.matthiasennen.transcript.ai

import java.io.Closeable

/**
 * Small lifecycle wrapper around llama.cpp. The model is mapped once. Free test
 * prompts use an isolated context; transcript correction keeps one group context
 * cached and resets to that exact base before every target segment.
 */
class LocalAiEngine(
    modelPath: String,
    contextSize: Int = 4_096,
    threadCount: Int = preferredThreadCount()
) : Closeable {
    private var handle: Long = LocalAiNative.create(modelPath, contextSize, threadCount)

    init {
        check(handle != 0L) { "Das lokale KI-Modell konnte nicht geladen werden." }
    }

    private fun generate(prompt: String, maximumOutputTokens: Int): String {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Der KI-Auftrag darf nicht leer sein." }
        return LocalAiNative.generate(handle, prompt, maximumOutputTokens)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("Das lokale KI-Modell hat keinen verwertbaren Text erzeugt.")
    }

    fun generateTest(prompt: String): String = generate(
        prompt = "[[FREE_TEST]]$prompt",
        maximumOutputTokens = 512
    )

    fun prepareCorrectionContext(prompt: String) {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Der Gesprächskontext darf nicht leer sein." }
        check(LocalAiNative.prepareCorrectionContext(handle, prompt)) {
            "Der gemeinsame Gesprächskontext konnte nicht vorbereitet werden."
        }
    }

    fun correctSegment(prompt: String, maximumOutputTokens: Int): String {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Das Zielsegment darf nicht leer sein." }
        return LocalAiNative.generateCorrection(handle, prompt, maximumOutputTokens)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: "{\"result\":\"\"}"
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
    external fun prepareCorrectionContext(handle: Long, prompt: String): Boolean
    external fun generateCorrection(
        handle: Long,
        prompt: String,
        maximumOutputTokens: Int
    ): String?
    external fun release(handle: Long)
}
