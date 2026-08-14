package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.WhisperSettingsPage
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStatusMessageTest {
    @Test
    fun `settings pages own their exact saved and reset messages`() {
        assertEquals(
            "Whisper-Einstellungen gespeichert.",
            WhisperSettingsPage.WHISPER.savedMessage
        )
        assertEquals(
            "Whisper-Einstellungen auf Standard zurückgesetzt.",
            WhisperSettingsPage.WHISPER.resetMessage
        )
        assertEquals("VAD-Einstellungen gespeichert.", WhisperSettingsPage.VAD.savedMessage)
        assertEquals(
            "VAD-Einstellungen auf Standard zurückgesetzt.",
            WhisperSettingsPage.VAD.resetMessage
        )
    }
}
