package de.matthiasennen.transcript

import de.matthiasennen.transcript.transcription.TRANSCRIPTION_COMPLETE_TEXT
import de.matthiasennen.transcript.transcription.TRANSCRIPTION_COMPLETE_TITLE
import de.matthiasennen.transcript.transcription.completedTranscriptionNotificationContent
import de.matthiasennen.transcript.transcription.shouldPublishCompletionNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionNotificationPolicyTest {
    @Test
    fun `completion notification is neutral and exact`() {
        val content = completedTranscriptionNotificationContent()
        assertEquals("Transkription abgeschlossen", TRANSCRIPTION_COMPLETE_TITLE)
        assertEquals("Das Transkript steht bereit.", TRANSCRIPTION_COMPLETE_TEXT)
        assertEquals(TRANSCRIPTION_COMPLETE_TITLE, content.title)
        assertEquals(TRANSCRIPTION_COMPLETE_TEXT, content.text)
    }

    @Test
    fun `completion is emitted once per job generation`() {
        assertTrue(shouldPublishCompletionNotification(null, "job-42"))
        assertTrue(shouldPublishCompletionNotification("job-41", "job-42"))
        assertFalse(shouldPublishCompletionNotification("job-42", "job-42"))
        assertFalse(shouldPublishCompletionNotification("job-42", ""))
    }
}
