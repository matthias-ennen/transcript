package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.R

@Composable
internal fun FloatingTranscriptControls(
    isPlaying: Boolean,
    playbackEnabled: Boolean,
    onPreviousSegmentClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextSegmentClick: () -> Unit,
    onScrollToTopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.62f),
        contentColor = MaterialTheme.colorScheme.onPrimary
    )
    Box(modifier = modifier.fillMaxWidth()) {
        TransportControlGrid(modifier = Modifier.padding(horizontal = 16.dp)) {
            FloatingTransportButton(
                onClick = onPreviousSegmentClick,
                enabled = playbackEnabled,
                colors = colors
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = "Zum vorherigen Textabschnitt"
                )
            }
            FloatingTransportButton(
                onClick = onPlayPauseClick,
                enabled = playbackEnabled,
                colors = colors
            ) {
                PlaybackIcon(isPlaying)
            }
            FloatingTransportButton(
                onClick = onNextSegmentClick,
                enabled = playbackEnabled,
                colors = colors
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = "Zum nächsten Textabschnitt"
                )
            }
            Spacer(Modifier.width(52.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
        ) {
            Button(
                onClick = onScrollToTopClick,
                modifier = Modifier
                    .width(58.dp)
                    .height(44.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp),
                colors = colors
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Zum Anfang der App"
                )
            }
        }
    }
}

@Composable
private fun FloatingTransportButton(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: androidx.compose.material3.ButtonColors,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .width(52.dp)
            .height(44.dp),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(0.dp),
        colors = colors,
        content = { content() }
    )
}
