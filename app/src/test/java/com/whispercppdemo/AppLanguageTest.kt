package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun `stored values select german and english`() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromPreferenceValue("de"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromPreferenceValue("en"))
    }

    @Test
    fun `missing or unknown stored values default to german`() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromPreferenceValue(null))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromPreferenceValue("unknown"))
    }
}
