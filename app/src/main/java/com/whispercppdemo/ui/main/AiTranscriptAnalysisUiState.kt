package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiTranscriptAnalysisState

/**
 * Keeps the new analysis service independent from transcript editing state while making its
 * active busy/progress state visible to the existing screen-level interaction guards.
 * Terminal analysis states deliberately do not overwrite a newer transcription/download status.
 */
internal fun TranscriptUiState.withAiTranscriptAnalysisState(
    analysisState: AiTranscriptAnalysisState
): TranscriptUiState = when (analysisState) {
    AiTranscriptAnalysisState.Idle -> copy(
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = null,
        aiTranscriptAnalysisResult = null
    )

    is AiTranscriptAnalysisState.Starting -> copy(
        isBusy = true,
        isAiTranscriptAnalysisRunning = true,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = "KI-Auswertung wird vorbereitet …",
        aiTranscriptAnalysisResult = null,
        status = "KI-Auswertung wird vorbereitet …",
        activityDetail = "${analysisState.model.modelLabel} wird lokal vorbereitet.",
        error = null,
        cannaBotMode = CannaBotMode.WAITING
    )

    is AiTranscriptAnalysisState.Running -> copy(
        isBusy = true,
        isAiTranscriptAnalysisRunning = true,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = analysisState.progress,
        aiTranscriptAnalysisStatus = analysisState.status,
        aiTranscriptAnalysisResult = null,
        status = analysisState.status,
        activityDetail = analysisState.activityDetail,
        error = null,
        cannaBotMode = CannaBotMode.RUNNING
    )

    is AiTranscriptAnalysisState.CancellationRequested -> copy(
        isBusy = true,
        isAiTranscriptAnalysisRunning = true,
        aiTranscriptAnalysisCancellationRequested = true,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisStatus = "Abbruch wird durchgeführt …",
        aiTranscriptAnalysisResult = null,
        status = "KI-Auswertung wird abgebrochen …",
        activityDetail = "Die laufende lokale Modellantwort wird sicher beendet.",
        cannaBotMode = CannaBotMode.WAITING
    )

    is AiTranscriptAnalysisState.Completed -> copy(
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.result.action,
        aiTranscriptAnalysisProgress = 1f,
        aiTranscriptAnalysisStatus = "KI-Auswertung abgeschlossen.",
        aiTranscriptAnalysisResult = analysisState.result
    )

    is AiTranscriptAnalysisState.Cancelled -> copy(
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = "KI-Auswertung abgebrochen.",
        aiTranscriptAnalysisResult = null
    )

    is AiTranscriptAnalysisState.Failed -> copy(
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = analysisState.message,
        aiTranscriptAnalysisResult = null
    )
}
