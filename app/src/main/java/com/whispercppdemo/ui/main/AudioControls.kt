package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.R

@Composable
fun AudioControls(
    state: TranscriptUiState,
    onRecordClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onPreviousSegmentClick: () -> Unit,
    onNextSegmentClick: () -> Unit,
    onSeek: (Float) -> Unit
) {
    val displayedWaveform = if (state.isRecording) state.liveWaveform else state.waveform
    val progress = if (state.audioDurationMs > 0L) {
        state.playbackPositionMs.toFloat() / state.audioDurationMs
    } else {
        0f
    }

    val segmentTransportEnabled = state.completedModel != null &&
        state.segments.isNotEmpty() && !state.isRecording && !state.isBusy

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TransportControlGrid {
            OutlinedIconButton(
                onClick = onPreviousSegmentClick,
                enabled = segmentTransportEnabled,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = "Zum vorherigen Textabschnitt"
                )
            }

            OutlinedIconButton(
                onClick = onPlayPauseClick,
                enabled = state.selectedAudio != null && !state.isRecording && !state.isBusy,
                modifier = Modifier.size(52.dp)
            ) {
                PlaybackIcon(state.isPlaying)
            }

            OutlinedIconButton(
                onClick = onNextSegmentClick,
                enabled = segmentTransportEnabled,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = "Zum nächsten Textabschnitt"
                )
            }

            FilledIconButton(
                onClick = onRecordClick,
                enabled = !state.isBusy,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isRecording) R.drawable.ic_stop else R.drawable.ic_mic
                    ),
                    contentDescription = if (state.isRecording) {
                        "Aufnahme beenden"
                    } else {
                        "Aufnahme starten"
                    }
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Waveform(
                values = displayedWaveform,
                progress = progress,
                seekEnabled = !state.isRecording && !state.isBusy && state.audioDurationMs > 0L,
                onSeek = onSeek,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (state.isRecording) "Aufnahme läuft" else formatClock(state.playbackPositionMs / 1_000L),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    if (state.isRecording) {
                        "Stopp über Mikrofon-Taste"
                    } else {
                        formatClock(state.audioDurationMs / 1_000L)
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
internal fun TransportControlGrid(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
internal fun PlaybackIcon(isPlaying: Boolean) {
    Icon(
        painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
        contentDescription = if (isPlaying) "Wiedergabe pausieren" else "Wiedergabe starten"
    )
}

@Composable
private fun Waveform(
    values: List<Float>,
    progress: Float,
    seekEnabled: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val remainingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    val markerColor = MaterialTheme.colorScheme.tertiary
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val gestureModifier = if (seekEnabled) {
        Modifier
            .pointerInput(onSeek) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }
            .pointerInput(onSeek) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        onSeek((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                        change.consume()
                    }
                )
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .then(gestureModifier)
    ) {
        val centerY = size.height / 2f
        if (values.isEmpty()) {
            drawLine(
                color = remainingColor,
                start = Offset(8f, centerY),
                end = Offset(size.width - 8f, centerY),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        } else {
            val slotWidth = size.width / values.size
            val strokeWidth = (slotWidth * 0.56f).coerceAtLeast(1f)
            values.forEachIndexed { index, amplitude ->
                val x = (index + 0.5f) * slotWidth
                val halfHeight = (amplitude.coerceIn(0.04f, 1f) * size.height * 0.42f)
                drawLine(
                    color = if (x <= progress.coerceIn(0f, 1f) * size.width) {
                        playedColor
                    } else {
                        remainingColor
                    },
                    start = Offset(x, centerY - halfHeight),
                    end = Offset(x, centerY + halfHeight),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }
        if (seekEnabled) {
            val markerX = progress.coerceIn(0f, 1f) * size.width
            drawLine(
                color = markerColor,
                start = Offset(markerX, 0f),
                end = Offset(markerX, size.height),
                strokeWidth = 3f
            )
            drawCircle(color = markerColor, radius = 6f, center = Offset(markerX, centerY))
        }
    }
}
