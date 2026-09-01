package de.matthiasennen.transcript.transcription

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.song.exportKimMemoryDiagnosticsToDownloads
import de.matthiasennen.transcript.ui.main.WhisperModel
import de.matthiasennen.transcript.ui.main.StatusMessageKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val STATE_FILE_NAME = "active-transcription-state.bin"
private const val STATE_CHANGED_ACTION = "de.matthiasennen.transcript.TRANSCRIPTION_STATE_CHANGED"
private const val WATCHDOG_LOG_TAG = "TranscriptionWatchdog"
private const val EXIT_CHECK_INTERVAL_MS = 3_000L
private const val WORKER_START_TIMEOUT_MS = 3 * 60_000L
private const val HEARTBEAT_MISSING_TIMEOUT_MS = 15_000L
private const val LONG_RUNNING_PROGRESS_NOTICE_MS = 3 * 60_000L

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
        val detectedLanguage: String?,
        val statusKind: StatusMessageKind = StatusMessageKind.PROGRESS
    ) : TranscriptionState
    data class Completed(
        val fileName: String,
        val model: WhisperModel,
        val segments: List<WhisperSegment>,
        val detectedLanguage: String,
        val transcriptionDurationSeconds: Long,
        val vadSummary: VadProcessingSummary? = null
    ) : TranscriptionState
    data class Cancelled(val fileName: String) : TranscriptionState
    data class Failed(
        val fileName: String,
        val message: String,
        val canResume: Boolean,
        val committedSegments: List<WhisperSegment>
    ) : TranscriptionState
}

internal enum class WorkerWatchdogState {
    HEALTHY,
    AWAITING_FIRST_HEARTBEAT,
    HEARTBEAT_MISSING
}

internal fun shouldRetryUnresponsiveWorkerOnCpu(
    heartbeat: WorkerHeartbeat?,
    expectedJobId: String,
    cpuRetryAlreadyUsed: Boolean
): Boolean {
    if (cpuRetryAlreadyUsed || heartbeat == null) return false
    if (heartbeat.jobId != expectedJobId) return false
    val backend = heartbeat.backend.uppercase()
    val gpuBackend = backend.contains("VULKAN") || backend.contains("GPU")
    return gpuBackend && heartbeat.phase in setOf("model_loading", "inference")
}

internal fun canResumeAfterWorkerExit(
    checkpoint: TranscriptionCheckpoint?,
    preparedAudioUsable: Boolean,
    committedSegments: List<WhisperSegment>
): Boolean = checkpoint != null &&
    (checkpoint.hasMeaningfulProgress() || preparedAudioUsable || committedSegments.isNotEmpty())

/**
 * Liveness deliberately depends only on the independent worker heartbeat.
 * Native Whisper progress can remain unchanged for a long time on large models
 * and must never be used as a hard-kill criterion.
 */
internal fun evaluateWorkerWatchdog(
    heartbeat: WorkerHeartbeat?,
    expectedWorkerStartedAtEpochMs: Long,
    envelopeUpdatedAtEpochMs: Long,
    nowEpochMs: Long,
    heartbeatMissingTimeoutMs: Long = HEARTBEAT_MISSING_TIMEOUT_MS,
    workerStartTimeoutMs: Long = WORKER_START_TIMEOUT_MS
): WorkerWatchdogState {
    if (heartbeat == null ||
        heartbeat.workerStartedAtEpochMs != expectedWorkerStartedAtEpochMs
    ) {
        return if (nowEpochMs - envelopeUpdatedAtEpochMs > workerStartTimeoutMs) {
            WorkerWatchdogState.HEARTBEAT_MISSING
        } else {
            WorkerWatchdogState.AWAITING_FIRST_HEARTBEAT
        }
    }
    return if (nowEpochMs - heartbeat.heartbeatAtEpochMs > heartbeatMissingTimeoutMs) {
        WorkerWatchdogState.HEARTBEAT_MISSING
    } else {
        WorkerWatchdogState.HEALTHY
    }
}

internal fun isLongRunningInferenceWithoutNativeProgress(
    heartbeat: WorkerHeartbeat?,
    expectedWorkerStartedAtEpochMs: Long,
    nowEpochMs: Long,
    noticeAfterMs: Long = LONG_RUNNING_PROGRESS_NOTICE_MS
): Boolean = heartbeat != null &&
    heartbeat.workerStartedAtEpochMs == expectedWorkerStartedAtEpochMs &&
    heartbeat.phase == "inference" &&
    nowEpochMs - heartbeat.heartbeatAtEpochMs <= HEARTBEAT_MISSING_TIMEOUT_MS &&
    nowEpochMs - heartbeat.lastProgressAtEpochMs > noticeAfterMs

