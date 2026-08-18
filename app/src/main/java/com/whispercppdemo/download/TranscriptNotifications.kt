package de.matthiasennen.transcript.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import de.matthiasennen.transcript.MainActivity
import de.matthiasennen.transcript.R

object TranscriptNotifications {
    const val WHISPER_MODEL_DOWNLOAD_ID = 2107
    const val TRANSCRIPTION_ID = 2108
    const val TRANSCRIPTION_COMPLETION_ID = 2109
    const val RECORDING_ID = 2110
    const val AI_PROCESSING_ID = 2111
    const val VAD_MODEL_DOWNLOAD_ID = 2112
    const val AI_MODEL_DOWNLOAD_ID = 2113

    val SMALL_ICON = R.drawable.ic_transcript_notification

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
