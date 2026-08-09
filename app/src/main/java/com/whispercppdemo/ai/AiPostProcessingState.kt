package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiPostProcessingMode { AUTOMATIC, MANUAL_GROUP }

sealed interface AiPostProcessingState {
    data object Idle : AiPostProcessingState

    data class Starting(
        val mode: AiPostProcessingMode,
        val model: AiModel
    ) : AiPostProcessingState

    data class Running(
        val mode: AiPostProcessingMode,
        val model: AiModel,
        val progress: Float,
        val groupNumber: Int,
        val groupCount: Int,
        val status: String,
        val activityDetail: String,
        val diagnostics: List<String>,
        val correctedSegments: List<WhisperSegment>,
        val groupStartMs: Long?,
        val checkedSegments: Int,
        val proposedCorrections: Int,
        val rejectedCorrections: Int
    ) : AiPostProcessingState

    data class Completed(
        val mode: AiPostProcessingMode,
        val model: AiModel,
        val segments: List<WhisperSegment>,
        val groupStartMs: Long?,
        val durationSeconds: Long,
        val diagnostics: List<String>,
        val checkedSegments: Int,
        val appliedCorrections: Int,
        val rejectedCorrections: Int
    ) : AiPostProcessingState

    data class Failed(
        val mode: AiPostProcessingMode,
        val model: AiModel,
        val message: String,
        val originalSegments: List<WhisperSegment>,
        val groupStartMs: Long?,
        val diagnostics: List<String>
    ) : AiPostProcessingState
}

object AiPostProcessingCoordinator {
    private val mutableState = MutableStateFlow<AiPostProcessingState>(AiPostProcessingState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: AiPostProcessingState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = AiPostProcessingState.Idle
    }
}
