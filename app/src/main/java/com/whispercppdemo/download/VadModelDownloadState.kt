package de.matthiasennen.transcript.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VadModelDownloadState {
    data object Idle : VadModelDownloadState
    data class Running(val downloadedBytes: Long, val totalBytes: Long, val resumed: Boolean) : VadModelDownloadState
    data class Verifying(val downloadedBytes: Long) : VadModelDownloadState
    data object Completed : VadModelDownloadState
    data class Failed(val message: String, val downloadedBytes: Long, val totalBytes: Long) : VadModelDownloadState
}

object VadModelDownloadCoordinator {
    private val mutableState = MutableStateFlow<VadModelDownloadState>(VadModelDownloadState.Idle)
    val state = mutableState.asStateFlow()
    fun update(state: VadModelDownloadState) { mutableState.value = state }
    fun reset() { mutableState.value = VadModelDownloadState.Idle }
}
