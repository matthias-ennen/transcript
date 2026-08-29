package de.matthiasennen.transcript.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptAnalysisPerformanceTest {
    @Test
    fun `unaccounted time exposes end to end overhead`() {
        val snapshot = snapshot(
            modelLoadMs = 1_000L,
            totalInferenceMs = 8_000L,
            totalDurationMs = 12_500L
        )

        assertEquals(3_500L, snapshot.unaccountedMs)
        assertTrue(
            aiTranscriptAnalysisPerformanceLines(snapshot)
                .first()
                .contains("sonstige Zeit 3,5 s")
        )
    }

    @Test
    fun `negative unexplained time is clamped`() {
        val snapshot = snapshot(
            modelLoadMs = 2_000L,
            totalInferenceMs = 9_000L,
            totalDurationMs = 10_000L
        )

        assertEquals(0L, snapshot.unaccountedMs)
    }

    @Test
    fun `multiple generations explain that detail metrics are last run only`() {
        val metrics = LocalAiGenerationMetrics(
            promptTokens = 335,
            generatedTokens = 62,
            chatTemplateMs = 10L,
            tokenizationMs = 20L,
            contextCreationMs = 30L,
            promptDecodeMs = 40L,
            promptProcessingMs = 93_680L,
            timeToFirstTokenMs = 93_688L,
            answerGenerationMs = 42_867L,
            totalInferenceMs = 136_555L,
            finishReason = "eos",
            thinkingDisabled = true
        )
        val lines = aiTranscriptAnalysisPerformanceLines(
            snapshot(
                generationCount = 3,
                lastGenerationMetrics = metrics
            )
        )

        assertTrue(lines.any { it.contains("335→62 Tokens") })
        assertTrue(lines.any { it.contains("letzten KI-Lauf") })
    }

    @Test
    fun `duration formatter is readable for device tests`() {
        assertEquals("999 ms", formatAiDuration(999L))
        assertEquals("1,0 s", formatAiDuration(1_000L))
        assertEquals("93,7 s", formatAiDuration(93_680L))
    }

    private fun snapshot(
        modelLoadMs: Long = 0L,
        totalInferenceMs: Long = 1_000L,
        totalDurationMs: Long = 1_200L,
        generationCount: Int = 1,
        lastGenerationMetrics: LocalAiGenerationMetrics? = null
    ) = AiTranscriptAnalysisPerformanceSnapshot(
        modelLoadMs = modelLoadMs,
        totalInferenceMs = totalInferenceMs,
        totalDurationMs = totalDurationMs,
        generationCount = generationCount,
        sourceChunkCount = 1,
        configuration = LocalAiConfiguration(),
        runtimeReport = null,
        lastGenerationMetrics = lastGenerationMetrics
    )
}
