package de.matthiasennen.transcript

import de.matthiasennen.transcript.transcription.TranscriptionWakeLockGuard
import de.matthiasennen.transcript.transcription.WakeLockHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionWakeLockGuardTest {
    @Test
    fun acquiresOnceForRepeatedStartAndReleasesIdempotently() {
        val handle = FakeWakeLockHandle()
        val guard = TranscriptionWakeLockGuard(handle, timeoutMs = 12_345L)

        guard.acquire()
        guard.acquire()

        assertTrue(handle.isHeld)
        assertEquals(listOf(12_345L), handle.acquireTimeouts)

        guard.release()
        guard.release()

        assertFalse(handle.isHeld)
        assertEquals(1, handle.releaseCount)
    }

    @Test
    fun renewsOnlyAnAcceptedRunAndUsesTheSafetyTimeout() {
        val handle = FakeWakeLockHandle()
        val guard = TranscriptionWakeLockGuard(handle, timeoutMs = 60_000L)

        guard.renew()
        assertTrue(handle.acquireTimeouts.isEmpty())

        guard.acquire()
        guard.renew()

        assertEquals(listOf(60_000L, 60_000L), handle.acquireTimeouts)
    }

    @Test
    fun canAcquireAgainAfterTerminalRelease() {
        val handle = FakeWakeLockHandle()
        val guard = TranscriptionWakeLockGuard(handle, timeoutMs = 42L)

        guard.acquire()
        guard.release()
        guard.acquire()

        assertTrue(handle.isHeld)
        assertEquals(listOf(42L, 42L), handle.acquireTimeouts)
    }

    private class FakeWakeLockHandle : WakeLockHandle {
        override var isHeld: Boolean = false
            private set
        val acquireTimeouts = mutableListOf<Long>()
        var releaseCount = 0
            private set

        override fun acquire(timeoutMs: Long) {
            acquireTimeouts += timeoutMs
            isHeld = true
        }

        override fun release() {
            check(isHeld)
            releaseCount += 1
            isHeld = false
        }
    }
}
