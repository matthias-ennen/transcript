package de.matthiasennen.transcript.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

class AudioPlayerController(
    private val context: Context,
    private val onPrepared: (durationMs: Long) -> Unit,
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var player: MediaPlayer? = null
    private var currentUri: Uri? = null
    private var startWhenPrepared = false
    private var isPrepared = false
    private var preparedDurationMs = 0L

    fun toggle(uri: Uri): Boolean {
        val active = player
        if (active != null && currentUri == uri) {
            if (!isPrepared) {
                startWhenPrepared = true
                return true
            }
            return if (active.isPlaying) {
                active.pause()
                false
            } else {
                active.start()
                true
            }
        }
        prepare(uri, autoStart = true)
        return true
    }

    fun prepare(uri: Uri, autoStart: Boolean = false) {
        release()
        currentUri = uri
        startWhenPrepared = autoStart
        isPrepared = false
        preparedDurationMs = 0L
        player = MediaPlayer().apply {
            setOnPreparedListener { prepared ->
                isPrepared = true
                preparedDurationMs = prepared.duration.toLong().coerceAtLeast(0L)
                onPrepared(preparedDurationMs)
                if (startWhenPrepared) prepared.start()
            }
            setOnCompletionListener {
                onCompletion()
            }
            setOnErrorListener { _, what, extra ->
                onError("Audiowiedergabe fehlgeschlagen ($what/$extra).")
                true
            }
            setDataSource(context, uri)
            prepareAsync()
        }
    }

    fun seekTo(positionMs: Long) {
        if (isPrepared) player?.seekTo(positionMs.coerceIn(0L, durationMs()).toInt())
    }

    fun restartFrom(positionMs: Long): Boolean {
        val active = player ?: return false
        if (!isPrepared) return false
        active.seekTo(positionMs.coerceIn(0L, durationMs()).toInt())
        if (!active.isPlaying) active.start()
        return true
    }

    fun positionMs(): Long = if (isPrepared) player?.currentPosition?.toLong() ?: 0L else 0L

    fun durationMs(): Long = preparedDurationMs

    fun isPlaying(): Boolean = isPrepared && player?.isPlaying == true

    fun pause() {
        if (isPrepared) player?.takeIf { it.isPlaying }?.pause()
    }

    fun release() {
        player?.release()
        player = null
        currentUri = null
        startWhenPrepared = false
        isPrepared = false
        preparedDurationMs = 0L
    }
}
