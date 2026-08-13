package com.whispercpp.whisper

import android.content.res.AssetManager
import androidx.annotation.Keep
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong

private const val LOG_TAG = "LibWhisper"

data class WhisperSegment(val startMs: Long, val endMs: Long, val text: String)

data class WhisperTranscriptResult(
    val segments: List<WhisperSegment>,
    val detectedLanguage: String
)

data class WhisperVadSegment(val startMs: Long, val endMs: Long)

/**
 * Converts the centisecond time base returned by whisper.cpp's VAD API to the
 * millisecond time base used by the Android application. Invalid native pairs
 * are ignored so they cannot become zero-length speech segments in statistics.
 */
fun whisperVadSegmentsFromCentiseconds(rawCentiseconds: FloatArray): List<WhisperVadSegment> {
    require(rawCentiseconds.size % 2 == 0) {
        "Silero VAD lieferte eine unvollständige Zeitstempel-Paarung."
    }
    return buildList(rawCentiseconds.size / 2) {
        rawCentiseconds.indices.step(2).forEach { index ->
            val startCentiseconds = rawCentiseconds[index]
            val endCentiseconds = rawCentiseconds[index + 1]
            if (!startCentiseconds.isFinite() || !endCentiseconds.isFinite()) return@forEach
            val startMs = (startCentiseconds * 10.0).roundToLong()
            val endMs = (endCentiseconds * 10.0).roundToLong()
            if (startMs >= 0L && endMs > startMs) {
                add(WhisperVadSegment(startMs, endMs))
            }
        }
    }
}

data class WhisperRuntimeBackend(
    val name: String,
    val gpuRequested: Boolean,
    val gpuAvailable: Boolean,
    val fellBackToCpu: Boolean
)

fun interface WhisperProgressListener {
    fun onProgress(percent: Int)
}

/** The native layer resolves this method by name, so it must not be obfuscated. */
@Keep
private class NativeWhisperProgressListener(
    private val callback: (Int) -> Unit
) : WhisperProgressListener {
    @Keep
    override fun onProgress(percent: Int) {
        callback(percent.coerceIn(0, 100))
    }
}

