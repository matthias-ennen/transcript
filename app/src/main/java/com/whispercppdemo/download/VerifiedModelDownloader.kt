package de.matthiasennen.transcript.download

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class VerifiedModelDownload(
    val modelLabel: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
    val destination: File,
    val partial: File,
    val failureLabel: String
)

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val resumed: Boolean
)

/** Shared resumable download and checksum path for every local model family. */
object VerifiedModelDownloader {
    fun downloadAndVerify(
        download: VerifiedModelDownload,
        onProgress: (DownloadProgress) -> Unit,
        onVerifying: (Long) -> Unit
    ) {
        if (download.destination.isFile && sha256(download.destination) == download.sha256) return

        if (download.partial.isFile && download.partial.length() >= download.expectedBytes) {
            if (sha256(download.partial) == download.sha256) {
                replace(download.partial, download.destination, download.failureLabel)
                return
            }
            check(download.partial.delete()) { "Der beschädigte ${download.failureLabel}-Zwischendownload konnte nicht gelöscht werden." }
        }

        var existingBytes = download.partial.takeIf(File::isFile)?.length() ?: 0L
        DownloadStoragePolicy.requireEnoughSpace(
            filesDirectory = download.partial.parentFile ?: download.destination.parentFile
                ?: error("Modellordner fehlt."),
            modelLabel = download.modelLabel,
            modelBytes = download.expectedBytes,
            partialBytes = existingBytes
        )

        var connection = openConnection(download.downloadUrl, existingBytes)
        var responseCode = connection.responseCode
        var resumed = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL &&
            contentRangeStart(connection.getHeaderField("Content-Range")) == existingBytes

        if (existingBytes > 0L && !resumed) {
            connection.disconnect()
            FileOutputStream(download.partial, false).use { }
            existingBytes = 0L
            connection = openConnection(download.downloadUrl, 0L)
            responseCode = connection.responseCode
        }

        try {
            check(responseCode in 200..299) {
                "${download.failureLabel}-Download fehlgeschlagen (HTTP $responseCode)."
            }
            val totalBytes = totalDownloadBytes(existingBytes, connection.contentLengthLong.coerceAtLeast(0L))
            var downloadedBytes = existingBytes
            onProgress(DownloadProgress(downloadedBytes, totalBytes, resumed))
            connection.inputStream.use { input ->
                FileOutputStream(download.partial, resumed).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var lastPublishedBytes = downloadedBytes
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        if (downloadedBytes - lastPublishedBytes >= 2 * 1024 * 1024L) {
                            onProgress(DownloadProgress(downloadedBytes, totalBytes, resumed))
                            lastPublishedBytes = downloadedBytes
                        }
                    }
                }
            }
            onProgress(DownloadProgress(downloadedBytes, totalBytes, resumed))
        } finally {
            connection.disconnect()
        }

        check(download.partial.length() >= download.expectedBytes) {
            "Das heruntergeladene ${download.failureLabel} ist unvollständig."
        }
        onVerifying(download.partial.length())
        check(sha256(download.partial) == download.sha256) {
            "Die Prüfsumme des ${download.failureLabel} stimmt nicht."
        }
        replace(download.partial, download.destination, download.failureLabel)
    }

    private fun openConnection(url: String, startByte: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (startByte > 0L) setRequestProperty("Range", "bytes=$startByte-")
            connect()
        }

    private fun replace(partial: File, destination: File, failureLabel: String) {
        if (destination.exists()) {
            check(destination.delete()) { "Das alte ${failureLabel} konnte nicht ersetzt werden." }
        }
        check(partial.renameTo(destination)) { "Das ${failureLabel} konnte nicht gespeichert werden." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
