package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.transcription.TranscriptionState

/** Pure mapping between a transcription-service envelope and the Compose render contract. */
internal fun TranscriptUiState.presentStartingTranscription(
    starting: TranscriptionState.Starting
): TranscriptUiState = copy(
    isBusy = true,
    isTranscribing = true,
    isCancellationRequested = false,
    progress = 0f,
    error = null,
    status = "Transkription wird im Hintergrund vorbereitet …",
    statusKind = StatusMessageKind.IMPORTANT,
    statusEventId = statusEventId + 1L,
    activityDetail = starting.fileName,
    cannaBotMode = CannaBotMode.WAITING
)

internal fun TranscriptUiState.presentRunningTranscription(
    state: TranscriptionState.Running,
    elapsedSeconds: Long
): TranscriptUiState = copy(
    isBusy = true,
    isTranscribing = true,
    isCancellationRequested = false,
    progress = state.progress,
    elapsedSeconds = elapsedSeconds,
    status = state.status,
    statusKind = state.statusKind,
    statusEventId = statusEventId + 1L,
    activityDetail = state.activityDetail,
    diagnostics = state.diagnostics,
    rawWhisperSegments = emptyList(),
    segments = state.committedSegments,
    detectedLanguage = state.detectedLanguage,
    completedModel = state.model,
    transcriptionDurationSeconds = null,
    vadProcessingSummary = null,
    error = null,
    cannaBotMode = CannaBotMode.RUNNING
)

internal data class CompletedTranscriptionPresentation(
    val state: TranscriptUiState,
    val timelineSegments: List<com.whispercpp.whisper.WhisperSegment>,
    val startsAutomaticAi: Boolean,
    val automaticAiModelMissing: Boolean
)

internal fun TranscriptUiState.presentCompletedTranscription(
    completed: TranscriptionState.Completed
): CompletedTranscriptionPresentation {
    val timelineSegments = buildTranscriptTimeline(
        whisperSegments = completed.segments,
        audioDurationMs = audioDurationMs
    )
    val startsAutomaticAi = completed.segments.isNotEmpty() &&
        aiPostProcessingEnabled && automaticAiPostProcessingEnabled && selectedAiModelInstalled
    val automaticAiModelMissing = completed.segments.isNotEmpty() &&
        aiPostProcessingEnabled && automaticAiPostProcessingEnabled && !selectedAiModelInstalled
    val capturedSectionMinutes = whisperSettings.sectionMinutes.coerceIn(1, 5)
    TranscriptGroupingRuntime.use(capturedSectionMinutes)
    return CompletedTranscriptionPresentation(
        state = copy(
            isBusy = startsAutomaticAi,
            isTranscribing = false,
            isCancellationRequested = false,
            progress = null,
            activityDetail = null,
            rawWhisperSegments = completed.segments,
            segments = timelineSegments,
            transcriptSectionMinutes = capturedSectionMinutes,
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            detectedLanguage = completed.detectedLanguage,
            completedModel = completed.model,
            transcriptionDurationSeconds = completed.transcriptionDurationSeconds,
            vadProcessingSummary = completed.vadSummary,
            error = null,
            status = when {
                startsAutomaticAi -> "Transkription fertig. Texte werden jetzt mit KI überarbeitet …"
                completed.segments.isEmpty() -> "Es wurde kein Text erkannt."
                automaticAiModelMissing -> "Transkription fertig. KI-Nachbearbeitung übersprungen: Modell fehlt."
                else -> "Fertig: ${completed.segments.size} Textabschnitte erkannt."
            },
            statusKind = StatusMessageKind.COMPLETION,
            statusEventId = statusEventId + 1L,
            cannaBotMode = if (startsAutomaticAi) CannaBotMode.REVIEW else CannaBotMode.IDLE
        ),
        timelineSegments = timelineSegments,
        startsAutomaticAi = startsAutomaticAi,
        automaticAiModelMissing = automaticAiModelMissing
    )
}

internal fun TranscriptUiState.presentCancelledTranscription(): TranscriptUiState = copy(
    isBusy = false,
    isTranscribing = false,
    isCancellationRequested = false,
    progress = null,
    activityDetail = null,
    rawWhisperSegments = emptyList(),
    segments = emptyList(),
    transcriptSectionMinutes = null,
    isEditingTranscript = false,
    editingTranscriptGroupStartMs = null,
    draftSegments = emptyList(),
    detectedLanguage = null,
    completedModel = null,
    transcriptionDurationSeconds = null,
    vadProcessingSummary = null,
    error = null,
    status = "Transkription angehalten · Der Zwischenstand bleibt erhalten.",
    statusKind = StatusMessageKind.COMPLETION,
    statusEventId = statusEventId + 1L,
    cannaBotMode = CannaBotMode.IDLE
)

internal fun TranscriptUiState.presentFailedTranscription(
    failed: TranscriptionState.Failed
): TranscriptUiState = copy(
    isBusy = false,
    isTranscribing = false,
    isCancellationRequested = false,
    progress = null,
    activityDetail = null,
    rawWhisperSegments = emptyList(),
    segments = failed.committedSegments,
    transcriptSectionMinutes = null,
    isEditingTranscript = false,
    editingTranscriptGroupStartMs = null,
    draftSegments = emptyList(),
    error = failed.message,
    status = if (failed.canResume) {
        "Transkription unterbrochen · Beim nächsten Start wird sie fortgesetzt."
    } else {
        "Transkription fehlgeschlagen."
    },
    statusKind = StatusMessageKind.ERROR,
    statusEventId = statusEventId + 1L,
    cannaBotMode = CannaBotMode.IDLE
)
