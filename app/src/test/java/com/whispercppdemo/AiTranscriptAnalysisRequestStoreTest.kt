package de.matthiasennen.transcript.ai

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptAnalysisRequestStoreTest {
    @Test
    fun `request store supports transcript text larger than writeUTF limit`() {
        val directory = Files.createTempDirectory("transcript-ai-analysis").toFile()
        try {
            val file = directory.resolve("request.bin")
            val store = AiTranscriptAnalysisRequestStore(file)
            val source = buildString {
                repeat(20_000) { index -> append("Abschnitt-$index ") }
            }.trim()
            assertTrue(source.toByteArray(Charsets.UTF_8).size > 65_535)
            val fingerprint = aiTranscriptSourceFingerprint(source)
            val request = AiTranscriptAnalysisRequest(
                action = AiTranscriptAnalysisAction.SUMMARY,
                modelId = AiModel.BALANCED.id,
                fileName = "langes-interview.m4a",
                sourceText = source,
                sourceFingerprint = fingerprint
            )

            store.write(request)
            val restored = store.read()

            assertEquals(request, restored)
            assertTrue(file.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `clear removes active and temporary request`() {
        val directory = Files.createTempDirectory("transcript-ai-analysis-clear").toFile()
        try {
            val file = directory.resolve("request.bin")
            val temporary = directory.resolve("request.bin.tmp")
            val store = AiTranscriptAnalysisRequestStore(file)
            val source = "Kurzer Inhalt"
            store.write(
                AiTranscriptAnalysisRequest(
                    action = AiTranscriptAnalysisAction.KEY_POINTS,
                    modelId = AiModel.BALANCED.id,
                    fileName = "test.wav",
                    sourceText = source,
                    sourceFingerprint = aiTranscriptSourceFingerprint(source)
                )
            )
            temporary.writeText("alt")

            store.clear()

            assertFalse(file.exists())
            assertFalse(temporary.exists())
            assertNull(store.read())
        } finally {
            directory.deleteRecursively()
        }
    }
}
