package de.matthiasennen.transcript

import de.matthiasennen.transcript.transcription.ProgressUpdateThrottle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressUpdateThrottleTest {
    @Test
    fun throttlesRapidChangesAndSuppressesDuplicates() {
        val throttle = ProgressUpdateThrottle(2_000L)

        assertTrue(throttle.shouldPublish(1_000L, "10|Dekodierung"))
        assertFalse(throttle.shouldPublish(1_250L, "11|Dekodierung"))
        assertFalse(throttle.shouldPublish(3_100L, "10|Dekodierung"))
        assertTrue(throttle.shouldPublish(3_100L, "12|Dekodierung"))
    }

    @Test
    fun forcedLifecycleUpdatesBypassTheInterval() {
        val throttle = ProgressUpdateThrottle(2_000L)

        assertTrue(throttle.shouldPublish(1_000L, "start", force = true))
        assertTrue(throttle.shouldPublish(1_001L, "done", force = true))
    }
}
