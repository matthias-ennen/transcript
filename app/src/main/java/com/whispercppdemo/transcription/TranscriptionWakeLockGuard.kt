package de.matthiasennen.transcript.transcription

internal const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1_000L
internal const val WAKE_LOCK_RENEW_INTERVAL_MS = 5L * 60L * 60L * 1_000L

internal interface WakeLockHandle {
    val isHeld: Boolean
    fun acquire(timeoutMs: Long)
    fun release()
}

/**
 * Owns exactly one non-reference-counted partial wake-lock lease for a transcription run.
 *
 * The Android wake lock itself is time-bounded. Long jobs renew the timeout before it expires;
 * every terminal service path can call [release] safely and repeatedly.
 */
internal class TranscriptionWakeLockGuard(
    private val handle: WakeLockHandle,
    private val timeoutMs: Long = WAKE_LOCK_TIMEOUT_MS
) {
    private var acceptedRun = false

    @Synchronized
    fun acquire() {
        if (acceptedRun && handle.isHeld) return
        handle.acquire(timeoutMs)
        acceptedRun = true
    }

    @Synchronized
    fun renew() {
        if (!acceptedRun) return
        handle.acquire(timeoutMs)
    }

    @Synchronized
    fun release() {
        try {
            if (handle.isHeld) handle.release()
        } finally {
            acceptedRun = false
        }
    }
}
