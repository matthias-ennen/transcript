package de.matthiasennen.transcript.ai

enum class AiModel(
    val id: String,
    val qualityLabel: String,
    val modelLabel: String,
    val description: String,
    val fileName: String,
    val downloadSizeLabel: String,
    val minimumBytes: Long,
    val sha256: String,
    val downloadUrl: String
) {
    QUICK(
        id = "qwen35-08b-q4",
        qualityLabel = "Schnell (Q4_0)",
        modelLabel = "Qwen3.5 0,8B Q4_0",
        description = "Schlankes 4-Bit-Modell für schnelle, vorsichtige Grundkorrekturen",
        fileName = "Qwen3.5-0.8B-Q4_0.gguf",
        downloadSizeLabel = "563 MB",
        minimumBytes = 540_000_000L,
        sha256 = "57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf",
        downloadUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/" +
            "8fea620810c4afa23dd6443f999a48574c1611a3/" +
            "Qwen3.5-0.8B-Q4_0.gguf?download=true"
    ),
    QUICK_Q8(
        id = "qwen35-08b-q8",
        qualityLabel = "Schnell (Q8_0)",
        modelLabel = "Qwen3.5 0,8B Q8_0",
        description = "Kleines Q8-Modell mit höherer Quantisierungspräzision bei moderatem Speicherbedarf",
        fileName = "Qwen3.5-0.8B-Q8_0.gguf",
        downloadSizeLabel = "834 MB",
        minimumBytes = 800_000_000L,
        sha256 = "37ae482d336108d23516fa35e8e0c4126688d81018b87178a18d752a1357814f",
        downloadUrl = "https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF/resolve/" +
            "8fea620810c4afa23dd6443f999a48574c1611a3/" +
            "Qwen3.5-0.8B-Q8_0.gguf?download=true"
    ),
    BALANCED(
        id = "qwen35-2b-q4km",
        qualityLabel = "Ausgewogen (Q4_K_M)",
        modelLabel = "Qwen3.5 2B Q4_K_M",
        description = "Empfohlener Kompromiss aus Genauigkeit, Tempo und Speicherbedarf",
        fileName = "Qwen3.5-2B-Q4_K_M.gguf",
        downloadSizeLabel = "1,28 GB",
        minimumBytes = 1_200_000_000L,
        sha256 = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/" +
            "1c466474d208da1a7c4b8cb87ebcdac78f160e34/" +
            "Qwen3.5-2B-Q4_K_M.gguf?download=true"
    ),
    BALANCED_Q4(
        id = "qwen35-2b-q4",
        qualityLabel = "Ausgewogen (Q4_0)",
        modelLabel = "Qwen3.5 2B Q4_0",
        description = "Alternative 4-Bit-Quantisierung für optimierte ARM- und KleidiAI-Pfade",
        fileName = "Qwen3.5-2B-Q4_0.gguf",
        downloadSizeLabel = "1,21 GB",
        minimumBytes = 1_150_000_000L,
        sha256 = "cd70221bebaee0503e0f6717e174250cd7825aa88438b3aabec9ad55731d9bb1",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/" +
            "31e04817b38d226cdd13454bcc3982ebaa5a386b/" +
            "Qwen3.5-2B-Q4_0.gguf?download=true"
    ),
    PRECISE(
        id = "qwen35-4b-q4km",
        qualityLabel = "Sehr genau (Q4_K_M)",
        modelLabel = "Qwen3.5 4B Q4_K_M",
        description = "Größtes Modell für anspruchsvolle Texte und bestmögliche lokale Korrektur",
        fileName = "Qwen3.5-4B-Q4_K_M.gguf",
        downloadSizeLabel = "2,74 GB",
        minimumBytes = 2_600_000_000L,
        sha256 = "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/" +
            "9b57f22a6a894e8db976ae8cc55f794b3ad18b94/" +
            "Qwen3.5-4B-Q4_K_M.gguf?download=true"
    ),
    PRECISE_Q4(
        id = "qwen35-4b-q4",
        qualityLabel = "Sehr genau (Q4_0)",
        modelLabel = "Qwen3.5 4B Q4_0",
        description = "Große Q4_0-Variante für hohe Korrekturqualität mit optimiertem ARM-Pfad",
        fileName = "Qwen3.5-4B-Q4_0.gguf",
        downloadSizeLabel = "2,58 GB",
        minimumBytes = 2_450_000_000L,
        sha256 = "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/" +
            "1a02dbed1cdfe73efc3fa519c54126befb4faf68/" +
            "Qwen3.5-4B-Q4_0.gguf?download=true"
    );

    val kleidiAiCompatible: Boolean
        get() = fileName.contains("Q4_0", ignoreCase = true) ||
            fileName.contains("Q8_0", ignoreCase = true)

    companion object {
        fun fromId(id: String?): AiModel = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

data class AiModelInstallation(
    val model: AiModel,
    val isInstalled: Boolean,
    val installedBytes: Long = 0L,
    val partialBytes: Long = 0L
) {
    val storedBytes: Long
        get() = installedBytes + partialBytes
}
