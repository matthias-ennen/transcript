package de.matthiasennen.transcript.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri

data class RecordingOutput(val uri: Uri, val fileName: String)

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState
    data object Stopping : RecordingState

    data class Running(
        val output: RecordingOutput,
        val startedAtEpochMs: Long,
        val elapsedSeconds: Long,
        val amplitude: Float
    ) : RecordingState

    data class Completed(val output: RecordingOutput) : RecordingState
    data class Failed(val message: String) : RecordingState
}

object RecordingCoordinator {
    private val stateLock = Any()
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: RecordingState) = synchronized(stateLock) {
        mutableState.value = state
    }

    fun updateRunning(state: RecordingState.Running): Boolean = synchronized(stateLock) {
        when (mutableState.value) {
            RecordingState.Starting, is RecordingState.Running -> {
                mutableState.value = state
                true
            }
            else -> false
        }
    }

    fun beginStopping(): Boolean = synchronized(stateLock) {
        when (mutableState.value) {
            RecordingState.Starting, is RecordingState.Running -> {
                mutableState.value = RecordingState.Stopping
                true
            }
            else -> false
        }
    }

    fun reset() = synchronized(stateLock) {
        mutableState.value = RecordingState.Idle
    }
}
