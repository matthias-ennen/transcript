package de.matthiasennen.transcript.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AiTranscriptAnalysisState {
    data object Idle : AiTranscriptAnalysisState

    data class Starting(
        val action: AiTranscriptAnalysisAction,
        val model: AiModel,
        val sourceFingerprint: String
    ) : AiTranscriptAnalysisState

    data class Running(
        val action: AiTranscriptAnalysisAction,
        val model: AiModel,
        val sourceFingerprint: String,
        val progress: Float,
        val status: String,
        val activityDetail: String
    ) : AiTranscriptAnalysisState

    data class CancellationRequested(
        val action: AiTranscriptAnalysisAction,
        val model: AiModel,
        val sourceFingerprint: String
    ) : AiTranscriptAnalysisState

    data class Completed(
        val result: AiTranscriptAnalysisResult
    ) : AiTranscriptAnalysisState

    data class Cancelled(
        val action: AiTranscriptAnalysisAction,
        val model: AiModel,
        val sourceFingerprint: String
    ) : AiTranscriptAnalysisState

    data class Failed(
        val action: AiTranscriptAnalysisAction,
        val model: AiModel,
        val sourceFingerprint: String,
        val message: String
    ) : AiTranscriptAnalysisState
}

object AiTranscriptAnalysisCoordinator {
    private val mutableState = MutableStateFlow<AiTranscriptAnalysisState>(AiTranscriptAnalysisState.Idle)
    val state: StateFlow<AiTranscriptAnalysisState> = mutableState.asStateFlow()

    internal fun update(state: AiTranscriptAnalysisState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = AiTranscriptAnalysisState.Idle
    }
}