/** Process-safe bridge between the isolated native worker and the UI process. */
object TranscriptionCoordinator {
    private val mutableState = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var applicationContext: Context? = null
    private var receiverRegistered = false
    private var observedEnvelope: PersistedTranscriptionState? = null
    private var watchdogRecoveryRequestedForStart = 0L
    private var longRunningNoticeKey = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STATE_CHANGED_ACTION) refreshFromDisk(context)
        }
    }

    private val exitMonitor = object : Runnable {
        override fun run() {
            val context = applicationContext ?: return
            val envelope = observedEnvelope
            if (envelope != null && envelope.state.isActive()) {
                val now = System.currentTimeMillis()
                val heartbeat = workerHeartbeatStore(context.filesDir).read()
                val exit = findWorkerExit(context, envelope.workerStartedAtEpochMs)
                if (exit != null) {
                    val running = envelope.state as? TranscriptionState.Running
                    val checkpoint = TranscriptionCheckpointStore(
                        File(context.filesDir, "active-transcription.bin")
                    ).read()
                    val preparedAudioUsable = checkpoint?.let { saved ->
                        val store = PreparedAudioStore(
                            File(context.filesDir, "prepared-transcription-audio")
                        )
                        store.readManifest()?.let { manifest ->
                            store.isUsable(
                                manifest,
                                preparedAudioRequestKey(saved.request, saved.durationMs),
                                saved.nextStartMs
                            )
                        } == true
                    } == true
                    val kimDiagnostics = if (
                        exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY
                    ) {
                        exportKimMemoryDiagnosticsToDownloads(context)
                    } else {
                        null
                    }
                    val failureMessage = buildString {
                        append(workerExitMessage(exit))
                        kimDiagnostics?.let { exported ->
                            append(" Kim-Speicherdiagnose gespeichert unter ")
                            append(exported.relativePath)
                            append('.')
                        }
                    }
                    val failure = TranscriptionState.Failed(
                        fileName = envelope.state.fileName(),
                        message = failureMessage,
                        canResume = canResumeAfterWorkerExit(
                            checkpoint = checkpoint,
                            preparedAudioUsable = preparedAudioUsable,
                            committedSegments = running?.committedSegments.orEmpty()
                        ),
                        committedSegments = running?.committedSegments.orEmpty()
                    )
                    val failedEnvelope = envelope.copy(
                        state = failure,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    stateStore(context).write(failedEnvelope)
                    observedEnvelope = failedEnvelope
                    mutableState.value = failure
                } else {
                    when (
                        evaluateWorkerWatchdog(
                            heartbeat = heartbeat,
                            expectedWorkerStartedAtEpochMs = envelope.workerStartedAtEpochMs,
                            envelopeUpdatedAtEpochMs = envelope.updatedAtEpochMs,
                            nowEpochMs = now
                        )
                    ) {
                        WorkerWatchdogState.HEARTBEAT_MISSING -> {
                            if (watchdogRecoveryRequestedForStart != envelope.workerStartedAtEpochMs) {
                                watchdogRecoveryRequestedForStart = envelope.workerStartedAtEpochMs
                                Log.w(
                                    WATCHDOG_LOG_TAG,
                                    "Unresponsive worker handling requested: reason=heartbeat_missing " +
                                        "workerStart=${envelope.workerStartedAtEpochMs} " +
                                        "phase=${heartbeat?.phase} backend=${heartbeat?.backend} " +
                                        "heartbeatAgeMs=${heartbeat?.let { now - it.heartbeatAtEpochMs }}"
                                )
                                context.sendBroadcast(
                                    Intent(context, TranscriptionControlReceiver::class.java).apply {
                                        action = ACTION_RECOVER_TRANSCRIPTION_ON_CPU
                                    }
                                )
                            }
                        }
                        WorkerWatchdogState.HEALTHY -> {
                            if (
                                isLongRunningInferenceWithoutNativeProgress(
                                    heartbeat = heartbeat,
                                    expectedWorkerStartedAtEpochMs = envelope.workerStartedAtEpochMs,
                                    nowEpochMs = now
                                )
                            ) {
                                publishLongRunningInferenceNotice(envelope, checkNotNull(heartbeat), now)
                            }
                        }
                        WorkerWatchdogState.AWAITING_FIRST_HEARTBEAT -> Unit
                    }
                }
            }
            if (observedEnvelope?.state?.isActive() == true) {
                mainHandler.postDelayed(this, EXIT_CHECK_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(STATE_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        refreshFromDisk(appContext)
        if (observedEnvelope?.state?.isActive() != true) {
            exportKimMemoryDiagnosticsToDownloads(appContext)?.let { exported ->
                Log.i(
                    WATCHDOG_LOG_TAG,
                    "Existing Kim memory diagnostics exported to ${exported.relativePath}"
                )
            }
        }
    }

    fun publish(context: Context, state: TranscriptionState, workerStartedAtEpochMs: Long) {
        stateStore(context).write(
            PersistedTranscriptionState(
                workerStartedAtEpochMs = workerStartedAtEpochMs,
                updatedAtEpochMs = System.currentTimeMillis(),
                state = state
            )
        )
        context.sendBroadcast(Intent(STATE_CHANGED_ACTION).setPackage(context.packageName))
    }

    @Synchronized
    fun acknowledgeTerminal(context: Context) {
        val current = observedEnvelope?.state ?: mutableState.value
        if (current.isActive() || current == TranscriptionState.Idle) return
        stateStore(context).clear()
        observedEnvelope = null
        longRunningNoticeKey = ""
        mutableState.value = TranscriptionState.Idle
        mainHandler.removeCallbacks(exitMonitor)
    }

    @Synchronized
    private fun refreshFromDisk(context: Context) {
        val previousWorkerStart = observedEnvelope?.workerStartedAtEpochMs
        val envelope = stateStore(context).read()
        if (previousWorkerStart != envelope?.workerStartedAtEpochMs) {
            longRunningNoticeKey = ""
        }
        observedEnvelope = envelope
        mutableState.value = envelope?.state ?: TranscriptionState.Idle
        mainHandler.removeCallbacks(exitMonitor)
        if (envelope?.state?.isActive() == true) mainHandler.post(exitMonitor)
    }

    private fun publishLongRunningInferenceNotice(
        envelope: PersistedTranscriptionState,
        heartbeat: WorkerHeartbeat,
        nowEpochMs: Long
    ) {
        val running = envelope.state as? TranscriptionState.Running ?: return
        val noticeKey = "${heartbeat.workerStartedAtEpochMs}|${heartbeat.sectionNumber}|" +
            heartbeat.lastProgressAtEpochMs
        if (longRunningNoticeKey == noticeKey) return
        longRunningNoticeKey = noticeKey

        val progressAgeSeconds =
            (nowEpochMs - heartbeat.lastProgressAtEpochMs).coerceAtLeast(0L) / 1_000L
        val progressAgeMinutes = (progressAgeSeconds / 60L).coerceAtLeast(1L)
        val diagnostic =
            "Whisper rechnet weiterhin: Backend ${heartbeat.backend}, " +
                "Abschnitt ${heartbeat.sectionNumber}, seit ${progressAgeMinutes} Min. " +
                "kein neuer Prozentwert; Lebenszeichen aktuell."
        Log.i(
            WATCHDOG_LOG_TAG,
            "Long-running inference remains healthy: workerStart=" +
                "${heartbeat.workerStartedAtEpochMs} backend=${heartbeat.backend} " +
                "section=${heartbeat.sectionNumber} heartbeatAgeMs=" +
                "${nowEpochMs - heartbeat.heartbeatAtEpochMs} progressAgeMs=" +
                "${nowEpochMs - heartbeat.lastProgressAtEpochMs}"
        )
        mutableState.value = running.copy(
            status = "Whisper rechnet weiterhin · seit $progressAgeMinutes Min. " +
                "kein neuer Prozentwert",
            activityDetail = "${running.model.modelLabel} · Backend ${heartbeat.backend} · " +
                "Abschnitt ${heartbeat.sectionNumber} von ${running.sectionCount}",
            diagnostics = (running.diagnostics + diagnostic).takeLast(12)
        )
    }

    private fun stateStore(context: Context) =
        TranscriptionStateStore(File(context.filesDir, STATE_FILE_NAME))

    private fun findWorkerExit(context: Context, workerStartedAtEpochMs: Long): WorkerExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java)
        val workerName = "${context.packageName}:transcription"
        return manager.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            .asSequence()
            .filter { it.processName == workerName && it.timestamp >= workerStartedAtEpochMs }
            .map { WorkerExit(it.reason, it.description?.toString()) }
            .firstOrNull(::isUnexpectedWorkerExit)
    }
}

internal data class WorkerExit(val reason: Int, val description: String?)

internal fun isUnexpectedWorkerExit(exit: WorkerExit): Boolean = when (exit.reason) {
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
    ApplicationExitInfo.REASON_LOW_MEMORY,
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
    else -> false
}

internal fun workerExitMessage(exit: WorkerExit): String {
    val cause = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "durch einen nativen Fehler"
        ApplicationExitInfo.REASON_CRASH -> "durch einen Programmfehler"
        ApplicationExitInfo.REASON_ANR -> "nach einem Stillstand"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "wegen Speichermangels"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "wegen zu hoher Ressourcenlast"
        else -> "unerwartet"
    }
    return "Der getrennte Transkriptionsprozess wurde $cause beendet. " +
        "Die App bleibt geöffnet; ein gesicherter Zwischenstand kann fortgesetzt werden."
}

private fun TranscriptionState.isActive(): Boolean =
    this is TranscriptionState.Starting || this is TranscriptionState.Running

private fun TranscriptionState.fileName(): String = when (this) {
    TranscriptionState.Idle -> "Transkription"
    is TranscriptionState.Starting -> fileName
    is TranscriptionState.Running -> fileName
    is TranscriptionState.Completed -> fileName
    is TranscriptionState.Cancelled -> fileName
    is TranscriptionState.Failed -> fileName
}