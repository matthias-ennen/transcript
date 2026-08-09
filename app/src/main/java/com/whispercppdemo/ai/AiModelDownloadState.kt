package de.matthiasennen.transcript.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AiModelDownloadState {
    data object Idle : AiModelDownloadState

    data class Running(
        val model: AiModel,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val resumed: Boolean
    ) : AiModelDownloadState

    data class Verifying(val model: AiModel, val downloadedBytes: Long) : AiModelDownloadState
    data class Completed(val model: AiModel) : AiModelDownloadState
    data class Failed(
        val model: AiModel,
        val message: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : AiModelDownloadState
}

object AiModelDownloadCoordinator {
    private val mutableState = MutableStateFlow<AiModelDownloadState>(AiModelDownloadState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: AiModelDownloadState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = AiModelDownloadState.Idle
    }
}
