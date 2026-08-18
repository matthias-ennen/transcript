package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelInventoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `creates all model directories and reports partial whisper download`() {
        val inventory = ModelInventory(temporaryFolder.root)

        inventory.ensureDirectories()
        inventory.whisperPartialFile(WhisperModel.BASE).writeText("partial")

        assertTrue(inventory.whisperFile(WhisperModel.BASE).parentFile!!.isDirectory)
        assertTrue(inventory.vadFile().parentFile!!.isDirectory)
        assertTrue(inventory.aiFile(AiModel.BALANCED).parentFile!!.isDirectory)
        assertTrue(inventory.whisperInstallations().first { it.model == WhisperModel.BASE }.partialBytes > 0L)
    }

    @Test
    fun `deletes complete and partial model files as one operation`() {
        val inventory = ModelInventory(temporaryFolder.root)
        inventory.ensureDirectories()
        inventory.whisperFile(WhisperModel.BASE).writeText("complete")
        inventory.whisperPartialFile(WhisperModel.BASE).writeText("partial")
        inventory.aiFile(AiModel.BALANCED).writeText("complete")
        inventory.aiPartialFile(AiModel.BALANCED).writeText("partial")

        inventory.deleteWhisper(WhisperModel.BASE)
        inventory.deleteAi(AiModel.BALANCED)

        assertFalse(inventory.whisperFile(WhisperModel.BASE).exists())
        assertFalse(inventory.whisperPartialFile(WhisperModel.BASE).exists())
        assertFalse(inventory.aiFile(AiModel.BALANCED).exists())
        assertFalse(inventory.aiPartialFile(AiModel.BALANCED).exists())
    }
}
