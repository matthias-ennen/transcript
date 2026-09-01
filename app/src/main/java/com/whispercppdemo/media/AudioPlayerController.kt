package de.matthiasennen.transcript.media

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import de.matthiasennen.transcript.song.SongPlaybackSourceRuntime

class AudioPlayerController(
    private val context: Context,
    private val onPrepared: (durationMs: Long) -> Unit,
    private val onCompletion: () -> Unit,
    private val onError: (String) -> Unit
) {
    private var player: MediaPlayer? = null
    private var baseUri: Uri? = null
    private var currentUri: Uri? = null
    private var startWhenPrepared = false
    private var pendingSeekMs: Long? = null
    private var isPrepared = false
    private var preparedDurationMs = 0L

    init {
        SongPlaybackSourceRuntime.attach(context)
        SongPlaybackSourceRuntime.registerSourceChangeListener(::switchPlaybackSource)
    }

    fun toggle(uri: Uri): Boolean {
        baseUri = uri
        val playbackUri = SongPlaybackSourceRuntime.resolvePlaybackUri(uri)
        val active = player
        if (active != null && currentUri == playbackUri) {
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
        prepareResolved(
            originalUri = uri,
            playbackUri = playbackUri,
            autoStart = true
        )
        return true
    }

    fun prepare(uri: Uri, autoStart: Boolean = false) {
        prepareResolved(
            originalUri = uri,
            playbackUri = SongPlaybackSourceRuntime.resolvePlaybackUri(uri),
            autoStart = autoStart
        )
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
        releasePlayer()
        baseUri = null
    }

    private fun switchPlaybackSource() {
        val originalUri = baseUri ?: return
        val playbackUri = SongPlaybackSourceRuntime.resolvePlaybackUri(originalUri)
        if (playbackUri == currentUri) return
        val position = positionMs()
        val continuePlaying = isPlaying() || startWhenPrepared
        prepareResolved(
            originalUri = originalUri,
            playbackUri = playbackUri,
            autoStart = continuePlaying,
            seekMs = position
        )
    }

    private fun prepareResolved(
        originalUri: Uri,
        playbackUri: Uri,
        autoStart: Boolean,
        seekMs: Long? = null
    ) {
        releasePlayer()
        baseUri = originalUri
        currentUri = playbackUri
        startWhenPrepared = autoStart
        pendingSeekMs = seekMs
        isPrepared = false
        preparedDurationMs = 0L
        player = MediaPlayer().apply {
            setOnPreparedListener { prepared ->
                isPrepared = true
                preparedDurationMs = prepared.duration.toLong().coerceAtLeast(0L)
                pendingSeekMs?.let { position ->
                    prepared.seekTo(position.coerceIn(0L, preparedDurationMs).toInt())
                }
                pendingSeekMs = null
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
            setDataSource(context, playbackUri)
            prepareAsync()
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        currentUri = null
        startWhenPrepared = false
        pendingSeekMs = null
        isPrepared = false
        preparedDurationMs = 0L
    }
}
