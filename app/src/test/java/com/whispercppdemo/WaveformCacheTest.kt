package de.matthiasennen.transcript.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WaveformCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cachedWaveformRoundTripsWithoutChangingItsResolution() {
        val cache = WaveformCache(temporaryFolder.newFolder("waveforms"))
        val key = cache.key("content://audio/42", durationMs = 900_000L, contentLength = 123L)
        val expected = CachedWaveform(
            peaks = listOf(0.1f, 0.4f, 1f, 0.2f),
            durationMs = 900_000L
        )

        cache.write(key, expected)

        assertEquals(expected, cache.read(key))
    }

    @Test
    fun differentMediaIdentityDoesNotReuseAnotherWaveform() {
        val cache = WaveformCache(temporaryFolder.newFolder("waveforms"))
        val firstKey = cache.key("content://audio/42", 900_000L, 123L)
        val changedKey = cache.key("content://audio/42", 900_000L, 124L)
        cache.write(firstKey, CachedWaveform(listOf(1f), 900_000L))

        assertNull(cache.read(changedKey))
    }
}
