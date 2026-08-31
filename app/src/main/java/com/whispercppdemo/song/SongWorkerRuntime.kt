package de.matthiasennen.transcript.song

internal data class SongWorkerConfiguration(
    val mode: TranscriptionMode,
    val model: SongSeparationModel,
    val threads: Int
)

/**
 * Process-local mirror of the already encoded worker snapshot. It lets the shared
 * decoder choose the Song preprocessing path without coupling UI state to the
 * background worker. TranscriptionJobConfiguration re-populates it after restart.
 */
internal object SongWorkerRuntime {
    @Volatile
    private var configuration = SongWorkerConfiguration(
        mode = TranscriptionMode.SPEECH,
        model = SongSeparationModel.BALANCED,
        threads = 2
    )

    fun update(mode: TranscriptionMode, modelId: String, threads: Int) {
        configuration = SongWorkerConfiguration(
            mode = mode,
            model = SongSeparationModel.fromId(modelId),
            threads = threads.coerceIn(1, 8)
        )
    }

    fun current(): SongWorkerConfiguration = configuration
}
