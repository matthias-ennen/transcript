package de.matthiasennen.transcript.transcription

import org.junit.Assert.assertThrows
import org.junit.Test

class SequentialTranscriptionResourceGuardTest {
    @Test
    fun `decoder and inference can run in strict sequence`() {
        val guard = SequentialTranscriptionResourceGuard()

        guard.beginDecoding()
        guard.endDecoding()
        guard.beginInference()
        guard.endInference()
        guard.beginDecoding()
        guard.endDecoding()
    }

    @Test
    fun `model cannot be loaded while decoder is active`() {
        val guard = SequentialTranscriptionResourceGuard()
        guard.beginDecoding()

        assertThrows(IllegalStateException::class.java) { guard.beginInference() }
    }

    @Test
    fun `decoder cannot start while model is active`() {
        val guard = SequentialTranscriptionResourceGuard()
        guard.beginInference()

        assertThrows(IllegalStateException::class.java) { guard.beginDecoding() }
    }
}
