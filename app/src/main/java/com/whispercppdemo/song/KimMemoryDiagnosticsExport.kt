package de.matthiasennen.transcript.song

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class KimMemoryDiagnosticsExport(
    val fileName: String,
    val relativePath: String
)

private data class KimDiagnosticSource(
    val prefix: String,
    val file: File
)

/**
 * Copies the newest crash-safe Kim memory log from private app storage into a
 * user-visible Downloads/Transcript file. Both the legacy ONNX runtime and the
 * Native/GGUF runtime use this path. The private source is deleted only after a
 * verified successful copy, so a hard worker-process kill cannot destroy the evidence.
 */
internal fun exportKimMemoryDiagnosticsToDownloads(
    context: Context
): KimMemoryDiagnosticsExport? {
    val candidates = listOf(
        KimDiagnosticSource(
            prefix = "kim-onnx",
            file = File(context.filesDir, "song-models/kim-vocal-2/kim-memory-diagnostics.log")
        ),
        KimDiagnosticSource(
            prefix = "kim-native",
            file = File(context.filesDir, "song-models/kim-vocal-2-native/kim-memory-diagnostics.log")
        )
    ).filter { it.file.isFile && it.file.length() > 0L }
    val source = candidates.maxByOrNull { it.file.lastModified() } ?: return null

    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        .format(Date(source.file.lastModified().coerceAtLeast(1L)))
    val fileName = "${source.prefix}-memory-diagnostics-$timestamp.txt"
    val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Transcript/$fileName"

    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(
                context = context,
                source = source.file,
                fileName = fileName
            )
        } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Transcript"
            ).apply { mkdirs() }
            val destination = File(directory, fileName)
            source.file.copyTo(destination, overwrite = true)
            check(destination.isFile && destination.length() == source.file.length()) {
                "Kim-Diagnosedatei wurde nicht vollständig nach Downloads kopiert."
            }
        }

        source.file.delete()
        KimMemoryDiagnosticsExport(
            fileName = fileName,
            relativePath = relativePath
        )
    }.onFailure { failure ->
        Log.w(
            EXPORT_LOG_TAG,
            "Kim memory diagnostics could not be exported to Downloads",
            failure
        )
    }.getOrNull()
}

@TargetApi(Build.VERSION_CODES.Q)
private fun exportWithMediaStore(
    context: Context,
    source: File,
    fileName: String
) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(
            MediaStore.Downloads.RELATIVE_PATH,
            "${Environment.DIRECTORY_DOWNLOADS}/Transcript"
        )
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("Kim-Diagnosedatei konnte nicht in Downloads angelegt werden.")

    try {
        val copiedBytes = resolver.openOutputStream(uri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Kim-Diagnosedatei konnte nicht in Downloads geschrieben werden.")
        check(copiedBytes == source.length()) {
            "Kim-Diagnosedatei wurde nicht vollständig nach Downloads kopiert."
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (failure: Throwable) {
        resolver.delete(uri, null, null)
        throw failure
    }
}

private const val EXPORT_LOG_TAG = "KimMemoryDiagnostics"
