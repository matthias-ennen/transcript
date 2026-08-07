package de.matthiasennen.transcript.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.matthiasennen.transcript.R
import kotlinx.coroutines.delay

private const val FRAME_WIDTH_PX = 192
private const val FRAME_HEIGHT_PX = 208

private enum class CannaBotAnimation(
    val row: Int,
    val frameCount: Int,
    val frameDurationMs: Long
) {
    IDLE(row = 0, frameCount = 6, frameDurationMs = 180L),
    WAVING(row = 3, frameCount = 4, frameDurationMs = 150L),
    FAILED(row = 5, frameCount = 8, frameDurationMs = 150L),
    WAITING(row = 6, frameCount = 6, frameDurationMs = 180L),
    RUNNING(row = 7, frameCount = 6, frameDurationMs = 100L),
    REVIEW(row = 8, frameCount = 6, frameDurationMs = 170L)
}

@Composable
fun CannaBotTitleAnimation(
    state: TranscriptUiState,
    modifier: Modifier = Modifier
) {
    val animation = when {
        state.error != null -> CannaBotAnimation.FAILED
        state.isCancellationRequested -> CannaBotAnimation.WAITING
        state.isTranscribing || state.isRecording -> CannaBotAnimation.RUNNING
        state.downloadingModel != null || state.isWaveformLoading -> CannaBotAnimation.WAITING
        state.segments.isNotEmpty() -> CannaBotAnimation.WAVING
        state.selectedAudio != null -> CannaBotAnimation.REVIEW
        else -> CannaBotAnimation.IDLE
    }
    val spriteSheet = ImageBitmap.imageResource(R.drawable.cannabot_spritesheet)
    var frame by remember(animation) { mutableStateOf(0) }

    LaunchedEffect(animation) {
        frame = 0
        while (true) {
            delay(animation.frameDurationMs)
            frame = (frame + 1) % animation.frameCount
        }
    }

    Canvas(modifier = modifier.size(width = 28.dp, height = 30.dp)) {
        drawImage(
            image = spriteSheet,
            srcOffset = IntOffset(
                x = frame * FRAME_WIDTH_PX,
                y = animation.row * FRAME_HEIGHT_PX
            ),
            srcSize = IntSize(FRAME_WIDTH_PX, FRAME_HEIGHT_PX),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = 1f,
            filterQuality = FilterQuality.None
        )
    }
}
