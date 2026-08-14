package de.matthiasennen.transcript.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedAudioStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `pcm section and complete manifest survive a process restart`() {
        val directory = temporaryFolder.newFolder("prepared")
        val store = PreparedAudioStore(directory)
        val section = TranscriptionSection(0L, 60_000L, 0L, 60_000L)
        val samples = floatArrayOf(-1f, -0.25f, 0f, 0.5f, 1f)
        val stored = store.writeSection(0, samples)
        val prepared = PreparedAudioSection(0, section, stored.first, stored.second)
        val manifest = PreparedAudioManifest(
            requestKey = "request",
            durationMs = 60_000L,
            sectionDurationMs = 60_000L,
            complete = true,
            sections = listOf(prepared),
            waveformPeaks = listOf(0.2f, 1f)
        )
        store.writeManifest(manifest)

        val reopened = PreparedAudioStore(directory)
        assertEquals(manifest, reopened.readManifest())
        assertTrue(reopened.isUsable(manifest, "request", 0L))
        val decoded = reopened.readSection(prepared)
        samples.indices.forEach { index ->
            assertEquals(samples[index], decoded[index], 0.0001f)
        }
        reopened.deleteSection(prepared)
        assertFalse(reopened.sectionExists(prepared))
    }

    @Test
    fun `storage preflight includes pcm bytes and a safety reserve`() {
        val sections = planTranscriptionSections(
            durationMs = 5 * 60_000L,
            sectionDurationMs = 60_000L,
            overlapMs = 0L
        )
        val estimated = estimatePreparedAudioBytes(sections)

        assertEquals(5L * 60L * PREPARED_SAMPLE_RATE * PREPARED_BYTES_PER_SAMPLE, estimated)
        assertTrue(requiredPreparedAudioFreeBytes(estimated) >= estimated + PREPARED_SAFETY_RESERVE_BYTES)
    }

    @Test
    fun `waveform accumulator covers the complete duration`() {
        val accumulator = PreparedWaveformAccumulator(durationMs = 2_000L, barCount = 4)
        accumulator.add(
            TranscriptionSection(0L, 2_000L, 0L, 2_000L),
            FloatArray(PREPARED_SAMPLE_RATE * 2) { index -> if (index > PREPARED_SAMPLE_RATE) 1f else 0.2f }
        )

        val peaks = accumulator.normalized()
        assertEquals(4, peaks.size)
        assertTrue(peaks.last() >= peaks.first())
    }
}
