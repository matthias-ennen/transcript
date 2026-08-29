package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiTranscriptAnalysisState

/**
 * Keeps the new analysis service independent from transcript editing state while making its
 * busy/progress/result state visible to the existing screen-level interaction guards.
 */
internal fun TranscriptUiState.withAiTranscriptAnalysisState(
    analysisState: AiTranscriptAnalysisState
): TranscriptUiState = when (analysisState) {
    AiTranscriptAnalysisState.Idle -> copy(
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = null
    )

    is AiTranscriptAnalysisState.Starting -> copy(
        isBusy = true,
        isAiTranscriptAnalysisRunning = true,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = "KI-Auswertung wird vorbereitet …",
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
        status = "KI-Auswertung wird abgebrochen …",
        activityDetail = "Die laufende lokale Modellantwort wird sicher beendet.",
        cannaBotMode = CannaBotMode.WAITING
    )

    is AiTranscriptAnalysisState.Completed -> copy(
        isBusy = false,
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.result.action,
        aiTranscriptAnalysisProgress = 1f,
        aiTranscriptAnalysisStatus = "KI-Auswertung abgeschlossen.",
        aiTranscriptAnalysisResult = analysisState.result,
        status = "KI-Auswertung abgeschlossen.",
        activityDetail = "${analysisState.result.action.resultTitle} ist bereit.",
        error = null,
        cannaBotMode = CannaBotMode.REVIEW
    )

    is AiTranscriptAnalysisState.Cancelled -> copy(
        isBusy = false,
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = "KI-Auswertung abgebrochen.",
        status = "KI-Auswertung abgebrochen.",
        activityDetail = null,
        cannaBotMode = CannaBotMode.IDLE
    )

    is AiTranscriptAnalysisState.Failed -> copy(
        isBusy = false,
        isAiTranscriptAnalysisRunning = false,
        aiTranscriptAnalysisCancellationRequested = false,
        aiTranscriptAnalysisAction = analysisState.action,
        aiTranscriptAnalysisProgress = null,
        aiTranscriptAnalysisStatus = "KI-Auswertung fehlgeschlagen.",
        status = "KI-Auswertung fehlgeschlagen.",
        activityDetail = null,
        error = analysisState.message,
        cannaBotMode = CannaBotMode.IDLE
    )
}
