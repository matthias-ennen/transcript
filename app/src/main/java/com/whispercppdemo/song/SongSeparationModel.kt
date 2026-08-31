package de.matthiasennen.transcript.song

enum class SongSeparationModel(
    val id: String,
    val qualityLabel: String,
    val modelLabel: String,
    val description: String,
    val downloadSizeLabel: String,
    val recommended: Boolean
) {
    QUICK(
        id = "umxhq",
        qualityLabel = "Schnell",
        modelLabel = "Open-Unmix UMXHQ",
        description = "Kompakte Gesangstrennung mit geringem Speicherbedarf für schnelle lokale Verarbeitung.",
        downloadSizeLabel = "17 MB",
        recommended = false
    ),
    BALANCED(
        id = "spleeter-2stems-fp16",
        qualityLabel = "Ausgewogen",
        modelLabel = "Deezer Spleeter 2-stem FP16",
        description = "Empfohlener Kompromiss aus Trennqualität, Geschwindigkeit und Speicherbedarf.",
        downloadSizeLabel = "35 MB",
        recommended = true
    ),
    HIGH_QUALITY(
        id = "kim-vocal-2",
        qualityLabel = "Hohe Qualität",
        modelLabel = "Kim Vocal 2 · Mel-Band RoFormer",
        description = "Großes Qualitätsmodell für bestmögliche Gesangsisolation bei höherem Ressourcenbedarf.",
        downloadSizeLabel = "913 MB",
        recommended = false
    );

    companion object {
        fun fromId(id: String?): SongSeparationModel =
            entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

data class SongModelInstallation(
    val model: SongSeparationModel,
    val isInstalled: Boolean = false,
    val installedBytes: Long = 0L,
    val partialBytes: Long = 0L
) {
    val storedBytes: Long
        get() = installedBytes + partialBytes
}
