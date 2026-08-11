package com.whispercpp.whisper

data class WhisperConfiguration(
    val threads: Int = 0,
    val useGpu: Boolean = true,
    val beamSearch: Boolean = false,
    val beamSize: Int = 5,
    val bestOf: Int = 2,
    val temperature: Float = 0f,
    val initialPrompt: String = "",
    val carryContext: Boolean = true,
    val maximumSegmentCharacters: Int = 0,
    val splitOnWord: Boolean = true,
    val tokenTimestamps: Boolean = false,
    val suppressBlank: Boolean = true,
    val suppressNonSpeechTokens: Boolean = false,
    val logProbabilityThreshold: Float = -1f,
    val noSpeechThreshold: Float = 0.6f,
    val entropyThreshold: Float = 2.4f
)
