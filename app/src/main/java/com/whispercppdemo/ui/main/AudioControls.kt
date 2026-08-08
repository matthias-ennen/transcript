package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    onSeek: (Float) -> Unit
) {
    val displayedWaveform = if (state.isRecording) state.liveWaveform else state.waveform
    val progress = if (state.audioDurationMs > 0L) {
        state.playbackPositionMs.toFloat() / state.audioDurationMs
    } else {
        0f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledIconButton(
            onClick = onRecordClick,
            enabled = !state.isBusy,
            modifier = Modifier.size(52.dp)
        ) {
            Icon(
                painter = painterResource(
                    if (state.isRecording) R.drawable.ic_stop else R.drawable.ic_mic
                ),
                contentDescription = if (state.isRecording) "Aufnahme beenden" else "Aufnahme starten"
            )
        }

        OutlinedIconButton(
            onClick = onPlayPauseClick,
            enabled = state.selectedAudio != null && !state.isRecording && !state.isBusy,
            modifier = Modifier.size(52.dp)
        ) {
            Icon(
                painter = painterResource(
                    if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = if (state.isPlaying) "Wiedergabe pausieren" else "Wiedergabe starten"
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Waveform(
                values = displayedWaveform,
                progress = progress,
                seekEnabled = !state.isRecording && state.audioDurationMs > 0L,
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
