package de.matthiasennen.transcript.ai

import java.io.Closeable

data class LocalAiGenerationMetrics(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptProcessingMs: Long,
    val timeToFirstTokenMs: Long,
    val answerGenerationMs: Long,
    val totalInferenceMs: Long,
    val finishReason: String,
    val thinkingDisabled: Boolean
)

data class LocalAiGenerationResult(
    val text: String,
    val metrics: LocalAiGenerationMetrics
)

/**
 * Small lifecycle wrapper around llama.cpp. The model is mapped once. Free test
 * prompts share one in-memory conversation until it is explicitly reset;
 * transcript correction keeps one group context cached and resets to that exact
 * base before every target segment.
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

    private fun generate(prompt: String, maximumOutputTokens: Int): LocalAiGenerationResult {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        require(prompt.isNotBlank()) { "Der KI-Auftrag darf nicht leer sein." }
        val values = LocalAiNative.generate(handle, prompt, maximumOutputTokens)
            ?: error(
                LocalAiNative.lastError(handle)
                    ?.takeIf(String::isNotBlank)
                    ?: "Das lokale KI-Modell hat keinen verwertbaren Text erzeugt."
            )
        check(values.size == GENERATION_RESULT_FIELD_COUNT) {
            "Die lokale KI hat unvollständige Diagnosewerte geliefert."
        }
        val text = values[0].trim().takeIf(String::isNotEmpty)
            ?: error("Das lokale KI-Modell hat keinen verwertbaren Text erzeugt.")
        return LocalAiGenerationResult(
            text = text,
            metrics = LocalAiGenerationMetrics(
                promptTokens = values[1].toIntOrNull() ?: 0,
                generatedTokens = values[2].toIntOrNull() ?: 0,
                promptProcessingMs = values[3].toLongOrNull() ?: 0L,
                timeToFirstTokenMs = values[4].toLongOrNull() ?: 0L,
                answerGenerationMs = values[5].toLongOrNull() ?: 0L,
                totalInferenceMs = values[6].toLongOrNull() ?: 0L,
                finishReason = values[7],
                thinkingDisabled = values[8].toBooleanStrictOrNull() ?: false
            )
        )
    }

    fun generateTest(prompt: String): LocalAiGenerationResult = generate(
        prompt = "[[FREE_TEST]]$prompt",
        maximumOutputTokens = 512
    )

    fun hasTestConversation(): Boolean {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        return LocalAiNative.hasTestConversation(handle)
    }

    fun resetTestConversation() {
        check(handle != 0L) { "Das lokale KI-Modell wurde bereits freigegeben." }
        LocalAiNative.resetTestConversation(handle)
    }

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
        private const val GENERATION_RESULT_FIELD_COUNT = 9

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
    external fun generate(handle: Long, prompt: String, maximumOutputTokens: Int): Array<String>?
    external fun lastError(handle: Long): String?
    external fun hasTestConversation(handle: Long): Boolean
    external fun resetTestConversation(handle: Long)
    external fun prepareCorrectionContext(handle: Long, prompt: String): Boolean
    external fun generateCorrection(
        handle: Long,
        prompt: String,
        maximumOutputTokens: Int
    ): String?
    external fun release(handle: Long)
}
