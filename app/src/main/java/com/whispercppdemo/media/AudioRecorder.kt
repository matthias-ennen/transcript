package de.matthiasennen.transcript.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: RecordingOutput? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    fun start(): RecordingOutput {
        check(recorder == null) { "Es läuft bereits eine Aufnahme." }
        val folder = RecordingFolderPreferences(context).loadValid()
            ?: error("Bitte zuerst einen Aufnahmeordner auswählen.")
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
        val fileName = "Aufnahme_$timestamp.m4a"
        val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            folder.treeUri,
            DocumentsContract.getTreeDocumentId(folder.treeUri)
        )
        val outputUri = DocumentsContract.createDocument(
            context.contentResolver,
            treeDocumentUri,
            "audio/mp4",
            fileName
        ) ?: error("Die Aufnahmedatei konnte im gewählten Ordner nicht angelegt werden.")
        val descriptor = context.contentResolver.openFileDescriptor(outputUri, "w")
            ?: run {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, outputUri) }
                error("Die Aufnahmedatei ist nicht beschreibbar.")
            }
        val recordingOutput = RecordingOutput(outputUri.toString(), fileName)
        val mediaRecorder = try {
            createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(descriptor.fileDescriptor)
                prepare()
                start()
            }
        } catch (failure: Throwable) {
            descriptor.close()
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, outputUri) }
            throw failure
        }
        output = recordingOutput
        outputDescriptor = descriptor
        recorder = mediaRecorder
        return recordingOutput
    }

    fun currentAmplitude(): Float = runCatching {
        recorder?.maxAmplitude?.div(32_767f)?.coerceIn(0f, 1f) ?: 0f
    }.getOrDefault(0f)

    fun stop(): RecordingOutput? {
        val activeRecorder = recorder ?: return null
        recorder = null
        var failedOutput: RecordingOutput? = null
        val completed = try {
            activeRecorder.stop()
            output
        } catch (_: RuntimeException) {
            failedOutput = output
            null
        } finally {
            activeRecorder.reset()
            activeRecorder.release()
            outputDescriptor?.close()
            outputDescriptor = null
            output = null
        }
        if (completed == null || !hasContent(android.net.Uri.parse(completed.uriString))) {
            (completed ?: failedOutput)?.let {
                runCatching {
                    DocumentsContract.deleteDocument(
                        context.contentResolver,
                        android.net.Uri.parse(it.uriString)
                    )
                }
            }
            return null
        }
        return completed
    }

    fun release() {
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }
        outputDescriptor?.close()
        outputDescriptor = null
        output = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

    private fun hasContent(uri: android.net.Uri): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize > 0L
        } == true
    }.getOrDefault(false)
}
