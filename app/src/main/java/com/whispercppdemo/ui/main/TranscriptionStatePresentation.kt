package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.song.TranscriptionMode
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
    pipelineTiming = TranscriptionPipelineTiming(
        mode = transcriptionMode,
        voiceIsolationModelLabel = selectedSongSeparationModel.modelLabel.takeIf {
            transcriptionMode == TranscriptionMode.SONG
        }
    ),
    cannaBotMode = CannaBotMode.WAITING
)

internal fun TranscriptUiState.presentRunningTranscription(
    state: TranscriptionState.Running,
    elapsedSeconds: Long
): TranscriptUiState {
    val contextualTiming = pipelineTiming.withContext(
        mode = transcriptionMode,
        voiceIsolationModelLabel = selectedSongSeparationModel.modelLabel
    )
    val phase = transcriptionPipelinePhase(
        status = state.status,
        activityDetail = state.activityDetail,
        mode = contextualTiming.mode,
        fallback = contextualTiming.activePhase
    )
    return copy(
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
        transcriptSectionMinutes = whisperSettings.sectionMinutes.coerceIn(1, 5),
        segmentOrigins = emptyMap(),
        transcriptView = TranscriptViewMode.EDITED,
        detectedLanguage = state.detectedLanguage,
        completedModel = state.model,
        transcriptionDurationSeconds = null,
        vadProcessingSummary = null,
        pipelineTiming = contextualTiming.advanceTo(elapsedSeconds, phase),
        error = null,
        cannaBotMode = CannaBotMode.RUNNING
    )
}

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
    // #101: Qwen korrigiert das Whisper-Ergebnis nicht mehr automatisch.
    // Die Felder der Presentation bleiben vorerst bestehen, damit der bestehende
    // ViewModel-Vertrag für Diagnose/Runtime nicht unnötig groß umgebaut wird.
    val startsAutomaticAi = false
    val automaticAiModelMissing = false
    val capturedSectionMinutes = transcriptSectionMinutes
        ?.coerceIn(1, 5)
        ?: whisperSettings.sectionMinutes.coerceIn(1, 5)
    TranscriptGroupingRuntime.use(capturedSectionMinutes)
    return CompletedTranscriptionPresentation(
        state = copy(
            isBusy = false,
            isTranscribing = false,
            isCancellationRequested = false,
            progress = null,
            activityDetail = null,
            rawWhisperSegments = completed.segments,
            segments = timelineSegments,
            transcriptSectionMinutes = capturedSectionMinutes,
            segmentOrigins = defaultTranscriptOrigins(timelineSegments, completed.segments),
            transcriptView = TranscriptViewMode.EDITED,
            editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
            aiBaselineSegments = emptyList(),
            aiBaselineOrigins = emptyMap(),
            isEditingTranscript = false,
            editingTranscriptGroupStartMs = null,
            draftSegments = emptyList(),
            detectedLanguage = completed.detectedLanguage,
            completedModel = completed.model,
            transcriptionDurationSeconds = completed.transcriptionDurationSeconds,
            vadProcessingSummary = completed.vadSummary,
            pipelineTiming = pipelineTiming.complete(completed.transcriptionDurationSeconds),
            error = null,
            status = if (completed.segments.isEmpty()) {
                "Es wurde kein Text erkannt."
            } else {
                "Fertig: ${completed.segments.size} Textabschnitte erkannt."
            },
            statusKind = StatusMessageKind.COMPLETION,
            statusEventId = statusEventId + 1L,
            cannaBotMode = CannaBotMode.IDLE
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
    segmentOrigins = emptyMap(),
    transcriptView = TranscriptViewMode.EDITED,
    editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
    aiBaselineSegments = emptyList(),
    aiBaselineOrigins = emptyMap(),
    isEditingTranscript = false,
    editingTranscriptGroupStartMs = null,
    draftSegments = emptyList(),
    detectedLanguage = null,
    completedModel = null,
    transcriptionDurationSeconds = null,
    vadProcessingSummary = null,
    pipelineTiming = TranscriptionPipelineTiming(),
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
    segmentOrigins = emptyMap(),
    transcriptView = TranscriptViewMode.EDITED,
    editingTranscriptOrigin = TranscriptSegmentOrigin.MANUAL,
    aiBaselineSegments = emptyList(),
    aiBaselineOrigins = emptyMap(),
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
