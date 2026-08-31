package de.matthiasennen.transcript.song

enum class SongSeparationModel(
    val id: String,
    val qualityLabel: String,
    val modelLabel: String,
    val description: String,
    val fileName: String,
    val downloadSizeLabel: String,
    val expectedBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val recommended: Boolean
) {
    QUICK(
        id = "umxhq",
        qualityLabel = "Schnell",
        modelLabel = "Open-Unmix UMXHQ",
        description = "Kompakte Gesangstrennung mit geringem Speicherbedarf für schnelle lokale Verarbeitung.",
        fileName = "umxhq.onnx",
        downloadSizeLabel = "17 MB",
        expectedBytes = 0L,
        sha256 = "",
        downloadUrl = "",
        recommended = false
    ),
    BALANCED(
        id = "spleeter-2stems-fp16",
        qualityLabel = "Ausgewogen",
        modelLabel = "Deezer Spleeter 2-stem FP16",
        description = "Empfohlener Kompromiss aus Trennqualität, Geschwindigkeit und Speicherbedarf.",
        fileName = "sherpa-onnx-spleeter-2stems-fp16.tar.bz2",
        downloadSizeLabel = "35 MB",
        expectedBytes = 35_271_738L,
        sha256 = "",
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems-fp16.tar.bz2",
        recommended = true
    ),
    HIGH_QUALITY(
        id = "kim-vocal-2",
        qualityLabel = "Hohe Qualität",
        modelLabel = "Kim Vocal 2 · Mel-Band RoFormer",
        description = "Großes Qualitätsmodell für bestmögliche Gesangsisolation bei höherem Ressourcenbedarf.",
        fileName = "mel_band_roformer_kim_ft2_unwa.ckpt",
        downloadSizeLabel = "913 MB",
        expectedBytes = 0L,
        sha256 = "",
        downloadUrl = "",
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
