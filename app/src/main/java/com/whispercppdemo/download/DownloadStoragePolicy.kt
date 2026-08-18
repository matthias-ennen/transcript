package de.matthiasennen.transcript.download

import android.os.StatFs
import java.io.File

private const val TEMPORARY_OVERHEAD_BYTES = 8L * 1024L * 1024L
private const val SAFETY_RESERVE_BYTES = 128L * 1024L * 1024L

data class DownloadStorageRequirement(
    val modelLabel: String,
    val modelBytes: Long,
    val partialBytes: Long,
    val requiredFreeBytes: Long,
    val availableBytes: Long
) {
    val hasEnoughSpace: Boolean
        get() = availableBytes >= requiredFreeBytes
}

class InsufficientDownloadStorageException(
    val requirement: DownloadStorageRequirement
) : IllegalStateException("Es steht nicht genügend Speicherplatz für ${requirement.modelLabel} zur Verfügung.")

object DownloadStoragePolicy {
    fun check(
        filesDirectory: File,
        modelLabel: String,
        modelBytes: Long,
        partialBytes: Long
    ): DownloadStorageRequirement {
        val stats = StatFs(filesDirectory.absolutePath)
        val availableBytes = stats.availableBlocksLong * stats.blockSizeLong
        return requirement(
            modelLabel = modelLabel,
            modelBytes = modelBytes,
            partialBytes = partialBytes,
            availableBytes = availableBytes
        )
    }

    internal fun requirement(
        modelLabel: String,
        modelBytes: Long,
        partialBytes: Long,
        availableBytes: Long
    ): DownloadStorageRequirement {
        val safeModelBytes = modelBytes.coerceAtLeast(0L)
        val safePartialBytes = partialBytes.coerceIn(0L, safeModelBytes)
        val requiredFreeBytes = (safeModelBytes - safePartialBytes) +
            TEMPORARY_OVERHEAD_BYTES + SAFETY_RESERVE_BYTES
        return DownloadStorageRequirement(
            modelLabel = modelLabel,
            modelBytes = safeModelBytes,
            partialBytes = safePartialBytes,
            requiredFreeBytes = requiredFreeBytes,
            availableBytes = availableBytes.coerceAtLeast(0L)
        )
    }

    fun requireEnoughSpace(
        filesDirectory: File,
        modelLabel: String,
        modelBytes: Long,
        partialBytes: Long
    ): DownloadStorageRequirement = check(filesDirectory, modelLabel, modelBytes, partialBytes)
        .also { requirement ->
            if (!requirement.hasEnoughSpace) {
                throw InsufficientDownloadStorageException(requirement)
            }
        }
}
