package de.matthiasennen.transcript.song

import org.junit.Assert.assertEquals
import org.junit.Test

class SongSeparationPerformanceConfigurationTest {
    @Test
    fun `native gguf keeps configurable backend and thread count`() {
        val normalized = SongSeparationPerformanceConfiguration(
            threads = 6,
            backend = SongSeparationBackend.CPU
        ).normalized(
            model = SongSeparationModel.NATIVE_GGUF,
            processors = 8
        )

        assertEquals(6, normalized.threads)
        assertEquals(SongSeparationBackend.CPU, normalized.backend)
    }

    @Test
    fun `onnx models always use cpu backend`() {
        val normalized = SongSeparationPerformanceConfiguration(
            threads = 4,
            backend = SongSeparationBackend.VULKAN
        ).normalized(
            model = SongSeparationModel.BALANCED,
            processors = 8
        )

        assertEquals(4, normalized.threads)
        assertEquals(SongSeparationBackend.CPU, normalized.backend)
    }

    @Test
    fun `high quality kim keeps conservative one thread default`() {
        val defaults = defaultSongSeparationPerformance(SongSeparationModel.HIGH_QUALITY)

        assertEquals(1, defaults.threads)
        assertEquals(SongSeparationBackend.CPU, defaults.backend)
    }
}
