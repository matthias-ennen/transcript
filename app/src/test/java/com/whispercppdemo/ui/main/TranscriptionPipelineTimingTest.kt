package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.song.TranscriptionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionPipelineTimingTest {
    @Test
    fun `song preparation is attributed to voice isolation`() {
        assertEquals(
            TranscriptionPipelinePhase.VOICE_ISOLATION,
            transcriptionPipelinePhase(
                status = "Audio wird vorbereitet · Abschnitt 1 von 1",
                activityDetail = "Dekodierung auf PCM 16 kHz Mono; Whisper ist noch nicht geladen.",
                mode = TranscriptionMode.SONG,
                fallback = null
            )
        )
    }

    @Test
    fun `speech preparation is attributed to audio preparation`() {
        assertEquals(
            TranscriptionPipelinePhase.AUDIO_PREPARATION,
            transcriptionPipelinePhase(
                status = "Audio wird vorbereitet · Abschnitt 1 von 1",
                activityDetail = "Dekodierung auf PCM 16 kHz Mono; Whisper ist noch nicht geladen.",
                mode = TranscriptionMode.SPEECH,
                fallback = null
            )
        )
    }

    @Test
    fun `vad and whisper phases are recognized`() {
        assertEquals(
            TranscriptionPipelinePhase.VAD,
            transcriptionPipelinePhase(
                status = "VAD-Automatik analysiert Abschnitt 1 von 1",
                activityDetail = "Silero liest den vorbereiteten PCM-Abschnitt.",
                mode = TranscriptionMode.SPEECH,
                fallback = null
            )
        )
        assertEquals(
            TranscriptionPipelinePhase.WHISPER,
            transcriptionPipelinePhase(
                status = "Abschnitt 1 von 1 wird transkribiert · 35 %",
                activityDetail = "Whisper verarbeitet 00:00 bis 00:25.",
                mode = TranscriptionMode.SPEECH,
                fallback = null
            )
        )
    }

    @Test
    fun `timing accumulates and completion closes the active phase`() {
        val timing = TranscriptionPipelineTiming()
            .advanceTo(2L, TranscriptionPipelinePhase.AUDIO_PREPARATION)
            .advanceTo(4L, TranscriptionPipelinePhase.VAD)
            .advanceTo(20L, TranscriptionPipelinePhase.WHISPER)
            .complete(25L)

        assertEquals(2L, timing.audioPreparationSeconds)
        assertEquals(2L, timing.vadSeconds)
        assertEquals(21L, timing.whisperSeconds)
        assertEquals(25L, timing.totalSeconds)
        assertEquals("Whisper", timing.bottleneckLabel())
    }
}
