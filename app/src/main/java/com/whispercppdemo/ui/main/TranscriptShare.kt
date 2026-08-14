package de.matthiasennen.transcript.ui.main

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.matthiasennen.transcript.export.ExportFormat
import de.matthiasennen.transcript.export.TranscriptExportMetadata
import de.matthiasennen.transcript.export.exportTranscript
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptShareDialog(
    onDismiss: () -> Unit,
    onShare: (Set<ExportFormat>) -> Result<Unit>
) {
    var selectedFormats by remember { mutableStateOf(setOf(ExportFormat.TEXT)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val transition = rememberInfiniteTransition(label = "share-question-pulse")
    val questionAlpha = transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "share-question-alpha"
    ).value

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CannaBotSharePromptAnimation()
                    Text(
                        text = "Welche Formate möchten Sie teilen?",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = questionAlpha)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportFormat.entries.forEach { format ->
                        FilterChip(
                            selected = format in selectedFormats,
                            onClick = {
                                selectedFormats = if (format in selectedFormats) {
                                    selectedFormats - format
                                } else {
                                    selectedFormats + format
                                }
                                errorMessage = null
                            },
                            label = { Text(format.buttonLabel) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onShare(selectedFormats)
                        .onSuccess { onDismiss() }
                        .onFailure {
                            errorMessage = "Die ausgewählten Dateien konnten nicht geteilt werden."
                        }
                },
                enabled = selectedFormats.isNotEmpty(),
                shape = RoundedCornerShape(50)
            ) {
                Text("Teilen")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(50)
            ) {
                Text("Zurück")
            }
        }
    )
}

internal val ExportFormat.buttonLabel: String
    get() = when (this) {
        ExportFormat.TEXT -> "TXT"
        ExportFormat.SUBRIP -> "SRT"
        ExportFormat.JSON -> "JSON"
    }

internal fun shareTranscript(
    context: Context,
    state: TranscriptUiState,
    formats: Set<ExportFormat>
): Result<Unit> = runCatching {
    require(state.segments.isNotEmpty()) { "No transcript is available" }

    val orderedFormats = orderedShareFormats(formats)
    require(orderedFormats.isNotEmpty()) { "At least one format is required" }

    val shareDirectory = File(context.cacheDir, SHARED_TRANSCRIPTS_DIRECTORY).apply {
        check(exists() || mkdirs()) { "Share directory could not be created" }
    }
    val metadata = state.exportMetadata()
    val sharedUris = orderedFormats.map { format ->
        val file = File(shareDirectory, transcriptExportFileName(state, format))
        file.writeText(
            exportTranscript(
                segments = state.segments,
                format = format,
                metadata = metadata,
                rawWhisperSegments = state.rawWhisperSegments
            ),
            Charsets.UTF_8
        )
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    val shareIntent = buildShareIntent(context, orderedFormats, sharedUris)
    context.startActivity(Intent.createChooser(shareIntent, "Transkript teilen"))
}

private const val SHARED_TRANSCRIPTS_DIRECTORY = "shared_transcripts"

internal fun orderedShareFormats(formats: Set<ExportFormat>): List<ExportFormat> =
    ExportFormat.entries.filter(formats::contains)

internal fun transcriptExportFileName(
    state: TranscriptUiState,
    format: ExportFormat
): String {
    val sourceBaseName = state.selectedFileName
        ?.substringBeforeLast('.')
        ?.trim()
        ?.ifBlank { null }
        ?: "Transcript"
    val safeBaseName = sourceBaseName
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .take(100)
        .ifBlank { "Transcript" }
    return "$safeBaseName Transcript.${format.extension}"
}

internal fun TranscriptUiState.exportMetadata(
    createdAt: String = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
): TranscriptExportMetadata = TranscriptExportMetadata(
    whisperModel = completedModel?.modelLabel ?: selectedModel.modelLabel,
    detectedLanguage = detectedLanguage ?: "unknown",
    transcriptionDurationSeconds = transcriptionDurationSeconds ?: 0L,
    createdAt = createdAt
)

private fun buildShareIntent(
    context: Context,
    formats: List<ExportFormat>,
    uris: List<Uri>
): Intent {
    val action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
    return Intent(action).apply {
        type = if (formats.size == 1) formats.single().mimeType else "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_SUBJECT, "Transkript")
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        clipData = ClipData.newUri(context.contentResolver, "Transkript", uris.first()).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }
    }
}
