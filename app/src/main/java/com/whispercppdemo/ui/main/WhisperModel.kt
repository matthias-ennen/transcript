package de.matthiasennen.transcript.ui.main

enum class WhisperModel(
    val id: String,
    val qualityLabel: String,
    val modelLabel: String,
    val description: String,
    val fileName: String,
    val downloadSizeLabel: String,
    val minimumBytes: Long,
    val sha256: String
) {
    TINY(
        id = "tiny",
        qualityLabel = "Sehr schnell",
        modelLabel = "Whisper Tiny",
        description = "Besonders schnelle Ergebnisse bei geringerer Genauigkeit",
        fileName = "ggml-tiny.bin",
        downloadSizeLabel = "77,7 MB",
        minimumBytes = 70_000_000L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    ),
    BASE(
        id = "base",
        qualityLabel = "Schnell",
        modelLabel = "Whisper Base",
        description = "Für deutliche Sprache und schnelle Ergebnisse",
        fileName = "ggml-base.bin",
        downloadSizeLabel = "148 MB",
        minimumBytes = 140_000_000L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    ),
    SMALL_Q5_1(
        id = "small-q5_1",
        qualityLabel = "Ausgewogen",
        modelLabel = "Whisper Small Q5_1",
        description = "Bessere Qualität bei vertretbarer Laufzeit",
        fileName = "ggml-small-q5_1.bin",
        downloadSizeLabel = "190 MB",
        minimumBytes = 180_000_000L,
        sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    ),
    LARGE_V3_TURBO_Q5_0(
        id = "large-v3-turbo-q5_0",
        qualityLabel = "Sehr genau",
        modelLabel = "Whisper Large V3 Turbo Q5_0",
        description = "Hohe Qualität für schwierige Aufnahmen und Songs",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        downloadSizeLabel = "574 MB",
        minimumBytes = 540_000_000L,
        sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"
    ),
    LARGE_V3_Q5_0(
        id = "large-v3-q5_0",
        qualityLabel = "Maximale Qualität",
        modelLabel = "Whisper Large V3 Q5_0",
        description = "Höchste lokale Whisper-Qualität",
        fileName = "ggml-large-v3-q5_0.bin",
        downloadSizeLabel = "1,08 GB",
        minimumBytes = 1_000_000_000L,
        sha256 = "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1"
    );

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"

    val requiresCancellationConfirmation: Boolean
        get() = this != TINY && this != BASE

    companion object {
        fun fromId(id: String?): WhisperModel = entries.firstOrNull { it.id == id } ?: BASE
    }
}

data class ModelInstallation(
    val model: WhisperModel,
    val isInstalled: Boolean,
    val installedBytes: Long = 0L,
    val partialBytes: Long = 0L
) {
    val storedBytes: Long
        get() = installedBytes + partialBytes
}
