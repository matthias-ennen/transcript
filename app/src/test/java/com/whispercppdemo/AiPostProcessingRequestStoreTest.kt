package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AiPostProcessingRequestStoreTest {
    @Test
    fun persistedRequestKeepsFrozenSectionMinutes() {
        val directory = createTempDir(prefix = "ai-request-")
        try {
            val store = AiPostProcessingRequestStore(File(directory, "request.bin"))
            val request = AiPostProcessingRequest(
                mode = AiPostProcessingMode.MANUAL_GROUP,
                modelId = "qwen35-2b-q4km",
                fileName = "test.wav",
                groupStartMs = 240_000L,
                segments = listOf(WhisperSegment(250_000L, 260_000L, "Test")),
                sectionMinutes = 2
            )

            store.write(request)
            val restored = requireNotNull(store.read())

            assertEquals(2, restored.sectionMinutes)
            assertEquals(240_000L, restored.groupStartMs)
            assertEquals(request.segments, restored.segments)
        } finally {
            directory.deleteRecursively()
        }
    }
}
