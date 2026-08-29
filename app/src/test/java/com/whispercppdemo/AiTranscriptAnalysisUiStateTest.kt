package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisAction
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisResult
import de.matthiasennen.transcript.ai.AiTranscriptAnalysisState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranscriptAnalysisUiStateTest {
    @Test
    fun `running analysis participates in existing busy guards`() {
        val base = TranscriptUiState(isBusy = false, status = "Transkript fertig.")

        val rendered = base.withAiTranscriptAnalysisState(
            AiTranscriptAnalysisState.Running(
                action = AiTranscriptAnalysisAction.SUMMARY,
                model = AiModel.BALANCED,
                sourceFingerprint = "abc",
                progress = 0.4f,
                status = "KI-Auswertung läuft …",
                activityDetail = "Teil 2 von 5 wird ausgewertet."
            )
        )

        assertTrue(rendered.isBusy)
        assertTrue(rendered.isAiTranscriptAnalysisRunning)
        assertEquals(0.4f, rendered.aiTranscriptAnalysisProgress)
        assertEquals("KI-Auswertung läuft …", rendered.status)
    }

    @Test
    fun `completed analysis attaches result without overwriting newer app operation`() {
        val base = TranscriptUiState(
            isBusy = true,
            isTranscribing = true,
            status = "Transkription läuft …",
            activityDetail = "Abschnitt 3"
        )
        val result = testResult()

        val rendered = base.withAiTranscriptAnalysisState(
            AiTranscriptAnalysisState.Completed(result)
        )

        assertTrue(rendered.isBusy)
        assertTrue(rendered.isTranscribing)
        assertEquals("Transkription läuft …", rendered.status)
        assertEquals("Abschnitt 3", rendered.activityDetail)
        assertFalse(rendered.isAiTranscriptAnalysisRunning)
        assertEquals(result, rendered.aiTranscriptAnalysisResult)
    }

    @Test
    fun `starting a new analysis clears the previous result`() {
        val base = TranscriptUiState(aiTranscriptAnalysisResult = testResult())

        val rendered = base.withAiTranscriptAnalysisState(
            AiTranscriptAnalysisState.Starting(
                action = AiTranscriptAnalysisAction.TODOS,
                model = AiModel.BALANCED,
                sourceFingerprint = "new"
            )
        )

        assertTrue(rendered.isAiTranscriptAnalysisRunning)
        assertEquals(null, rendered.aiTranscriptAnalysisResult)
    }

    private fun testResult() = AiTranscriptAnalysisResult(
        action = AiTranscriptAnalysisAction.SUMMARY,
        model = AiModel.BALANCED,
        text = "Kurze Zusammenfassung",
        sourceFileName = "test.wav",
        sourceFingerprint = "abc",
        sourceChunkCount = 1,
        generationCount = 1,
        modelLoadMs = 10L,
        totalInferenceMs = 100L,
        totalDurationMs = 120L,
        cpuFallbackUsed = false
    )
}
