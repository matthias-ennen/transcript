package de.matthiasennen.transcript.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperModelTest {
    @Test
    fun onlySlowerModelsRequireCancellationConfirmation() {
        assertFalse(WhisperModel.TINY.requiresCancellationConfirmation)
        assertFalse(WhisperModel.BASE.requiresCancellationConfirmation)
        assertTrue(WhisperModel.SMALL_Q5_1.requiresCancellationConfirmation)
        assertTrue(WhisperModel.LARGE_V3_TURBO_Q5_0.requiresCancellationConfirmation)
        assertTrue(WhisperModel.LARGE_V3_Q5_0.requiresCancellationConfirmation)
    }

    @Test
    fun catalogContainsTheFiveRequestedQualityLevelsInOrder() {
        assertEquals(
            listOf(
                "Sehr schnell",
                "Schnell",
                "Ausgewogen",
                "Sehr genau",
                "Maximale Qualität"
            ),
            WhisperModel.entries.map { it.qualityLabel }
        )
    }

    @Test
    fun tinyUsesTheOfficialMultilingualModelMetadata() {
        assertEquals("ggml-tiny.bin", WhisperModel.TINY.fileName)
        assertEquals("77,7 MB", WhisperModel.TINY.downloadSizeLabel)
        assertEquals(
            "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            WhisperModel.TINY.sha256
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
