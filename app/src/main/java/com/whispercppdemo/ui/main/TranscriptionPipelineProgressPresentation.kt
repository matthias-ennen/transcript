package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.song.TranscriptionMode
import de.matthiasennen.transcript.transcription.TranscriptionState
import kotlin.math.roundToInt

internal data class TranscriptionPipelineProgressPresentation(
    val phase: TranscriptionPipelinePhase,
    val phaseNumber: Int,
    val phaseCount: Int,
    val phaseProgress: Float,
    val statusLine: String,
    val detailLine: String
)

internal fun transcriptionPipelineProgressPresentation(
    running: TranscriptionState.Running,
    mode: TranscriptionMode,
    vadMode: WhisperVadMode,
    phase: TranscriptionPipelinePhase
): TranscriptionPipelineProgressPresentation {
    val phases = activePipelinePhases(mode, vadMode)
    val phaseIndex = phases.indexOf(phase).takeIf { it >= 0 } ?: 0
    val safeProgress = running.progress.coerceIn(0f, 1f)
    val percent = (safeProgress * 100f).roundToInt().coerceIn(0, 100)
    val phaseLabel = phase.displayLabel(vadMode)
    val unitDetail = when (phase) {
        TranscriptionPipelinePhase.VOICE_ISOLATION ->
            listOf(running.status, running.activityDetail)
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" · ")
                .ifBlank { "Gesamte Stimmspur wird erzeugt." }
        TranscriptionPipelinePhase.AUDIO_PREPARATION,
        TranscriptionPipelinePhase.VAD,
        TranscriptionPipelinePhase.WHISPER -> sectionProgressDetail(running, safeProgress)
    }
    return TranscriptionPipelineProgressPresentation(
        phase = phase,
        phaseNumber = phaseIndex + 1,
        phaseCount = phases.size,
        phaseProgress = safeProgress,
        statusLine = "Schritt ${phaseIndex + 1} von ${phases.size} · $phaseLabel · $percent %",
        detailLine = unitDetail
    )
}

internal fun activePipelinePhases(
    mode: TranscriptionMode,
    vadMode: WhisperVadMode
): List<TranscriptionPipelinePhase> = buildList {
    if (mode == TranscriptionMode.SONG) add(TranscriptionPipelinePhase.VOICE_ISOLATION)
    add(TranscriptionPipelinePhase.AUDIO_PREPARATION)
    if (vadMode == WhisperVadMode.AUTOMATIC) add(TranscriptionPipelinePhase.VAD)
    add(TranscriptionPipelinePhase.WHISPER)
}

private fun TranscriptionPipelinePhase.displayLabel(
    vadMode: WhisperVadMode
): String = when (this) {
    TranscriptionPipelinePhase.VOICE_ISOLATION -> "Stimmisolierung"
    TranscriptionPipelinePhase.AUDIO_PREPARATION -> "Audio vorbereiten"
    TranscriptionPipelinePhase.VAD -> "VAD-Analyse"
    TranscriptionPipelinePhase.WHISPER ->
        if (vadMode == WhisperVadMode.ON) "Whisper + VAD" else "Transkribieren"
}

private fun sectionProgressDetail(
    running: TranscriptionState.Running,
    phaseProgress: Float
): String {
    val source = "${running.status} ${running.activityDetail}"
    val match = SECTION_PATTERN.find(source)
    if (match == null) return running.activityDetail.ifBlank { running.status }
    val sectionNumber = match.groupValues[1].toIntOrNull()?.coerceAtLeast(1) ?: return running.activityDetail
    val sectionCount = match.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: return running.activityDetail
    val explicitPercent = PERCENT_PATTERN.find(running.status)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.coerceIn(0, 100)
    val localPercent = explicitPercent ?: run {
        val scaled = phaseProgress * sectionCount - (sectionNumber - 1)
        (scaled.coerceIn(0f, 1f) * 100f).roundToInt()
    }
    return "Abschnitt $sectionNumber von $sectionCount · $localPercent %"
}

private val SECTION_PATTERN = Regex("Abschnitt\\s+(\\d+)\\s+von\\s+(\\d+)", RegexOption.IGNORE_CASE)
private val PERCENT_PATTERN = Regex("(\\d{1,3})\\s*%")
