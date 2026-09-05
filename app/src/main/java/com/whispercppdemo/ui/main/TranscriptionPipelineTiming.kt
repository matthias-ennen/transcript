package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.song.TranscriptionMode

enum class TranscriptionPipelinePhase {
    AUDIO_PREPARATION,
    VOICE_ISOLATION,
    VAD,
    WHISPER
}

data class TranscriptionPipelineTiming(
    val mode: TranscriptionMode = TranscriptionMode.SPEECH,
    val voiceIsolationModelLabel: String? = null,
    val audioPreparationSeconds: Long = 0L,
    val voiceIsolationSeconds: Long = 0L,
    val vadSeconds: Long = 0L,
    val whisperSeconds: Long = 0L,
    val totalSeconds: Long = 0L,
    val lastElapsedSeconds: Long = 0L,
    val activePhase: TranscriptionPipelinePhase? = null
) {
    fun withContext(mode: TranscriptionMode, voiceIsolationModelLabel: String?): TranscriptionPipelineTiming =
        if (activePhase == null && lastElapsedSeconds == 0L && totalSeconds == 0L) {
            copy(
                mode = mode,
                voiceIsolationModelLabel = voiceIsolationModelLabel.takeIf {
                    mode == TranscriptionMode.SONG
                }
            )
        } else {
            this
        }

    fun advanceTo(
        elapsedSeconds: Long,
        phase: TranscriptionPipelinePhase
    ): TranscriptionPipelineTiming {
        val safeElapsed = elapsedSeconds.coerceAtLeast(lastElapsedSeconds)
        val delta = (safeElapsed - lastElapsedSeconds).coerceAtLeast(0L)
        return add(delta, phase).copy(
            totalSeconds = safeElapsed,
            lastElapsedSeconds = safeElapsed,
            activePhase = phase
        )
    }

    fun complete(totalSeconds: Long): TranscriptionPipelineTiming {
        val safeTotal = totalSeconds.coerceAtLeast(lastElapsedSeconds)
        val delta = safeTotal - lastElapsedSeconds
        val finalPhase = activePhase ?: TranscriptionPipelinePhase.WHISPER
        return add(delta, finalPhase).copy(
            totalSeconds = safeTotal,
            lastElapsedSeconds = safeTotal,
            activePhase = finalPhase
        )
    }

    fun bottleneckLabel(): String? = listOf(
        "Audioaufbereitung" to audioPreparationSeconds,
        "Stimmisolierung" to voiceIsolationSeconds,
        "VAD / Segmentierung" to vadSeconds,
        "Whisper" to whisperSeconds
    ).filter { it.second > 0L }
        .maxByOrNull { it.second }
        ?.first

    private fun add(
        seconds: Long,
        phase: TranscriptionPipelinePhase
    ): TranscriptionPipelineTiming {
        if (seconds <= 0L) return this
        return when (phase) {
            TranscriptionPipelinePhase.AUDIO_PREPARATION -> copy(
                audioPreparationSeconds = audioPreparationSeconds + seconds
            )
            TranscriptionPipelinePhase.VOICE_ISOLATION -> copy(
                voiceIsolationSeconds = voiceIsolationSeconds + seconds
            )
            TranscriptionPipelinePhase.VAD -> copy(vadSeconds = vadSeconds + seconds)
            TranscriptionPipelinePhase.WHISPER -> copy(whisperSeconds = whisperSeconds + seconds)
        }
    }
}

internal fun transcriptionPipelinePhase(
    status: String,
    activityDetail: String,
    mode: TranscriptionMode,
    fallback: TranscriptionPipelinePhase?
): TranscriptionPipelinePhase {
    val statusText = status.lowercase()
    val detailText = activityDetail.lowercase()
    return when {
        "transkrib" in statusText || "whisper" in statusText ->
            TranscriptionPipelinePhase.WHISPER
        "vad" in statusText -> TranscriptionPipelinePhase.VAD
        "stimmisolierung" in statusText || "separator" in detailText ->
            TranscriptionPipelinePhase.VOICE_ISOLATION
        "audio wird vorbereitet" in statusText || "dekodierung" in detailText ->
            TranscriptionPipelinePhase.AUDIO_PREPARATION
        fallback != null -> fallback
        mode == TranscriptionMode.SONG -> TranscriptionPipelinePhase.VOICE_ISOLATION
        else -> TranscriptionPipelinePhase.AUDIO_PREPARATION
    }
}
