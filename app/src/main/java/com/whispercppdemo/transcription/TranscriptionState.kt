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
import androidx.core.content.ContextCompat
import com.whispercpp.whisper.WhisperSegment
import de.matthiasennen.transcript.ui.main.WhisperModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val STATE_FILE_NAME = "active-transcription-state.bin"
private const val STATE_CHANGED_ACTION = "de.matthiasennen.transcript.TRANSCRIPTION_STATE_CHANGED"
private const val EXIT_CHECK_INTERVAL_MS = 3_000L

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

/** Process-safe bridge between the isolated native worker and the UI process. */
object TranscriptionCoordinator {
    private val mutableState = MutableStateFlow<TranscriptionState>(TranscriptionState.Idle)
    val state = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var applicationContext: Context? = null
    private var receiverRegistered = false
    private var observedEnvelope: PersistedTranscriptionState? = null

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
                val exit = findWorkerExit(context, envelope.workerStartedAtEpochMs)
                if (exit != null) {
                    val running = envelope.state as? TranscriptionState.Running
                    val failure = TranscriptionState.Failed(
                        fileName = envelope.state.fileName(),
                        message = workerExitMessage(exit),
                        canResume = running?.committedSegments?.isNotEmpty() == true,
                        committedSegments = running?.committedSegments.orEmpty()
                    )
                    val failedEnvelope = envelope.copy(
                        state = failure,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    stateStore(context).write(failedEnvelope)
                    observedEnvelope = failedEnvelope
                    mutableState.value = failure
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
        mutableState.value = TranscriptionState.Idle
        mainHandler.removeCallbacks(exitMonitor)
    }

    @Synchronized
    private fun refreshFromDisk(context: Context) {
        val envelope = stateStore(context).read()
        observedEnvelope = envelope
        mutableState.value = envelope?.state ?: TranscriptionState.Idle
        mainHandler.removeCallbacks(exitMonitor)
        if (envelope?.state?.isActive() == true) mainHandler.post(exitMonitor)
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
