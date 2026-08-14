package de.matthiasennen.transcript.ui.main

import android.net.Uri
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ai.AiBenchmarkResult
import de.matthiasennen.transcript.ai.AiCorrectionTrace
import de.matthiasennen.transcript.ai.AiHardwareSnapshot
import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiModelInstallation
import de.matthiasennen.transcript.ai.AiSelfTestMetrics
import de.matthiasennen.transcript.ai.LocalAiConfiguration
import de.matthiasennen.transcript.download.VadModelInstallation
import de.matthiasennen.transcript.transcription.VadProcessingSummary

enum class CannaBotMode { IDLE, WAITING, REVIEW, RUNNING }

enum class CannaBotCue { NONE, RUNNING_RIGHT, RUNNING_LEFT, JUMPING, WAVING, SUCCESS, FAILED }

/** Immutable rendering contract shared by the main screen and its subpages. */
data class TranscriptUiState(
    val selectedAudio: Uri? = null,
    val selectedFileName: String? = null,
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val mediaReadyStatus: String? = null,
    val waveform: List<Float> = emptyList(),
    val liveWaveform: List<Float> = emptyList(),
    val isWaveformLoading: Boolean = false,
    val waveformProgress: Float? = null,
    val language: String = "auto",
    val whisperSettings: WhisperSettings = WhisperSettings(),
    val selectedModel: WhisperModel = WhisperModel.BASE,
    val modelInstallations: List<ModelInstallation> = emptyList(),
    val modelReady: Boolean = false,
    val downloadingModel: WhisperModel? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val vadModelInstallation: VadModelInstallation = VadModelInstallation(),
    val isVadDownloading: Boolean = false,
    val vadDownloadedBytes: Long = 0L,
    val vadDownloadTotalBytes: Long = 0L,
    val selectedAiModel: AiModel = AiModel.BALANCED,
    val aiModelInstallations: List<AiModelInstallation> = emptyList(),
    val aiPostProcessingEnabled: Boolean = false,
    val automaticAiPostProcessingEnabled: Boolean = false,
    val downloadingAiModel: AiModel? = null,
    val aiDownloadedBytes: Long = 0L,
    val aiDownloadTotalBytes: Long = 0L,
    val isAiPostProcessing: Boolean = false,
    val isAiSelfTest: Boolean = false,
    val aiTestPrompt: String = "",
    val aiSelfTestResponse: String? = null,
    val aiSelfTestModel: AiModel? = null,
    val aiSelfTestMetrics: AiSelfTestMetrics? = null,
    val latestAiCorrectionTrace: AiCorrectionTrace? = null,
    val performanceProfileModel: AiModel = AiModel.BALANCED,
    val aiPerformanceConfiguration: LocalAiConfiguration = LocalAiConfiguration(),
    val aiHardwareSnapshot: AiHardwareSnapshot? = null,
    val performanceModelLayerCount: Int = 0,
    val isAiBenchmarkRunning: Boolean = false,
    val aiBenchmarkProgress: Float = 0f,
    val aiBenchmarkResult: AiBenchmarkResult? = null,
    val aiPerformanceJson: String = "",
    val aiPerformanceMessage: String? = null,
    val isBusy: Boolean = false,
    val isTranscribing: Boolean = false,
    val isCancellationRequested: Boolean = false,
    val progress: Float? = null,
    val status: String = "Bitte zuerst das Whisper-Modell herunterladen.",
    val statusKind: StatusMessageKind = StatusMessageKind.IMPORTANT,
    val statusEventId: Long = 0L,
    val runtimeEstimateAnnouncementId: Long = 0L,
    val transcriptionEstimateSeconds: Long? = null,
    val elapsedSeconds: Long = 0L,
    val activityDetail: String? = null,
    val diagnostics: List<String> = emptyList(),
    val rawWhisperSegments: List<WhisperSegment> = emptyList(),
    val segments: List<WhisperSegment> = emptyList(),
    val isEditingTranscript: Boolean = false,
    val editingTranscriptGroupStartMs: Long? = null,
    val draftSegments: List<WhisperSegment> = emptyList(),
    val detectedLanguage: String? = null,
    val completedModel: WhisperModel? = null,
    val transcriptionDurationSeconds: Long? = null,
    val vadProcessingSummary: VadProcessingSummary? = null,
    val error: String? = null,
    val cannaBotMode: CannaBotMode = CannaBotMode.IDLE,
    val cannaBotCue: CannaBotCue = CannaBotCue.NONE,
    val cannaBotCueId: Long = 0L
)
