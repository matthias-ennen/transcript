package de.matthiasennen.transcript.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun start(): File {
        check(recorder == null) { "Es läuft bereits eine Aufnahme." }
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
        val file = File(directory, "Aufnahme_$timestamp.m4a")
        val mediaRecorder = createRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        outputFile = file
        recorder = mediaRecorder
        return file
    }

    fun currentAmplitude(): Float = runCatching {
        recorder?.maxAmplitude?.div(32_767f)?.coerceIn(0f, 1f) ?: 0f
    }.getOrDefault(0f)

    fun stop(): File? {
        val activeRecorder = recorder ?: return null
        recorder = null
        return try {
            activeRecorder.stop()
            outputFile
        } catch (_: RuntimeException) {
            outputFile?.delete()
            null
        } finally {
            activeRecorder.reset()
            activeRecorder.release()
            outputFile = null
        }
    }

    fun release() {
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            activeRecorder.release()
        }
        outputFile = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
}
