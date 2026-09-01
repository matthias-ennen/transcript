package de.matthiasennen.transcript.song

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SongModelDownloadState {
    data object Idle : SongModelDownloadState

    data class Running(
        val model: SongSeparationModel,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val resumed: Boolean
    ) : SongModelDownloadState

    data class Verifying(
        val model: SongSeparationModel,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : SongModelDownloadState

    data class Completed(val model: SongSeparationModel) : SongModelDownloadState

    data class Failed(
        val model: SongSeparationModel,
        val message: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : SongModelDownloadState
}

object SongModelDownloadCoordinator {
    private val mutableState = MutableStateFlow<SongModelDownloadState>(SongModelDownloadState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: SongModelDownloadState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = SongModelDownloadState.Idle
    }
}
