package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.settingsPerformanceMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperModelPerformanceTest {
    @Test
    fun `performance message depends only on the selected whisper model`() {
        assertEquals(
            "Leistungsbedarf: niedrig · Das gewählte Modell arbeitet vergleichsweise schnell.",
            WhisperModel.TINY.settingsPerformanceMessage()
        )
        assertEquals(
            "Leistungsbedarf: niedrig · Das gewählte Modell arbeitet vergleichsweise schnell.",
            WhisperModel.BASE.settingsPerformanceMessage()
        )
        assertEquals(
            "Leistungsbedarf: mittel · Laufzeit und Gerätewärme können steigen.",
            WhisperModel.SMALL_Q5_1.settingsPerformanceMessage()
        )
        assertEquals(
            "Leistungsbedarf: hoch · Das gewählte Modell kann das Gerät stark belasten.",
            WhisperModel.LARGE_V3_TURBO_Q5_0.settingsPerformanceMessage()
        )
        assertEquals(
            "Leistungsbedarf: hoch · Das gewählte Modell kann das Gerät stark belasten.",
            WhisperModel.LARGE_V3_Q5_0.settingsPerformanceMessage()
        )
    }
}
