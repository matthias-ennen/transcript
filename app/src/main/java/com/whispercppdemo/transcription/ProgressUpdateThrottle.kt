package de.matthiasennen.transcript.transcription

/** Keeps high-frequency native progress callbacks away from Android UI/notification rendering. */
internal class ProgressUpdateThrottle(
    private val minimumIntervalMs: Long
) {
    private var lastPublishedAtMs = Long.MIN_VALUE
    private var lastSignature: String? = null

    fun shouldPublish(
        nowMs: Long,
        signature: String,
        force: Boolean = false
    ): Boolean {
        if (signature == lastSignature) return false
        val intervalElapsed = lastPublishedAtMs == Long.MIN_VALUE ||
            nowMs - lastPublishedAtMs >= minimumIntervalMs
        if (!force && !intervalElapsed) return false
        lastPublishedAtMs = nowMs
        lastSignature = signature
        return true
    }
}
