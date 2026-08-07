package de.matthiasennen.transcript.download

import de.matthiasennen.transcript.ui.main.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState

    data class Running(
        val model: WhisperModel,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val resumed: Boolean
    ) : ModelDownloadState

    data class Verifying(val model: WhisperModel, val downloadedBytes: Long) : ModelDownloadState

    data class Completed(val model: WhisperModel) : ModelDownloadState

    data class Failed(
        val model: WhisperModel,
        val message: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : ModelDownloadState
}
object ModelDownloadCoordinator {
    private val mutableState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: ModelDownloadState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = ModelDownloadState.Idle
    }
}
