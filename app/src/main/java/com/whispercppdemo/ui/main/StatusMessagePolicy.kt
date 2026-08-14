package de.matthiasennen.transcript.ui.main

/** Display priority for KanaBot status messages. */
enum class StatusMessageKind {
    PROGRESS,
    IMPORTANT,
    COMPLETION,
    ERROR
}

internal const val IMPORTANT_STATUS_VISIBLE_MS = 3_600L

internal fun statusMinimumVisibleMs(kind: StatusMessageKind): Long = when (kind) {
    StatusMessageKind.IMPORTANT -> IMPORTANT_STATUS_VISIBLE_MS
    StatusMessageKind.PROGRESS,
    StatusMessageKind.COMPLETION,
    StatusMessageKind.ERROR -> 0L
}

/**
 * Progress may replace progress immediately, but it cannot hide a currently
 * held important event. Terminal information always wins immediately.
 */
internal fun shouldReplaceVisibleStatus(
    visibleKind: StatusMessageKind,
    incomingKind: StatusMessageKind,
    visibleUntilMs: Long,
    nowMs: Long
): Boolean = when (incomingKind) {
    StatusMessageKind.ERROR,
    StatusMessageKind.COMPLETION,
    StatusMessageKind.IMPORTANT -> true
    StatusMessageKind.PROGRESS ->
        visibleKind != StatusMessageKind.IMPORTANT || nowMs >= visibleUntilMs
}

internal fun TranscriptUiState.runtimeStatus(): String? = when {
    isTranscribing -> transcriptionRuntimeDisplay(elapsedSeconds, transcriptionEstimateSeconds)
    transcriptionDurationSeconds != null ->
        "Gesamtlaufzeit: ${formatClock(transcriptionDurationSeconds)}"
    else -> null
}
