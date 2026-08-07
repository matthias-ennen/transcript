package de.matthiasennen.transcript.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelTest {
    @Test
    fun catalogContainsTheFourRequestedQualityLevelsInOrder() {
        assertEquals(
            listOf("Schnell", "Ausgewogen", "Sehr genau", "Maximale Qualität"),
            WhisperModel.entries.map { it.qualityLabel }
        )
    }

    @Test
    fun everyModelUsesTheMultilingualFileAndHasACompleteChecksum() {
        WhisperModel.entries.forEach { model ->
            assertTrue(model.fileName.startsWith("ggml-"))
            assertTrue(model.fileName.endsWith(".bin"))
            assertTrue(!model.fileName.contains(".en."))
            assertEquals(64, model.sha256.length)
            assertEquals(
                "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${model.fileName}",
                model.downloadUrl
            )
        }
    }

    @Test
    fun unknownStoredModelFallsBackToBase() {
        assertEquals(WhisperModel.BASE, WhisperModel.fromId("does-not-exist"))
    }

    @Test
    fun modelInstallationCountsInstalledAndPartialStorage() {
        val installation = ModelInstallation(
            model = WhisperModel.BASE,
            isInstalled = true,
            installedBytes = 140_000_000L,
            partialBytes = 8_000_000L
        )

        assertEquals(148_000_000L, installation.storedBytes)
    }
}
