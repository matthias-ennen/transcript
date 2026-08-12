package de.matthiasennen.transcript.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

class AudioDecoderLimitsTest {
    @Test
    fun `watchdog accepts regular decoder progress`() {
        val watchdog = DecoderProgressWatchdog(startedAtMs = 0L, stallTimeoutMs = 100L, maxIdleCycles = 5)
        watchdog.recordProgress(nowMs = 50L, inputQueued = true)

        assertNull(watchdog.recordIdle(nowMs = 120L))
    }

    @Test
    fun `watchdog detects elapsed stall time`() {
        val watchdog = DecoderProgressWatchdog(startedAtMs = 0L, stallTimeoutMs = 100L, maxIdleCycles = 50)

        val stall = watchdog.recordIdle(nowMs = 100L)

        assertNotNull(stall)
        assertEquals(100L, stall?.idleDurationMs)
    }

    @Test
    fun `watchdog detects bounded idle cycles`() {
        val watchdog = DecoderProgressWatchdog(startedAtMs = 0L, stallTimeoutMs = 10_000L, maxIdleCycles = 2)

        assertNull(watchdog.recordIdle(nowMs = 1L))
        assertNotNull(watchdog.recordIdle(nowMs = 2L))
    }

    @Test
    fun `decoder stall receives exactly one restart`() {
        var calls = 0
        var restarts = 0

        val result = withSingleDecoderRestart(onRestart = { restarts++ }) { attempt ->
            calls++
            if (attempt == 0) throw testStall()
            "decoded"
        }

        assertEquals("decoded", result)
        assertEquals(2, calls)
        assertEquals(1, restarts)
    }

    @Test
    fun `second decoder stall is final`() {
        var calls = 0
        var restarts = 0

        assertThrows(AudioDecoderStallException::class.java) {
            withSingleDecoderRestart(onRestart = { restarts++ }) {
                calls++
                throw testStall()
            }
        }

        assertEquals(2, calls)
        assertEquals(1, restarts)
    }

    @Test
    fun `cancellation is never restarted`() {
        var calls = 0
        var restarts = 0

        assertThrows(CancellationException::class.java) {
            withSingleDecoderRestart(onRestart = { restarts++ }) {
                calls++
                throw CancellationException("cancelled")
            }
        }

        assertEquals(1, calls)
        assertEquals(0, restarts)
    }

    @Test
    fun `requested sample count matches a 16 kHz section`() {
        assertEquals(2_400_000, targetSampleCount(150_000L))
        assertEquals(4_864_000, targetSampleCount(304_000L))
    }

    @Test
    fun `decoder capacity adds a fixed five second margin`() {
        assertEquals(2_480_000, decoderOutputCapacity(2_400_000))
    }

    @Test
    fun `decoder overhang is trimmed to requested section`() {
        val decoded = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)

        val result = trimDecoderSamples(decoded, requestedSamples = 4)

        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), result.samples, 0f)
        assertEquals(2, result.discardedTrailingSamples)
    }

    @Test
    fun `decoder result without overhang is reused`() {
        val decoded = floatArrayOf(1f, 2f, 3f)

        val result = trimDecoderSamples(decoded, requestedSamples = 4)

        assertSame(decoded, result.samples)
        assertEquals(0, result.discardedTrailingSamples)
    }

    private fun testStall() = AudioDecoderStallException(
        mimeType = "audio/test",
        startMs = 0L,
        endMs = 1_000L,
        snapshot = DecoderStallSnapshot(100L, 10, 1, 0, null)
    )
}
