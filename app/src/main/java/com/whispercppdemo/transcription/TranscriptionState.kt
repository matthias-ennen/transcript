package de.matthiasennen.transcript.transcription

import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface TranscriptionState {
    data object Idle : TranscriptionState

    data class Starting(val fileName: String) : TranscriptionState

    data class Running(
        val fileName: String,
        val model: WhisperModel,
        val progress: Float,
        val sectionNumber: Int,
        val sectionCount: Int,
        val startedAtEpochMs: Long,
        val elapsedSeconds: Long,
        val status: String,
        val activityDetail: String,
        val diagnostics: List<String>,
        val committedSegments: List<WhisperSegment>,
        val detectedLanguage: String?
    ) : TranscriptionState

    data class Completed(
        val fileName: String,
        val model: WhisperModel,
        val segments: List<WhisperSegment>,
        val detectedLanguage: String,
        val transcriptionDurationSeconds: Long
    ) : TranscriptionState

    data class Cancelled(val fileName: String) : TranscriptionState

    data class Failed(
        val fileName: String,
        val message: String,
        val canResume: Boolean,
        val committedSegments: List<WhisperSegment>
    ) : TranscriptionState
}

object TranscriptionCoordinator {
    private val mutableState = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state = mutableState.asStateFlow()

    fun update(state: TranscriptionState) {
        mutableState.value = state
    }

    fun reset() {
        mutableState.value = TranscriptionState.Idle
    }
}
