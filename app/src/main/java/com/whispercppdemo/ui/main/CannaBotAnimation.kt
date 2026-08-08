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
    RUNNING_RIGHT(row = 1, frameCount = 8, frameDurationMs = 110L),
    RUNNING_LEFT(row = 2, frameCount = 8, frameDurationMs = 110L),
    WAVING(row = 3, frameCount = 4, frameDurationMs = 150L),
    JUMPING(row = 4, frameCount = 5, frameDurationMs = 130L),
    FAILED(row = 5, frameCount = 8, frameDurationMs = 150L),
    WAITING(row = 6, frameCount = 6, frameDurationMs = 180L),
    RUNNING(row = 7, frameCount = 6, frameDurationMs = 100L),
    REVIEW(row = 8, frameCount = 6, frameDurationMs = 170L)
}

@Composable
fun CannaBotStatusAnimation(
    state: TranscriptUiState,
    modifier: Modifier = Modifier
) {
    val baseAnimation = when (state.cannaBotMode) {
        CannaBotMode.IDLE -> CannaBotAnimation.IDLE
        CannaBotMode.WAITING -> CannaBotAnimation.WAITING
        CannaBotMode.REVIEW -> CannaBotAnimation.REVIEW
        CannaBotMode.RUNNING -> CannaBotAnimation.RUNNING
    }
    var eventAnimation by remember { mutableStateOf<CannaBotAnimation?>(null) }
    val animation = eventAnimation ?: baseAnimation

    LaunchedEffect(state.cannaBotCueId) {
        suspend fun playOnce(value: CannaBotAnimation) {
            eventAnimation = value
            delay(value.frameCount * value.frameDurationMs)
        }

        when (state.cannaBotCue) {
            CannaBotCue.RUNNING_RIGHT -> playOnce(CannaBotAnimation.RUNNING_RIGHT)
            CannaBotCue.RUNNING_LEFT -> playOnce(CannaBotAnimation.RUNNING_LEFT)
            CannaBotCue.JUMPING -> playOnce(CannaBotAnimation.JUMPING)
            CannaBotCue.WAVING -> playOnce(CannaBotAnimation.WAVING)
            CannaBotCue.SUCCESS -> {
                playOnce(CannaBotAnimation.JUMPING)
                playOnce(CannaBotAnimation.WAVING)
            }
            CannaBotCue.FAILED -> playOnce(CannaBotAnimation.FAILED)
            CannaBotCue.NONE -> Unit
        }
        eventAnimation = null
    }

    CannaBotSprite(animation = animation, modifier = modifier)
}

/**
 * Plays one calm prompt sequence whenever the share dialog opens. Short idle
 * pauses keep the three gestures distinct before CannaBot settles down again.
 */
@Composable
internal fun CannaBotSharePromptAnimation(
    modifier: Modifier = Modifier
) {
    var animation by remember { mutableStateOf(CannaBotAnimation.IDLE) }

    LaunchedEffect(Unit) {
        delay(650L)
        animation = CannaBotAnimation.RUNNING_RIGHT
        delay(CannaBotAnimation.RUNNING_RIGHT.playbackDurationMs)

        animation = CannaBotAnimation.IDLE
        delay(650L)
        animation = CannaBotAnimation.JUMPING
        delay(CannaBotAnimation.JUMPING.playbackDurationMs)

        animation = CannaBotAnimation.IDLE
        delay(650L)
        animation = CannaBotAnimation.WAVING
        delay(CannaBotAnimation.WAVING.playbackDurationMs * 2)

        animation = CannaBotAnimation.IDLE
    }

    CannaBotSprite(animation = animation, modifier = modifier)
}

private val CannaBotAnimation.playbackDurationMs: Long
    get() = frameCount * frameDurationMs

@Composable
private fun CannaBotSprite(
    animation: CannaBotAnimation,
    modifier: Modifier = Modifier
) {
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
