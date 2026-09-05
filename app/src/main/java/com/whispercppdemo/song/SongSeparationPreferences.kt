package de.matthiasennen.transcript.song

import android.content.Context
import java.io.File

private const val PREFERENCES_NAME = "song_separation_preferences"
private const val SELECTED_MODEL_KEY = "selected_song_separation_model"
private const val PERFORMANCE_THREADS_PREFIX = "performance_threads_"
private const val PERFORMANCE_BACKEND_PREFIX = "performance_backend_"

enum class SongSeparationBackend(val label: String) {
    AUTO("Automatisch / Vulkan wenn verfügbar"),
    CPU("CPU"),
    VULKAN("Vulkan bevorzugen")
}

data class SongSeparationPerformanceConfiguration(
    val threads: Int = 1,
    val backend: SongSeparationBackend = SongSeparationBackend.CPU
) {
    fun normalized(
        model: SongSeparationModel,
        processors: Int = Runtime.getRuntime().availableProcessors()
    ): SongSeparationPerformanceConfiguration {
        val maximumThreads = processors.coerceIn(1, 8)
        return copy(
            threads = threads.coerceIn(1, maximumThreads),
            backend = if (model == SongSeparationModel.NATIVE_GGUF) {
                backend
            } else {
                SongSeparationBackend.CPU
            }
        )
    }
}

fun defaultSongSeparationPerformance(
    model: SongSeparationModel
): SongSeparationPerformanceConfiguration = when (model) {
    SongSeparationModel.QUICK,
    SongSeparationModel.BALANCED -> SongSeparationPerformanceConfiguration(
        threads = 1,
        backend = SongSeparationBackend.CPU
    )
    SongSeparationModel.NATIVE_GGUF -> SongSeparationPerformanceConfiguration(
        threads = 1,
        backend = SongSeparationBackend.AUTO
    )
    SongSeparationModel.HIGH_QUALITY -> SongSeparationPerformanceConfiguration(
        threads = 1,
        backend = SongSeparationBackend.CPU
    )
}

object SongSeparationRuntime {
    @Volatile
    var currentModel: SongSeparationModel = SongSeparationModel.BALANCED
        private set

    @Volatile
    var currentPerformance: SongSeparationPerformanceConfiguration =
        defaultSongSeparationPerformance(currentModel)
        private set

    fun use(model: SongSeparationModel, performance: SongSeparationPerformanceConfiguration) {
        currentModel = model
        currentPerformance = performance.normalized(model)
    }
}

class SongSeparationPreferences(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSelectedModel(): SongSeparationModel {
        val model = SongSeparationModel.fromId(preferences.getString(SELECTED_MODEL_KEY, null))
        SongSeparationRuntime.use(model, loadPerformance(model))
        return model
    }

    fun saveSelectedModel(model: SongSeparationModel) {
        preferences.edit().putString(SELECTED_MODEL_KEY, model.id).apply()
        SongSeparationRuntime.use(model, loadPerformance(model))
    }

    fun loadPerformance(model: SongSeparationModel): SongSeparationPerformanceConfiguration {
        val defaults = defaultSongSeparationPerformance(model)
        val storedBackend = preferences.getString(PERFORMANCE_BACKEND_PREFIX + model.id, null)
        val backend = SongSeparationBackend.entries.firstOrNull { it.name == storedBackend }
            ?: defaults.backend
        return SongSeparationPerformanceConfiguration(
            threads = preferences.getInt(PERFORMANCE_THREADS_PREFIX + model.id, defaults.threads),
            backend = backend
        ).normalized(model)
    }

    fun savePerformance(
        model: SongSeparationModel,
        performance: SongSeparationPerformanceConfiguration
    ): SongSeparationPerformanceConfiguration {
        val previous = loadPerformance(model)
        val normalized = performance.normalized(model)
        preferences.edit()
            .putInt(PERFORMANCE_THREADS_PREFIX + model.id, normalized.threads)
            .putString(PERFORMANCE_BACKEND_PREFIX + model.id, normalized.backend.name)
            .apply()
        if (SongSeparationRuntime.currentModel == model) {
            SongSeparationRuntime.use(model, normalized)
        }
        if (previous != normalized) invalidatePreparedSongTracks()
        return normalized
    }

    fun resetPerformance(model: SongSeparationModel): SongSeparationPerformanceConfiguration {
        val previous = loadPerformance(model)
        preferences.edit()
            .remove(PERFORMANCE_THREADS_PREFIX + model.id)
            .remove(PERFORMANCE_BACKEND_PREFIX + model.id)
            .apply()
        val defaults = defaultSongSeparationPerformance(model).normalized(model)
        if (SongSeparationRuntime.currentModel == model) {
            SongSeparationRuntime.use(model, defaults)
        }
        if (previous != defaults) invalidatePreparedSongTracks()
        return defaults
    }

    private fun invalidatePreparedSongTracks() {
        val directory = File(applicationContext.filesDir, "song-prepared")
        directory.listFiles().orEmpty().forEach { file -> runCatching(file::delete) }
    }
}
