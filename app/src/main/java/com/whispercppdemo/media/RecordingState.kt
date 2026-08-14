package de.matthiasennen.transcript.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState

    data class Running(
        val file: File,
        val startedAtEpochMs: Long,
        val elapsedSeconds: Long,
        val amplitude: Float
    ) : RecordingState

    data class Completed(val file: File) : RecordingState
    data class Failed(val message: String) : RecordingState
}

object RecordingCoordinator {
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: RecordingState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = RecordingState.Idle
    }
}
