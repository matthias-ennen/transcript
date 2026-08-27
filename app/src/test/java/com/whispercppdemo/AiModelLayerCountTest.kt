package com.whispercppdemo

import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.LocalAiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelLayerCountTest {
    @Test
    fun allSixPinnedQwen35VariantsHaveKnownLayerCounts() {
        val expected = mapOf(
            AiModel.QUICK to 24,
            AiModel.QUICK_Q8 to 24,
            AiModel.BALANCED to 24,
            AiModel.BALANCED_Q4 to 24,
            AiModel.PRECISE to 32,
            AiModel.PRECISE_Q4 to 32
        )

        assertEquals(AiModel.entries.toSet(), expected.keys)
        expected.forEach { (model, layerCount) ->
            assertEquals(
                "Unexpected layer count for ${model.fileName}",
                layerCount,
                LocalAiEngine.knownModelLayerCount(model.fileName)
            )
        }
        assertTrue(expected.values.all { it > 0 })
    }

    @Test
    fun quantizationDoesNotChangeLayerCountWithinModelFamily() {
        assertEquals(
            LocalAiEngine.knownModelLayerCount(AiModel.QUICK.fileName),
            LocalAiEngine.knownModelLayerCount(AiModel.QUICK_Q8.fileName)
        )
        assertEquals(
            LocalAiEngine.knownModelLayerCount(AiModel.BALANCED.fileName),
            LocalAiEngine.knownModelLayerCount(AiModel.BALANCED_Q4.fileName)
        )
        assertEquals(
            LocalAiEngine.knownModelLayerCount(AiModel.PRECISE.fileName),
            LocalAiEngine.knownModelLayerCount(AiModel.PRECISE_Q4.fileName)
        )
    }

    @Test
    fun unknownModelDoesNotInventLayerCount() {
        assertEquals(0, LocalAiEngine.knownModelLayerCount("unknown-model.gguf"))
    }
}
