package de.matthiasennen.transcript.transcription

internal const val TRANSCRIPTION_COMPLETE_TITLE = "Transkription abgeschlossen"
internal const val TRANSCRIPTION_COMPLETE_TEXT = "Das Transkript steht bereit."

internal data class TranscriptionNotificationContent(
    val title: String,
    val text: String
)

internal fun completedTranscriptionNotificationContent() = TranscriptionNotificationContent(
    title = TRANSCRIPTION_COMPLETE_TITLE,
    text = TRANSCRIPTION_COMPLETE_TEXT
)

internal fun shouldPublishCompletionNotification(
    lastCompletedJobId: String?,
    completedJobId: String
): Boolean = completedJobId.isNotBlank() && lastCompletedJobId != completedJobId
