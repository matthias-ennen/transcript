package de.matthiasennen.transcript.ui.main

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException

private const val SHARED_IMPORT_RESERVE_BYTES = 64L * 1024L * 1024L

data class SharedMediaRequest(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val declaredSizeBytes: Long
)

internal data class StagedSharedMedia(
    val file: File,
    val safeFileName: String
)

internal fun isSupportedSharedMediaMime(mimeType: String?): Boolean {
    val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return normalized.startsWith("audio/") || normalized.startsWith("video/")
}

internal fun safeSharedMediaFileName(requestedName: String): String {
    val leaf = requestedName.substringAfterLast('/').substringAfterLast('\\')
    val safe = leaf.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(120)
    return safe.ifBlank { "geteilte-mediendatei" }
}

internal class SharedMediaImportStore(private val directory: File) {
    fun clearPending() {
        directory.listFiles()
            ?.filter { it.name.startsWith(".pending-") }
            ?.forEach(File::delete)
    }

    fun clearCommitted() {
        directory.listFiles()
            ?.filterNot { it.name.startsWith(".pending-") }
            ?.forEach(File::delete)
    }

    fun stage(
        input: InputStream,
        requestedFileName: String,
        declaredSizeBytes: Long,
        availableBytes: Long
    ): StagedSharedMedia {
        directory.mkdirs()
        clearPending()
        val usableBytes = (availableBytes - SHARED_IMPORT_RESERVE_BYTES).coerceAtLeast(0L)
        if (declaredSizeBytes >= 0L && declaredSizeBytes > usableBytes) {
            throw IOException("Für die geteilte Datei ist nicht genügend freier Gerätespeicher vorhanden.")
        }
        val safeName = safeSharedMediaFileName(requestedFileName)
        val pending = File(directory, ".pending-${System.nanoTime()}-$safeName")
        try {
            FileOutputStream(pending).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > usableBytes) {
                        throw IOException(
                            "Für die geteilte Datei ist nicht genügend freier Gerätespeicher vorhanden."
                        )
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            if (pending.length() <= 0L) {
                throw IOException("Die geteilte Datei ist leer.")
            }
            return StagedSharedMedia(pending, safeName)
        } catch (failure: Throwable) {
            pending.delete()
            throw failure
        }
    }

    fun commit(staged: StagedSharedMedia): File {
        directory.mkdirs()
        val target = File(
            directory,
            "current-${System.currentTimeMillis()}-${staged.safeFileName}"
        )
        if (!staged.file.renameTo(target)) {
            staged.file.delete()
            throw IOException("Die geteilte Datei konnte nicht sicher gespeichert werden.")
        }
        directory.listFiles()
            ?.filter { it != target }
            ?.forEach(File::delete)
        return target
    }
}
