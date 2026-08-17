package com.whispercppdemo

import de.matthiasennen.transcript.ui.main.whisperLanguageLabel
import de.matthiasennen.transcript.ui.main.whisperLanguageOptions
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLanguageCatalogTest {
    @Test
    fun keepsPreferredOrder() {
        assertEquals("auto", whisperLanguageOptions[0].first)
        assertEquals("de", whisperLanguageOptions[1].first)
        assertEquals("en", whisperLanguageOptions[2].first)
        val germanCollator = Collator.getInstance(Locale.GERMAN)
        assertEquals(
            whisperLanguageOptions.drop(3).map { it.second },
            whisperLanguageOptions.drop(3).map { it.second }.sortedWith(germanCollator)
        )
    }

    @Test
    fun exposesAllFortySupportedLanguagesAndAutomaticMode() {
        assertEquals(41, whisperLanguageOptions.size)
        assertEquals(41, whisperLanguageOptions.map { it.first }.toSet().size)
        assertTrue(whisperLanguageOptions.any { it == "fr" to "Französisch" })
        assertEquals("Automatisch – empfohlen", whisperLanguageLabel("unknown"))
    }
}
