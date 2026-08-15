package de.matthiasennen.transcript.ui.main

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
}
