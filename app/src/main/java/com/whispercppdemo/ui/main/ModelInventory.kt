package de.matthiasennen.transcript.ui.main

import de.matthiasennen.transcript.ai.AiModel
import de.matthiasennen.transcript.ai.AiModelInstallation
import de.matthiasennen.transcript.download.SileroVadModel
import de.matthiasennen.transcript.download.VadModelInstallation
import de.matthiasennen.transcript.song.SongModelInstallation
import de.matthiasennen.transcript.song.SongSeparationModel
import java.io.File

/**
 * Single owner for the local Whisper, VAD and AI model files.
 *
 * The view model deliberately keeps presentation state and service commands, while this class
 * owns file naming, installation inspection and the paired cleanup of complete/partial files.
 */
internal class ModelInventory(filesDirectory: File) {
    private val whisperDirectory = File(filesDirectory, "models")
    private val vadDirectory = File(filesDirectory, "vad-models")
    private val aiDirectory = File(filesDirectory, "ai-models")
    private val songDirectory = File(filesDirectory, "song-models")

    fun ensureDirectories() {
        whisperDirectory.mkdirs()
        vadDirectory.mkdirs()
        aiDirectory.mkdirs()
        songDirectory.mkdirs()
    }

    fun whisperFile(model: WhisperModel): File = File(whisperDirectory, model.fileName)

    fun whisperPartialFile(model: WhisperModel): File =
        File(whisperDirectory, "${model.fileName}.part")

    fun vadFile(): File = File(vadDirectory, SileroVadModel.fileName)

    fun vadPartialFile(): File = File(vadDirectory, "${SileroVadModel.fileName}.part")

    fun aiFile(model: AiModel): File = File(aiDirectory, model.fileName)

    fun aiPartialFile(model: AiModel): File = File(aiDirectory, "${model.fileName}.part")

    fun songFile(model: SongSeparationModel, artifactFileName: String): File =
        File(File(songDirectory, model.id).apply { mkdirs() }, artifactFileName)

    fun songPartialFile(model: SongSeparationModel, artifactFileName: String): File =
        File(File(songDirectory, model.id).apply { mkdirs() }, "${artifactFileName}.part")

    fun whisperInstallations(): List<ModelInstallation> = WhisperModel.entries.map { model ->
        val file = whisperFile(model)
        ModelInstallation(
            model = model,
            isInstalled = file.isFile && file.length() >= model.minimumBytes,
            installedBytes = file.takeIf(File::isFile)?.length() ?: 0L,
            partialBytes = whisperPartialFile(model).takeIf(File::isFile)?.length() ?: 0L
        )
    }

    fun vadInstallation(): VadModelInstallation {
        val file = vadFile()
        return VadModelInstallation(
            isInstalled = file.isFile && file.length() == SileroVadModel.expectedBytes,
            installedBytes = file.takeIf(File::isFile)?.length() ?: 0L,
            partialBytes = vadPartialFile().takeIf(File::isFile)?.length() ?: 0L
        )
    }

    fun aiInstallations(): List<AiModelInstallation> = AiModel.entries.map { model ->
        val file = aiFile(model)
        AiModelInstallation(
            model = model,
            isInstalled = file.isFile && file.length() >= model.minimumBytes,
            installedBytes = file.takeIf(File::isFile)?.length() ?: 0L,
            partialBytes = aiPartialFile(model).takeIf(File::isFile)?.length() ?: 0L
        )
    }

    fun songInstallations(): List<SongModelInstallation> = SongSeparationModel.entries.map { model ->
        val installedBytes = model.artifacts.sumOf { artifact ->
            songFile(model, artifact.fileName).takeIf(File::isFile)?.length() ?: 0L
        }
        val partialBytes = model.artifacts.sumOf { artifact ->
            songPartialFile(model, artifact.fileName).takeIf(File::isFile)?.length() ?: 0L
        }
        val installed = model.artifacts.all { artifact ->
            val file = songFile(model, artifact.fileName)
            file.isFile && file.length() == artifact.expectedBytes
        }
        SongModelInstallation(
            model = model,
            isInstalled = installed,
            installedBytes = installedBytes,
            partialBytes = partialBytes
        )
    }

    fun deleteWhisper(model: WhisperModel, genericErrors: Boolean = false) = deletePair(
        complete = whisperFile(model),
        partial = whisperPartialFile(model),
        completeError = if (genericErrors) "Das Modell konnte nicht gelöscht werden."
        else "${model.modelLabel} konnte nicht gelöscht werden.",
        partialError = if (genericErrors) "Der unvollständige Download konnte nicht gelöscht werden."
        else "Der unvollständige Download von ${model.modelLabel} konnte nicht gelöscht werden."
    )

    fun deleteVad() = deletePair(
        complete = vadFile(),
        partial = vadPartialFile(),
        completeError = "Das VAD-Modell konnte nicht gelöscht werden.",
        partialError = "Der unvollständige VAD-Download konnte nicht gelöscht werden."
    )

    fun deleteAi(model: AiModel, genericErrors: Boolean = false) = deletePair(
        complete = aiFile(model),
        partial = aiPartialFile(model),
        completeError = if (genericErrors) "Das KI-Modell konnte nicht gelöscht werden."
        else "${model.modelLabel} konnte nicht gelöscht werden.",
        partialError = if (genericErrors) "Der unvollständige KI-Download konnte nicht gelöscht werden."
        else "Der unvollständige Download von ${model.modelLabel} konnte nicht gelöscht werden."
    )

    fun deleteSong(model: SongSeparationModel) {
        model.artifacts.forEach { artifact ->
            deletePair(
                complete = songFile(model, artifact.fileName),
                partial = songPartialFile(model, artifact.fileName),
                completeError = "${model.modelLabel} konnte nicht gelöscht werden.",
                partialError = "Der unvollständige Download von ${model.modelLabel} konnte nicht gelöscht werden."
            )
        }
        val directory = File(songDirectory, model.id)
        check(!directory.exists() || directory.list()?.isNotEmpty() == true || directory.delete()) {
            "Der Modellordner von ${model.modelLabel} konnte nicht gelöscht werden."
        }
    }

    private fun deletePair(
        complete: File,
        partial: File,
        completeError: String,
        partialError: String
    ) {
        check(!complete.exists() || complete.delete()) { completeError }
        check(!partial.exists() || partial.delete()) { partialError }
    }
}
