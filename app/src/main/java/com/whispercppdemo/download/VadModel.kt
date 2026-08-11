package de.matthiasennen.transcript.download

data object SileroVadModel {
    const val modelLabel = "Silero VAD 6.2.0"
    const val fileName = "ggml-silero-v6.2.0.bin"
    const val downloadUrl =
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
    const val expectedBytes = 885_098L
    const val minimumBytes = 880_000L
    const val sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"
}

data class VadModelInstallation(
    val isInstalled: Boolean = false,
    val installedBytes: Long = 0L,
    val partialBytes: Long = 0L
) {
    val storedBytes: Long get() = installedBytes + partialBytes
}
