package de.matthiasennen.transcript.song

internal data class SongWorkerConfiguration(
    val mode: TranscriptionMode,
    val model: SongSeparationModel,
    val threads: Int,
    val backend: SongSeparationBackend
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
        threads = 1,
        backend = SongSeparationBackend.CPU
    )

    fun update(
        mode: TranscriptionMode,
        modelId: String,
        threads: Int,
        backend: SongSeparationBackend
    ) {
        val model = SongSeparationModel.fromId(modelId)
        val performance = SongSeparationPerformanceConfiguration(
            threads = threads,
            backend = backend
        ).normalized(model)
        configuration = SongWorkerConfiguration(
            mode = mode,
            model = model,
            threads = performance.threads,
            backend = performance.backend
        )
    }

    fun current(): SongWorkerConfiguration = configuration
}
