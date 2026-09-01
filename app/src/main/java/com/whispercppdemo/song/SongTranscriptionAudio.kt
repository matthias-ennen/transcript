package de.matthiasennen.transcript.song

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import de.matthiasennen.transcript.media.DecodedAudioChunk
import de.matthiasennen.transcript.media.analyzeAudioSamples

internal fun decodeAndSeparateSongChunk(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    configuration: SongWorkerConfiguration,
    shouldCancel: () -> Boolean = { false },
    onProgress: (Float) -> Unit = {}
): DecodedAudioChunk {
    require(configuration.mode == TranscriptionMode.SONG)
    require(endMs > startMs)

    val preparedTrack = ensurePreparedSongTrack(
        context = context,
        uri = uri,
        configuration = configuration,
        shouldCancel = shouldCancel,
        onProgress = onProgress
    )
    if (shouldCancel()) throw java.util.concurrent.CancellationException("Stimmisolierung abgebrochen.")

    val samples = readPreparedSongSamples(
        track = preparedTrack,
        startMs = startMs,
        endMs = endMs
    )
    onProgress(1f)
    return DecodedAudioChunk(
        samples = samples,
        decodeStartMs = startMs,
        decodeEndMs = endMs,
        sourceSampleRate = SONG_PREPARED_SAMPLE_RATE,
        sourceChannelCount = 1,
        mimeType = "audio/wav+song-separated",
        pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
        discardedTrailingSamples = 0,
        diagnostics = analyzeAudioSamples(samples)
    )
}

internal fun separatorWindowStarts(startMs: Long, endMs: Long): List<Long> {
    require(endMs > startMs)
    val starts = mutableListOf<Long>()
    var position = startMs
    while (position < endMs) {
        starts += position
        if (position + SONG_SEPARATOR_WINDOW_MS >= endMs) break
        position += SONG_SEPARATOR_STEP_MS
    }
    return starts
}
