package de.matthiasennen.transcript.ai

import com.whispercpp.whisper.WhisperSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiPostProcessingMode { AUTOMATIC, MANUAL_GROUP }

data class AiCorrectionTrace(
    val segmentNumber: Int,
    val originalText: String,
    val rawResponse: String,
    val resultText: String,
    val decision: String
)

data class AiSelfTestMetrics(
    val modelAlreadyLoaded: Boolean,
    val conversationContinued: Boolean,
    val modelLoadMs: Long,
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptProcessingMs: Long,
    val timeToFirstTokenMs: Long,
    val answerGenerationMs: Long,
    val totalMs: Long,
    val finishReason: String,
    val thinkingDisabled: Boolean
)

data class AiModelPreloadMetrics(
    val modelAlreadyLoaded: Boolean,
    val modelLoadMs: Long,
    val cpuFallbackUsed: Boolean
)

sealed interface AiPostProcessingState {
    data object Idle : AiPostProcessingState

    data class Starting(
        val mode: AiPostProcessingMode,
        val model: AiModel
    ) : AiPostProcessingState

    data class SelfTestStarting(val model: AiModel) : AiPostProcessingState

    data class ModelPreloadStarting(val model: AiModel) : AiPostProcessingState

    data class ModelPreloadRunning(
        val model: AiModel,
        val status: String,
        val activityDetail: String,
        val diagnostics: List<String>
    ) : AiPostProcessingState

    data class ModelPreloadCompleted(
        val model: AiModel,
        val metrics: AiModelPreloadMetrics,
        val diagnostics: List<String>
    ) : AiPostProcessingState

    data class ModelPreloadFailed(
        val model: AiModel,
        val message: String,
        val diagnostics: List<String>
    ) : AiPostProcessingState

    data class SelfTestRunning(
        val model: AiModel,
        val status: String,
        val activityDetail: String,
        val diagnostics: List<String>
    ) : AiPostProcessingState

    data class SelfTestCompleted(
        val model: AiModel,
        val response: String,
        val metrics: AiSelfTestMetrics,
        val diagnostics: List<String>
    ) : AiPostProcessingState

    data class SelfTestFailed(
        val model: AiModel,
        val message: String,
        val diagnostics: List<String>
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
        val rejectedCorrections: Int,
        val latestTrace: AiCorrectionTrace?
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
        val rejectedCorrections: Int,
        val latestTrace: AiCorrectionTrace?
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
