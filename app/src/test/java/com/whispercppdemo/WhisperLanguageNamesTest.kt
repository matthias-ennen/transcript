package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.whisperLanguageDisplayName
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperLanguageNamesTest {
    @Test
    fun `supported whisper language codes are displayed in full`() {
        assertEquals("Dänisch", whisperLanguageDisplayName("da"))
        assertEquals("Baskisch", whisperLanguageDisplayName("eu"))
        assertEquals("Kantonesisch", whisperLanguageDisplayName("yue"))
        assertEquals("Deutsch", whisperLanguageDisplayName("DE"))
    }

    @Test
    fun `unknown language code remains diagnosable`() {
        assertEquals("Unbekannt (xx)", whisperLanguageDisplayName("xx"))
        assertEquals("Unbekannt", whisperLanguageDisplayName("  "))
    }
}
