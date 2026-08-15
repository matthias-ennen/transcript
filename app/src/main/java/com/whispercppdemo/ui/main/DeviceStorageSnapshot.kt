package de.matthiasennen.transcript.ui.main

data class DeviceStorageSnapshot(
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L
) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceIn(0L, totalBytes.coerceAtLeast(0L))

    val usedFraction: Float
        get() = if (totalBytes > 0L) {
            (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

internal fun normalizedStorageSnapshot(totalBytes: Long, freeBytes: Long): DeviceStorageSnapshot {
    val total = totalBytes.coerceAtLeast(0L)
    return DeviceStorageSnapshot(
        totalBytes = total,
        freeBytes = freeBytes.coerceIn(0L, total)
    )
}
