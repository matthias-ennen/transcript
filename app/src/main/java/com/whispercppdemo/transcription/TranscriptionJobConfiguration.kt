package de.matthiasennen.transcript.transcription

import de.matthiasennen.transcript.song.SongSeparationModel
import de.matthiasennen.transcript.song.SongSeparationRuntime
import de.matthiasennen.transcript.song.SongWorkerRuntime
import de.matthiasennen.transcript.song.TranscriptionMode
import de.matthiasennen.transcript.song.TranscriptionModeRuntime
import de.matthiasennen.transcript.ui.main.WhisperComputeBackend
import de.matthiasennen.transcript.ui.main.WhisperDecoding
import de.matthiasennen.transcript.ui.main.WhisperSettings
import de.matthiasennen.transcript.ui.main.WhisperTimestampMode
import de.matthiasennen.transcript.ui.main.WhisperVadMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val CONFIGURATION_MAGIC = 0x54524346
private const val CONFIGURATION_VERSION = 2
private const val LEGACY_CONFIGURATION_VERSION = 1
private const val MAX_CONFIGURATION_BYTES = 1024 * 1024

data class TranscriptionJobConfiguration(
    val modelId: String,
    val language: String,
    val whisperSettings: WhisperSettings,
    val transcriptionMode: TranscriptionMode = TranscriptionModeRuntime.current,
    val songSeparationModelId: String = SongSeparationRuntime.currentModel.id
) {
    fun normalized(): TranscriptionJobConfiguration {
        val normalizedSettings = whisperSettings.normalized().let { settings ->
            if (transcriptionMode == TranscriptionMode.SONG) {
                settings.copy(vadMode = WhisperVadMode.OFF)
            } else {
                settings
            }
        }
        return copy(
            modelId = modelId.trim(),
            language = language.trim().ifBlank { "auto" },
            whisperSettings = normalizedSettings,
            songSeparationModelId = songSeparationModelId.trim().ifBlank {
                SongSeparationModel.BALANCED.id
            }
        ).also {
            require(it.modelId.isNotBlank()) { "Whisper-Modell fehlt in der Auftragskonfiguration." }
            if (it.transcriptionMode == TranscriptionMode.SONG) {
                require(SongSeparationModel.entries.any { model -> model.id == it.songSeparationModelId }) {
                    "Unbekanntes Modell für die Gesangstrennung."
                }
            }
            SongWorkerRuntime.update(
                mode = it.transcriptionMode,
                modelId = it.songSeparationModelId,
                threads = it.whisperSettings.threads
            )
        }
    }

    fun encode(): String {
        val value = normalized()
        val bytes = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(CONFIGURATION_MAGIC)
                output.writeInt(CONFIGURATION_VERSION)
                output.writeSizedString(value.modelId)
                output.writeSizedString(value.language)
                output.writeWhisperSettings(value.whisperSettings)
                output.writeSizedString(value.transcriptionMode.name)
                output.writeSizedString(value.songSeparationModelId)
            }
            buffer.toByteArray()
        }
        check(bytes.size <= MAX_CONFIGURATION_BYTES) {
            "Die Transkriptionskonfiguration ist ungewöhnlich groß."
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun decode(encoded: String): TranscriptionJobConfiguration {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            check(bytes.size in 1..MAX_CONFIGURATION_BYTES) {
                "Ungültige Größe der Transkriptionskonfiguration."
            }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readInt() == CONFIGURATION_MAGIC) {
                    "Unbekannte Transkriptionskonfiguration."
                }
                val version = input.readInt()
                check(version == CONFIGURATION_VERSION || version == LEGACY_CONFIGURATION_VERSION) {
                    "Veraltete Transkriptionskonfiguration."
                }
                val modelId = input.readSizedString()
                val language = input.readSizedString()
                val whisperSettings = input.readWhisperSettings()
                val transcriptionMode = if (version >= CONFIGURATION_VERSION) {
                    input.readEnum<TranscriptionMode>()
                } else {
                    TranscriptionMode.SPEECH
                }
                val songSeparationModelId = if (version >= CONFIGURATION_VERSION) {
                    input.readSizedString()
                } else {
                    SongSeparationModel.BALANCED.id
                }
                TranscriptionJobConfiguration(
                    modelId = modelId,
                    language = language,
                    whisperSettings = whisperSettings,
                    transcriptionMode = transcriptionMode,
                    songSeparationModelId = songSeparationModelId
                ).normalized().also {
                    check(input.available() == 0) {
                        "Die Transkriptionskonfiguration enthält unerwartete Daten."
                    }
                }
            }
        }
    }
}

private fun DataOutputStream.writeWhisperSettings(value: WhisperSettings) {
    val settings = value.normalized()
    writeSizedString(settings.initialPrompt)
    writeInt(settings.threads)
    writeSizedString(settings.backend.name)
    writeSizedString(settings.decoding.name)
    writeInt(settings.beamSize)
    writeInt(settings.bestOf)
    writeInt(settings.temperaturePercent)
    writeBoolean(settings.carryContext)
    writeInt(settings.maximumSegmentCharacters)
    writeBoolean(settings.splitOnWord)
    writeSizedString(settings.timestampMode.name)
    writeBoolean(settings.suppressBlank)
    writeBoolean(settings.suppressNonSpeechTokens)
    writeInt(settings.logProbabilityThresholdPercent)
    writeInt(settings.noSpeechThresholdPercent)
    writeInt(settings.entropyThresholdPercent)
    writeInt(settings.sectionMinutes)
    writeSizedString(settings.vadMode.name)
    writeInt(settings.vadThresholdPercent)
    writeInt(settings.vadMinSpeechDurationMs)
    writeInt(settings.vadMinSilenceDurationMs)
    writeInt(settings.vadMaxSpeechDurationSeconds)
    writeInt(settings.vadSpeechPadMs)
    writeInt(settings.vadOverlapMs)
}

private fun DataInputStream.readWhisperSettings(): WhisperSettings = WhisperSettings(
    initialPrompt = readSizedString(),
    threads = readInt(),
    backend = readEnum(),
    decoding = readEnum(),
    beamSize = readInt(),
    bestOf = readInt(),
    temperaturePercent = readInt(),
    carryContext = readBoolean(),
    maximumSegmentCharacters = readInt(),
    splitOnWord = readBoolean(),
    timestampMode = readEnum(),
    suppressBlank = readBoolean(),
    suppressNonSpeechTokens = readBoolean(),
    logProbabilityThresholdPercent = readInt(),
    noSpeechThresholdPercent = readInt(),
    entropyThresholdPercent = readInt(),
    sectionMinutes = readInt(),
    vadMode = readEnum(),
    vadThresholdPercent = readInt(),
    vadMinSpeechDurationMs = readInt(),
    vadMinSilenceDurationMs = readInt(),
    vadMaxSpeechDurationSeconds = readInt(),
    vadSpeechPadMs = readInt(),
    vadOverlapMs = readInt()
).normalized()

private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
    val raw = readSizedString()
    return enumValues<T>().firstOrNull { it.name == raw }
        ?: error("Ungültiger Konfigurationswert: $raw")
}

private fun DataOutputStream.writeSizedString(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= MAX_CONFIGURATION_BYTES) { "Konfigurationstext ist zu groß." }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readSizedString(): String {
    val size = readInt()
    check(size in 0..MAX_CONFIGURATION_BYTES) { "Ungültige Konfigurationstextlänge." }
    val bytes = ByteArray(size)
    readFully(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}
