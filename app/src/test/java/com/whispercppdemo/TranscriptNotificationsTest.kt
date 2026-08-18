package de.matthiasennen.transcript

import de.matthiasennen.transcript.download.TranscriptNotifications
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptNotificationsTest {
    @Test
    fun `background notifications have unique identifiers`() {
        val ids = listOf(
            TranscriptNotifications.WHISPER_MODEL_DOWNLOAD_ID,
            TranscriptNotifications.TRANSCRIPTION_ID,
            TranscriptNotifications.TRANSCRIPTION_COMPLETION_ID,
            TranscriptNotifications.RECORDING_ID,
            TranscriptNotifications.AI_PROCESSING_ID,
            TranscriptNotifications.VAD_MODEL_DOWNLOAD_ID,
            TranscriptNotifications.AI_MODEL_DOWNLOAD_ID
        )

        assertEquals(ids.size, ids.toSet().size)
    }
}
