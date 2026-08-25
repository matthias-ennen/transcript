package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiSelfTestMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDiagnosticsStateTest {
    @Test
    fun `new and reset conversation show the fixed welcome message`() {
        assertEquals(
            "Ich bin bereit. Was möchtest du ausprobieren?",
            aiDiagnosticsResponseText(showWelcome = true, modelResponse = null)
        )
        assertEquals(
            "Ich bin bereit. Was möchtest du ausprobieren?",
            aiDiagnosticsResponseText(showWelcome = true, modelResponse = "alte Antwort")
        )
    }

    @Test
    fun `real model response replaces welcome without becoming a fallback`() {
        assertEquals(
            "Echte Modellantwort",
            aiDiagnosticsResponseText(
                showWelcome = false,
                modelResponse = "Echte Modellantwort"
            )
        )
        assertEquals("", aiDiagnosticsResponseText(showWelcome = false, modelResponse = null))
    }

    @Test
    fun `preload starts only for installed idle model without matching session`() {
        assertEquals(
            AiDiagnosticsPreloadDecision.START,
            aiDiagnosticsPreloadDecision(
                modelInstalled = true,
                operationActive = false,
                matchingSessionLoaded = false
            )
        )
        assertEquals(
            AiDiagnosticsPreloadDecision.ALREADY_LOADED,
            aiDiagnosticsPreloadDecision(true, false, true)
        )
        assertEquals(
            AiDiagnosticsPreloadDecision.MODEL_MISSING,
            aiDiagnosticsPreloadDecision(false, false, false)
        )
        assertEquals(
            AiDiagnosticsPreloadDecision.OPERATION_ACTIVE,
            aiDiagnosticsPreloadDecision(true, true, false)
        )
    }

    @Test
    fun `completed response clears prompt while failed response keeps it`() {
        assertEquals("", aiDiagnosticsPromptAfterResult("Neue Frage", successful = true))
        assertEquals(
            "Neue Frage",
            aiDiagnosticsPromptAfterResult("Neue Frage", successful = false)
        )
    }

    @Test
    fun `thermal marker accepts only official Android status range`() {
        assertEquals(0, normalizeAiDiagnosticsThermalStatus(0))
        assertEquals(6, normalizeAiDiagnosticsThermalStatus(6))
        assertEquals(null, normalizeAiDiagnosticsThermalStatus(-1))
        assertEquals(null, normalizeAiDiagnosticsThermalStatus(7))
        assertEquals(null, normalizeAiDiagnosticsThermalStatus(null))
    }

    @Test
    fun `prompt can be typed while loading but sent only after ready`() {
        assertFalse(
            canSendAiDiagnosticsRequest(
                modelInstalled = true,
                modelReady = false,
                modelPreloading = true,
                operationActive = true,
                prompt = "Test"
            )
        )
        assertTrue(
            canSendAiDiagnosticsRequest(
                modelInstalled = true,
                modelReady = true,
                modelPreloading = false,
                operationActive = false,
                prompt = "Test"
            )
        )
        assertFalse(canSendAiDiagnosticsRequest(true, true, false, false, "   "))
    }

    @Test
    fun `timing breakdown separates native and outside wall clock time`() {
        val metrics = AiSelfTestMetrics(
            modelAlreadyLoaded = true,
            conversationContinued = false,
            modelLoadMs = 0,
            cpuFallbackUsed = false,
            promptTokens = 335,
            generatedTokens = 62,
            promptProcessingMs = 93_680,
            timeToFirstTokenMs = 93_688,
            answerGenerationMs = 42_867,
            nativeInferenceMs = 136_600,
            totalMs = 299_197,
            finishReason = "eog",
            thinkingDisabled = true
        )

        assertEquals(136_547L, metrics.accountedNativeComputeMs)
        assertEquals(53L, metrics.insideNativeUnaccountedMs)
        assertEquals(162_597L, metrics.outsideNativeMs)
    }
}
