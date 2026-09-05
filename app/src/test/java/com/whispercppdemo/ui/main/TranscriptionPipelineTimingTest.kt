package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.song.TranscriptionMode
import de.matthiasennen.transcript.song.TranscriptionModeRuntime
import de.matthiasennen.transcript.transcription.TranscriptionState
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionPipelineTimingTest {
    @Test
    fun `song voice isolation and audio preparation are separate phases`() {
        assertEquals(
            TranscriptionPipelinePhase.VOICE_ISOLATION,
            transcriptionPipelinePhase(
                status = "Stimmisolierung · Kim Vocal 2 · Native/GGUF",
                activityDetail = "Fenster 23 von 60 · 38 %",
                mode = TranscriptionMode.SONG,
                fallback = null
            )
        )
        assertEquals(
            TranscriptionPipelinePhase.AUDIO_PREPARATION,
            transcriptionPipelinePhase(
                status = "Audio wird vorbereitet · Abschnitt 2 von 6",
                activityDetail = "Dekodierung auf PCM 16 kHz Mono; Whisper ist noch nicht geladen.",
                mode = TranscriptionMode.SONG,
                fallback = TranscriptionPipelinePhase.VOICE_ISOLATION
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
    fun `song automatic vad exposes four user visible pipeline steps`() {
        assertEquals(
            listOf(
                TranscriptionPipelinePhase.VOICE_ISOLATION,
                TranscriptionPipelinePhase.AUDIO_PREPARATION,
                TranscriptionPipelinePhase.VAD,
                TranscriptionPipelinePhase.WHISPER
            ),
            activePipelinePhases(TranscriptionMode.SONG, WhisperVadMode.AUTOMATIC)
        )
    }

    @Test
    fun `section progress presentation keeps phase and subsection visible`() {
        val running = TranscriptionState.Running(
            fileName = "six-minutes.mp3",
            model = WhisperModel.BASE,
            progress = 2.5f / 6f,
            sectionNumber = 3,
            sectionCount = 6,
            startedAtEpochMs = 1L,
            elapsedSeconds = 10L,
            status = "Abschnitt 3 von 6 wird transkribiert · 50 %",
            activityDetail = "Whisper verarbeitet 02:00 bis 03:00.",
            diagnostics = emptyList(),
            committedSegments = emptyList(),
            detectedLanguage = null
        )

        val presented = transcriptionPipelineProgressPresentation(
            running = running,
            mode = TranscriptionMode.SONG,
            vadMode = WhisperVadMode.AUTOMATIC,
            phase = TranscriptionPipelinePhase.WHISPER
        )

        assertEquals(4, presented.phaseNumber)
        assertEquals(4, presented.phaseCount)
        assertEquals("Abschnitt 3 von 6 · 50 %", presented.detailLine)
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

    @Test
    fun `starting presentation uses active runtime mode for voice isolation`() {
        val previousMode = TranscriptionModeRuntime.current
        try {
            TranscriptionModeRuntime.current = TranscriptionMode.SONG
            val presented = TranscriptUiState(
                transcriptionMode = TranscriptionMode.SPEECH
            ).presentStartingTranscription(
                TranscriptionState.Starting("song.mp3")
            )

            assertEquals(TranscriptionMode.SONG, presented.pipelineTiming.mode)
            assertEquals(
                presented.selectedSongSeparationModel.modelLabel,
                presented.pipelineTiming.voiceIsolationModelLabel
            )
        } finally {
            TranscriptionModeRuntime.current = previousMode
        }
    }
}
