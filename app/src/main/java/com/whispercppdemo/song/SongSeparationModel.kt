package de.matthiasennen.transcript.song

data class SongModelArtifact(
    val fileName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String
)

enum class SongSeparationModel(
    val id: String,
    val qualityLabel: String,
    val modelLabel: String,
    val description: String,
    val downloadSizeLabel: String,
    val artifacts: List<SongModelArtifact>,
    val recommended: Boolean
) {
    QUICK(
        id = "umxhq",
        qualityLabel = "Schnell",
        modelLabel = "Open-Unmix UMXHQ",
        description = "Kompakte Gesangstrennung mit geringem Speicherbedarf für schnelle lokale Verarbeitung.",
        downloadSizeLabel = "17 MB",
        artifacts = listOf(
            SongModelArtifact(
                fileName = "umxhq-vocals.onnx",
                downloadUrl = "https://huggingface.co/edgetools/umx-hq/resolve/main/vocals.onnx?download=true",
                expectedBytes = 17_820_856L,
                sha256 = "3d05709ff7197bbd4a33aee759e4e82002acb491f41c94770227ab57507b6ccb"
            )
        ),
        recommended = false
    ),
    BALANCED(
        id = "spleeter-2stems-fp16",
        qualityLabel = "Ausgewogen",
        modelLabel = "Deezer Spleeter 2-stem FP16",
        description = "Empfohlener Kompromiss aus Trennqualität, Geschwindigkeit und Speicherbedarf.",
        downloadSizeLabel = "19,7 MB",
        artifacts = listOf(
            SongModelArtifact(
                fileName = "spleeter-vocals.fp16.onnx",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems-fp16/resolve/d4f5d657bda30d02c3cb7d5d391f3eaa2c632df9/vocals.fp16.onnx?download=true",
                expectedBytes = 19_680_725L,
                sha256 = "c17def195c44a71dbe9eea2f27d9f61100cd686ed0353033a56f766f7ecb14a0"
            )
        ),
        recommended = true
    ),
    HIGH_QUALITY(
        id = "kim-vocal-2",
        qualityLabel = "Hohe Qualität",
        modelLabel = "Kim Vocal 2 · Mel-Band RoFormer",
        description = "Großes Qualitätsmodell für bestmögliche Gesangsisolation bei höherem Ressourcenbedarf.",
        downloadSizeLabel = "747 MB",
        artifacts = listOf(
            SongModelArtifact(
                fileName = "kim-vocal-2.onnx",
                downloadUrl = "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/6baa633eadde3c111041a81a28ed6245f19858ae/syhft_core_folded_fp16_webgpu.onnx?download=true",
                expectedBytes = 5_308_300L,
                sha256 = "dde2bfe8f85d2c12efa24ce4d45cc13e8709b8a72e277a93f130d496d948e918"
            ),
            SongModelArtifact(
                fileName = "syhft_core_folded_fp16_webgpu.onnx.data",
                downloadUrl = "https://huggingface.co/silverdaw/mel-band-roformer-vocals-onnx/resolve/6baa633eadde3c111041a81a28ed6245f19858ae/syhft_core_folded_fp16_webgpu.onnx.data?download=true",
                expectedBytes = 741_190_540L,
                sha256 = "b08cfc80905e3560a4dd5d30f641299a47dd96d309ebbe9524d9d6c9d2a0356f"
            )
        ),
        recommended = false
    );

    val totalDownloadBytes: Long
        get() = artifacts.sumOf(SongModelArtifact::expectedBytes)

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
