package de.matthiasennen.transcript

import android.net.Uri
import de.matthiasennen.transcript.media.RecordingCoordinator
import de.matthiasennen.transcript.media.RecordingOutput
import de.matthiasennen.transcript.media.RecordingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingCoordinatorTest {
    private val recordingOutput = RecordingOutput(
        uri = Uri.parse("content://test/recording-test.m4a"),
        fileName = "recording-test.m4a"
    )

    @Before
    fun setUp() {
        RecordingCoordinator.reset()
    }

    @After
    fun tearDown() {
        RecordingCoordinator.reset()
    }

    @Test
    fun `stop transition is accepted exactly once`() {
        RecordingCoordinator.update(RecordingState.Starting)
        assertTrue(RecordingCoordinator.updateRunning(running(elapsedSeconds = 2L)))

        assertTrue(RecordingCoordinator.beginStopping())
        assertFalse(RecordingCoordinator.beginStopping())
        assertSame(RecordingState.Stopping, RecordingCoordinator.state.value)
    }

    @Test
    fun `late meter callback cannot revive stopping recording`() {
        RecordingCoordinator.update(RecordingState.Starting)
        assertTrue(RecordingCoordinator.beginStopping())

        assertFalse(RecordingCoordinator.updateRunning(running(elapsedSeconds = 3L)))
        assertSame(RecordingState.Stopping, RecordingCoordinator.state.value)
    }

    @Test
    fun `completed recording cannot be overwritten by meter callback`() {
        RecordingCoordinator.update(RecordingState.Starting)
        assertTrue(RecordingCoordinator.beginStopping())
        RecordingCoordinator.update(RecordingState.Completed(recordingOutput))

        assertFalse(RecordingCoordinator.updateRunning(running(elapsedSeconds = 4L)))
        assertEquals(RecordingState.Completed(recordingOutput), RecordingCoordinator.state.value)
    }

    @Test
    fun `reset starts a new independent recording generation`() {
        RecordingCoordinator.update(RecordingState.Starting)
        assertTrue(RecordingCoordinator.beginStopping())
        RecordingCoordinator.reset()
        RecordingCoordinator.update(RecordingState.Starting)

        assertTrue(RecordingCoordinator.updateRunning(running(elapsedSeconds = 1L)))
        assertTrue(RecordingCoordinator.state.value is RecordingState.Running)
    }

    private fun running(elapsedSeconds: Long) = RecordingState.Running(
        output = recordingOutput,
        startedAtEpochMs = 1_000L,
        elapsedSeconds = elapsedSeconds,
        amplitude = 0.5f
    )
}
