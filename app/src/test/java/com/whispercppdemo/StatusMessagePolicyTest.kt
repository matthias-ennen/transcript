package de.matthiasennen.transcript

import de.matthiasennen.transcript.ui.main.IMPORTANT_STATUS_VISIBLE_MS
import de.matthiasennen.transcript.ui.main.StatusMessageKind
import de.matthiasennen.transcript.ui.main.TranscriptUiState
import de.matthiasennen.transcript.ui.main.runtimeStatus
import de.matthiasennen.transcript.ui.main.shouldReplaceVisibleStatus
import de.matthiasennen.transcript.ui.main.statusMinimumVisibleMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusMessagePolicyTest {
    @Test
    fun `important status keeps one full pulse against progress`() {
        assertEquals(
            IMPORTANT_STATUS_VISIBLE_MS,
            statusMinimumVisibleMs(StatusMessageKind.IMPORTANT)
        )
        assertFalse(
            shouldReplaceVisibleStatus(
                visibleKind = StatusMessageKind.IMPORTANT,
                incomingKind = StatusMessageKind.PROGRESS,
                visibleUntilMs = 4_600L,
                nowMs = 2_000L
            )
        )
        assertTrue(
            shouldReplaceVisibleStatus(
                visibleKind = StatusMessageKind.IMPORTANT,
                incomingKind = StatusMessageKind.PROGRESS,
                visibleUntilMs = 4_600L,
                nowMs = 4_600L
            )
        )
    }

    @Test
    fun `terminal messages preempt held important status`() {
        assertTrue(
            shouldReplaceVisibleStatus(
                StatusMessageKind.IMPORTANT,
                StatusMessageKind.ERROR,
                visibleUntilMs = 10_000L,
                nowMs = 1_000L
            )
        )
        assertTrue(
            shouldReplaceVisibleStatus(
                StatusMessageKind.IMPORTANT,
                StatusMessageKind.COMPLETION,
                visibleUntilMs = 10_000L,
                nowMs = 1_000L
            )
        )
    }

    @Test
    fun `runtime is part of the shared status line`() {
        assertEquals(
            "Laufzeit: 03:42 (≈ 05:00)",
            TranscriptUiState(
                isTranscribing = true,
                elapsedSeconds = 222L,
                transcriptionEstimateSeconds = 300L
            ).runtimeStatus()
        )
        assertEquals(
            "Gesamtlaufzeit: 04:12",
            TranscriptUiState(transcriptionDurationSeconds = 252L).runtimeStatus()
        )
    }
}
