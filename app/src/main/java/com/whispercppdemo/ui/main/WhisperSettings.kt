package de.matthiasennen.transcript.ui.main

import android.content.Context
import com.whispercpp.whisper.WhisperConfiguration

enum class WhisperComputeBackend { AUTO, CPU, VULKAN }
enum class WhisperDecoding { GREEDY, BEAM_SEARCH }
enum class WhisperTimestampMode { SEGMENTS, WORDS }

data class WhisperSettings(
    val initialPrompt: String = "",
    val threads: Int = 0,
    val backend: WhisperComputeBackend = WhisperComputeBackend.AUTO,
    val decoding: WhisperDecoding = WhisperDecoding.GREEDY,
    val beamSize: Int = 5,
    val bestOf: Int = 2,
    val temperaturePercent: Int = 0,
    val carryContext: Boolean = true,
    val maximumSegmentCharacters: Int = 0,
    val splitOnWord: Boolean = true,
    val timestampMode: WhisperTimestampMode = WhisperTimestampMode.SEGMENTS,
    val suppressBlank: Boolean = true,
    val suppressNonSpeechTokens: Boolean = false,
    val logProbabilityThresholdPercent: Int = -100,
    val noSpeechThresholdPercent: Int = 60,
    val entropyThresholdPercent: Int = 240,
    val sectionMinutes: Int = 5
) {
    fun normalized(processors: Int = Runtime.getRuntime().availableProcessors()): WhisperSettings = copy(
        threads = threads.coerceIn(0, processors.coerceAtLeast(1)),
        beamSize = beamSize.coerceIn(1, 20),
        bestOf = bestOf.coerceIn(1, 20),
        temperaturePercent = temperaturePercent.coerceIn(0, 100),
        maximumSegmentCharacters = maximumSegmentCharacters.coerceIn(0, 500),
        logProbabilityThresholdPercent = logProbabilityThresholdPercent.coerceIn(-500, 0),
        noSpeechThresholdPercent = noSpeechThresholdPercent.coerceIn(0, 100),
        entropyThresholdPercent = entropyThresholdPercent.coerceIn(0, 500),
        sectionMinutes = sectionMinutes.coerceIn(1, 10)
    )

    fun toNativeConfiguration(): WhisperConfiguration {
        val value = normalized()
        return WhisperConfiguration(
            threads = value.threads,
            useGpu = value.backend != WhisperComputeBackend.CPU,
            beamSearch = value.decoding == WhisperDecoding.BEAM_SEARCH,
            beamSize = value.beamSize,
            bestOf = value.bestOf,
            temperature = value.temperaturePercent / 100f,
            initialPrompt = value.initialPrompt,
            carryContext = value.carryContext,
            maximumSegmentCharacters = value.maximumSegmentCharacters,
            splitOnWord = value.splitOnWord,
            tokenTimestamps = value.timestampMode == WhisperTimestampMode.WORDS,
            suppressBlank = value.suppressBlank,
            suppressNonSpeechTokens = value.suppressNonSpeechTokens,
            logProbabilityThreshold = value.logProbabilityThresholdPercent / 100f,
            noSpeechThreshold = value.noSpeechThresholdPercent / 100f,
            entropyThreshold = value.entropyThresholdPercent / 100f
        )
    }
}

enum class WhisperSettingsGroup { COMPUTE, DETECTION, DECODING, SEGMENTS, PROTECTION }

fun WhisperSettings.reset(group: WhisperSettingsGroup): WhisperSettings {
    val defaults = WhisperSettings()
    return when (group) {
        WhisperSettingsGroup.COMPUTE -> copy(threads = defaults.threads, backend = defaults.backend)
        WhisperSettingsGroup.DETECTION -> copy(initialPrompt = defaults.initialPrompt)
        WhisperSettingsGroup.DECODING -> copy(
            decoding = defaults.decoding,
            beamSize = defaults.beamSize,
            bestOf = defaults.bestOf,
            temperaturePercent = defaults.temperaturePercent,
            carryContext = defaults.carryContext
        )
        WhisperSettingsGroup.SEGMENTS -> copy(
            maximumSegmentCharacters = defaults.maximumSegmentCharacters,
            splitOnWord = defaults.splitOnWord,
            timestampMode = defaults.timestampMode,
            sectionMinutes = defaults.sectionMinutes
        )
        WhisperSettingsGroup.PROTECTION -> copy(
            suppressBlank = defaults.suppressBlank,
            suppressNonSpeechTokens = defaults.suppressNonSpeechTokens,
            logProbabilityThresholdPercent = defaults.logProbabilityThresholdPercent,
            noSpeechThresholdPercent = defaults.noSpeechThresholdPercent,
            entropyThresholdPercent = defaults.entropyThresholdPercent
        )
    }
}

class WhisperSettingsPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("whisper_settings", Context.MODE_PRIVATE)

    fun load(): WhisperSettings = WhisperSettings(
        initialPrompt = preferences.getString("initial_prompt", "").orEmpty(),
        threads = preferences.getInt("threads", 0),
        backend = enumValue(preferences.getString("backend", null), WhisperComputeBackend.AUTO),
        decoding = enumValue(preferences.getString("decoding", null), WhisperDecoding.GREEDY),
        beamSize = preferences.getInt("beam_size", 5),
        bestOf = preferences.getInt("best_of", 2),
        temperaturePercent = preferences.getInt("temperature", 0),
        carryContext = preferences.getBoolean("carry_context", true),
        maximumSegmentCharacters = preferences.getInt("max_segment_characters", 0),
        splitOnWord = preferences.getBoolean("split_on_word", true),
        timestampMode = enumValue(preferences.getString("timestamps", null), WhisperTimestampMode.SEGMENTS),
        suppressBlank = preferences.getBoolean("suppress_blank", true),
        suppressNonSpeechTokens = preferences.getBoolean("suppress_non_speech", false),
        logProbabilityThresholdPercent = preferences.getInt("log_probability", -100),
        noSpeechThresholdPercent = preferences.getInt("no_speech", 60),
        entropyThresholdPercent = preferences.getInt("entropy", 240),
        sectionMinutes = preferences.getInt("section_minutes", 5)
    ).normalized()

    fun save(value: WhisperSettings) {
        val settings = value.normalized()
        preferences.edit()
            .putString("initial_prompt", settings.initialPrompt)
            .putInt("threads", settings.threads)
            .putString("backend", settings.backend.name)
            .putString("decoding", settings.decoding.name)
            .putInt("beam_size", settings.beamSize)
            .putInt("best_of", settings.bestOf)
            .putInt("temperature", settings.temperaturePercent)
            .putBoolean("carry_context", settings.carryContext)
            .putInt("max_segment_characters", settings.maximumSegmentCharacters)
            .putBoolean("split_on_word", settings.splitOnWord)
            .putString("timestamps", settings.timestampMode.name)
            .putBoolean("suppress_blank", settings.suppressBlank)
            .putBoolean("suppress_non_speech", settings.suppressNonSpeechTokens)
            .putInt("log_probability", settings.logProbabilityThresholdPercent)
            .putInt("no_speech", settings.noSpeechThresholdPercent)
            .putInt("entropy", settings.entropyThresholdPercent)
            .putInt("section_minutes", settings.sectionMinutes)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