class WhisperContext private constructor(
    private var ptr: Long,
    val runtimeBackend: WhisperRuntimeBackend
) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val released = AtomicBoolean(false)

    suspend fun transcribeSegments(
        data: FloatArray,
        language: String = "auto",
        configuration: WhisperConfiguration = WhisperConfiguration(),
        shouldCancel: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {}
    ): WhisperTranscriptResult = withContext(dispatcher) {
        check(!released.get()) { "Der Whisper-Kontext wurde bereits freigegeben." }
        require(ptr != 0L)
        val numThreads = configuration.threads.takeIf { it > 0 }
            ?: WhisperCpuConfig.preferredThreadCount
        Log.d(LOG_TAG, "Selecting $numThreads threads")
        val abortToken = WhisperLib.createAbortToken()
        check(abortToken != 0L) { "Das Abbruchsignal für Whisper konnte nicht vorbereitet werden." }
        synchronized(abortLock) {
            activeAbortToken = abortToken
        }
        try {
            if (shouldCancel()) WhisperLib.requestAbort(abortToken)
            fun run(modelPath: String): Int = WhisperLib.fullTranscribe(
                ptr,
                numThreads,
                data,
                language,
                configuration.beamSearch,
                configuration.beamSize,
                configuration.bestOf,
                configuration.temperature,
                configuration.initialPrompt,
                configuration.carryContext,
                configuration.maximumSegmentCharacters,
                configuration.splitOnWord,
                configuration.tokenTimestamps,
                configuration.suppressBlank,
                configuration.suppressNonSpeechTokens,
                configuration.logProbabilityThreshold,
                configuration.noSpeechThreshold,
                configuration.entropyThreshold,
                modelPath,
                configuration.vadThreshold,
                configuration.vadMinSpeechDurationMs,
                configuration.vadMinSilenceDurationMs,
                configuration.vadMaxSpeechDurationSeconds,
                configuration.vadSpeechPadMs,
                configuration.vadSamplesOverlapSeconds,
                abortToken,
                NativeWhisperProgressListener { percent -> onProgress(percent) }
            )
            val result = run(configuration.vadModelPath.orEmpty())
            if (WhisperLib.isAbortRequested(abortToken)) {
                throw java.util.concurrent.CancellationException("Whisper-Transkription abgebrochen.")
            }
            check(result == 0) {
                "Whisper konnte die Audiodatei nicht verarbeiten (Fehlercode $result)."
            }
            val textCount = WhisperLib.getTextSegmentCount(ptr)
            val segments = List(textCount) { index ->
                WhisperSegment(
                    startMs = WhisperLib.getTextSegmentT0(ptr, index) * 10,
                    endMs = WhisperLib.getTextSegmentT1(ptr, index) * 10,
                    text = WhisperLib.getTextSegment(ptr, index).trim()
                )
            }
            return@withContext WhisperTranscriptResult(
                segments = segments,
                detectedLanguage = WhisperLib.getDetectedLanguage(ptr)
            )
        } finally {
            synchronized(abortLock) {
                if (activeAbortToken == abortToken) activeAbortToken = 0L
            }
            WhisperLib.freeAbortToken(abortToken)
        }
    }

    private val abortLock = Any()
    private var activeAbortToken = 0L

    fun requestAbort() {
        synchronized(abortLock) {
            if (activeAbortToken != 0L) WhisperLib.requestAbort(activeAbortToken)
        }
    }

    suspend fun benchMemory(nthreads: Int): String = withContext(dispatcher) {
        return@withContext WhisperLib.benchMemcpy(nthreads)
    }

    suspend fun benchGgmlMulMat(nthreads: Int): String = withContext(dispatcher) {
        return@withContext WhisperLib.benchGgmlMulMat(nthreads)
    }

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable + dispatcher) {
                if (ptr != 0L) {
                    WhisperLib.freeContext(ptr)
                    ptr = 0
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    protected fun finalize() {
        if (!released.get()) {
            runBlocking {
                release()
            }
        }
    }

    companion object {
        fun createContextFromFile(filePath: String, useGpu: Boolean = true): WhisperContext {
            val gpuBackend = if (useGpu) WhisperLib.getGpuBackendName().takeIf(String::isNotBlank) else null
            val gpuAvailable = gpuBackend != null
            var ptr = WhisperLib.initContext(filePath, useGpu && gpuAvailable)
            var fellBackToCpu = false
            if (ptr == 0L && useGpu && gpuAvailable) {
                Log.w(LOG_TAG, "Whisper GPU initialization failed; retrying with CPU")
                ptr = WhisperLib.initContext(filePath, false)
                fellBackToCpu = true
            }
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(
                ptr = ptr,
                runtimeBackend = WhisperRuntimeBackend(
                    name = if (useGpu && gpuAvailable && !fellBackToCpu) {
                        checkNotNull(gpuBackend)
                    } else {
                        "CPU"
                    },
                    gpuRequested = useGpu,
                    gpuAvailable = gpuAvailable,
                    fellBackToCpu = fellBackToCpu || useGpu && !gpuAvailable
                )
            )
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr, WhisperRuntimeBackend("CPU", false, false, false))
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr, WhisperRuntimeBackend("CPU", false, false, false))
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

class WhisperVadContext private constructor(private var ptr: Long) {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val released = AtomicBoolean(false)

    suspend fun detectSegments(
        samples: FloatArray,
        threshold: Float,
        minimumSpeechDurationMs: Int,
        minimumSilenceDurationMs: Int,
        maximumSpeechDurationSeconds: Float,
        speechPadMs: Int,
        overlapSeconds: Float
    ): List<WhisperVadSegment> = withContext(dispatcher) {
        check(!released.get()) { "Der VAD-Kontext wurde bereits freigegeben." }
        require(ptr != 0L)
        val rawCentiseconds = WhisperLib.detectVadSegmentsCentiseconds(
            ptr,
            samples,
            threshold,
            minimumSpeechDurationMs,
            minimumSilenceDurationMs,
            maximumSpeechDurationSeconds,
            speechPadMs,
            overlapSeconds
        ) ?: error("Silero VAD konnte den Audioabschnitt nicht analysieren.")
        whisperVadSegmentsFromCentiseconds(rawCentiseconds)
    }

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable + dispatcher) {
                if (ptr != 0L) {
                    WhisperLib.freeVadContext(ptr)
                    ptr = 0L
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    protected fun finalize() {
        if (!released.get()) runBlocking { release() }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperVadContext {
            val ptr = WhisperLib.initVadContext(filePath)
            check(ptr != 0L) { "Das Silero-VAD-Modell konnte nicht geladen werden." }
            return WhisperVadContext(ptr)
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String, useGpu: Boolean): Long
        external fun freeContext(contextPtr: Long)
        external fun getGpuBackendName(): String
        external fun initVadContext(modelPath: String): Long
        external fun freeVadContext(contextPtr: Long)
        /** Returns alternating t0/t1 values in whisper.cpp's centisecond time base. */
        external fun detectVadSegmentsCentiseconds(
            contextPtr: Long,
            audioData: FloatArray,
            threshold: Float,
            minimumSpeechDurationMs: Int,
            minimumSilenceDurationMs: Int,
            maximumSpeechDurationSeconds: Float,
            speechPadMs: Int,
            overlapSeconds: Float
        ): FloatArray?
        external fun createAbortToken(): Long
        external fun requestAbort(abortToken: Long)
        external fun isAbortRequested(abortToken: Long): Boolean
        external fun freeAbortToken(abortToken: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            language: String,
            beamSearch: Boolean,
            beamSize: Int,
            bestOf: Int,
            temperature: Float,
            initialPrompt: String,
            carryContext: Boolean,
            maximumSegmentCharacters: Int,
            splitOnWord: Boolean,
            tokenTimestamps: Boolean,
            suppressBlank: Boolean,
            suppressNonSpeechTokens: Boolean,
            logProbabilityThreshold: Float,
            noSpeechThreshold: Float,
            entropyThreshold: Float,
            vadModelPath: String,
            vadThreshold: Float,
            vadMinSpeechDurationMs: Int,
            vadMinSilenceDurationMs: Int,
            vadMaxSpeechDurationSeconds: Float,
            vadSpeechPadMs: Int,
            vadSamplesOverlapSeconds: Float,
            abortToken: Long,
            progressListener: WhisperProgressListener
        ): Int
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getDetectedLanguage(contextPtr: Long): String
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}
