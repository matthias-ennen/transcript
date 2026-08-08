package de.matthiasennen.transcript.media

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

private const val CACHE_VERSION = 1
private const val CACHE_MAGIC = 0x57415645
private const val MAX_CACHED_WAVEFORMS = 12
private const val MAX_CACHED_BAR_COUNT = 1_000

internal data class CachedWaveform(
    val peaks: List<Float>,
    val durationMs: Long
)

internal class WaveformCache(
    private val directory: File,
    private val maximumEntries: Int = MAX_CACHED_WAVEFORMS
) {
    fun key(uri: String, durationMs: Long, contentLength: Long): String {
        val identity = "$CACHE_VERSION|$uri|$durationMs|$contentLength"
        return MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun read(key: String): CachedWaveform? = runCatching {
        val file = cacheFile(key)
        if (!file.isFile) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == CACHE_MAGIC)
            check(input.readInt() == CACHE_VERSION)
            val durationMs = input.readLong()
            val barCount = input.readInt()
            check(durationMs >= 0L)
            check(barCount in 1..MAX_CACHED_BAR_COUNT)
            val peaks = List(barCount) {
                val peak = input.readFloat()
                check(peak.isFinite()) { "Ungültiger Wellenformwert im Cache." }
                peak.coerceIn(0f, 1f)
            }
            file.setLastModified(System.currentTimeMillis())
            CachedWaveform(peaks = peaks, durationMs = durationMs)
        }
    }.getOrNull()

    fun write(key: String, waveform: CachedWaveform) {
        if (waveform.peaks.isEmpty() || waveform.peaks.size > MAX_CACHED_BAR_COUNT) return
        directory.mkdirs()
        val target = cacheFile(key)
        val temporary = File(directory, "$key.tmp")
        runCatching {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeLong(waveform.durationMs.coerceAtLeast(0L))
                output.writeInt(waveform.peaks.size)
                waveform.peaks.forEach { output.writeFloat(it.coerceIn(0f, 1f)) }
            }
            if (target.exists()) check(target.delete())
            check(temporary.renameTo(target))
            prune()
        }.onFailure {
            temporary.delete()
        }
    }

    private fun prune() {
        directory.listFiles { file -> file.extension == "waveform" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(maximumEntries.coerceAtLeast(1))
            ?.forEach(File::delete)
    }

    private fun cacheFile(key: String): File = File(directory, "$key.waveform")
}
