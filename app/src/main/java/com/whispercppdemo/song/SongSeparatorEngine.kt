package de.matthiasennen.transcript.song

import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

private const val UMX_FFT = 4096
private const val UMX_HOP = 1024
private const val SPLEETER_FFT = 4096
private const val SPLEETER_HOP = 1024
private const val SPLEETER_BINS = 1024
private const val SPLEETER_FRAME_BLOCK = 512
private const val KIM_FFT = 2048
private const val KIM_HOP = 441
internal const val KIM_FRAMES = 1101
internal const val KIM_SAMPLES_PER_CHANNEL = KIM_HOP * (KIM_FRAMES - 1)

internal enum class SpleeterTensorLayout {
    CHANNELS_FIRST,
    SPLITS_FIRST
}

internal data class SpleeterTensorPlan(
    val layout: SpleeterTensorLayout,
    val splitCount: Int,
    val shape: LongArray
)

/**
 * Keeps the selected separator loaded while bounded song chunks are processed.
 * Closing this engine releases every separator runtime before Whisper is loaded.
 */
internal class SongSeparatorEngine private constructor(
    val model: SongSeparationModel,
    private val primary: OnnxSongSeparatorRuntime?,
    private val secondary: OnnxSongSeparatorRuntime?,
    private val nativeRuntime: CrispSongSeparatorRuntime?
) : Closeable {

    fun separateVocals(interleavedStereo44100: FloatArray): FloatArray {
        require(interleavedStereo44100.size % 2 == 0) { "Audio für die Stimmisolierung muss Stereo sein." }
        return when (model) {
            SongSeparationModel.QUICK -> separateUmx(interleavedStereo44100)
            SongSeparationModel.BALANCED -> separateSpleeter(interleavedStereo44100)
            SongSeparationModel.NATIVE_GGUF -> checkNotNull(nativeRuntime) {
                "Native Kim-Runtime fehlt."
            }.separateVocals(interleavedStereo44100)
            SongSeparationModel.HIGH_QUALITY -> separateKim(interleavedStereo44100)
        }
    }

    private fun separateUmx(audio: FloatArray): FloatArray {
        val runtime = requirePrimary()
        val spectrogram = SongStft.forward(
            interleavedStereo = audio,
            fftSize = UMX_FFT,
            hopSize = UMX_HOP,
            centered = true
        )
        val input = FloatArray(2 * spectrogram.bins * spectrogram.frames)
        for (channel in 0 until 2) {
            for (bin in 0 until spectrogram.bins) {
                for (frame in 0 until spectrogram.frames) {
                    val source = spectrogram.index(channel, bin, frame)
                    val target = ((channel * spectrogram.bins + bin) * spectrogram.frames) + frame
                    input[target] = magnitude(spectrogram.real[source], spectrogram.imaginary[source])
                }
            }
        }
        val estimate = runtime.runFloat(
            input = input,
            shape = longArrayOf(1, 2, spectrogram.bins.toLong(), spectrogram.frames.toLong())
        )
        check(estimate.size == input.size) { "UMXHQ lieferte eine unerwartete Tensorgröße." }
        val real = spectrogram.real.copyOf()
        val imaginary = spectrogram.imaginary.copyOf()
        for (i in input.indices) {
            val ratio = (estimate[i] / input[i].coerceAtLeast(1e-8f)).coerceIn(0f, 4f)
            real[i] *= ratio
            imaginary[i] *= ratio
        }
        return SongStft.inverse(spectrogram.copy(real = real, imaginary = imaginary))
    }

    private fun separateSpleeter(audio: FloatArray): FloatArray {
        val vocalsRuntime = requirePrimary()
        val accompaniment = checkNotNull(secondary) { "Spleeter-Accompaniment-Modell fehlt." }
        val spectrogram = SongStft.forward(
            interleavedStereo = audio,
            fftSize = SPLEETER_FFT,
            hopSize = SPLEETER_HOP,
            centered = false
        )
        val paddedFrames = roundUp(spectrogram.frames, SPLEETER_FRAME_BLOCK)
        val requiredSplits = paddedFrames / SPLEETER_FRAME_BLOCK
        val vocalsPlan = spleeterTensorPlan(vocalsRuntime.inputShape(), requiredSplits)
        val accompanimentPlan = spleeterTensorPlan(accompaniment.inputShape(), requiredSplits)
        check(
            vocalsPlan.layout == accompanimentPlan.layout &&
                vocalsPlan.splitCount == accompanimentPlan.splitCount &&
                vocalsPlan.shape.contentEquals(accompanimentPlan.shape)
        ) {
            "Die beiden Spleeter-Modelle erwarten unterschiedliche Tensorformen."
        }
        val plan = vocalsPlan
        val input = FloatArray(2 * plan.splitCount * SPLEETER_FRAME_BLOCK * SPLEETER_BINS)
        for (channel in 0 until 2) {
            for (frame in 0 until spectrogram.frames) {
                for (bin in 0 until SPLEETER_BINS) {
                    val source = spectrogram.index(channel, bin, frame)
                    val target = spleeterTensorIndex(
                        layout = plan.layout,
                        splitCount = plan.splitCount,
                        channel = channel,
                        frame = frame,
                        bin = bin
                    )
                    input[target] = magnitude(spectrogram.real[source], spectrogram.imaginary[source])
                }
            }
        }
        val vocalsSpec = vocalsRuntime.runFloat(input, plan.shape)
        val accompanimentSpec = accompaniment.runFloat(input, plan.shape)
        check(vocalsSpec.size == input.size && accompanimentSpec.size == input.size) {
            "Spleeter lieferte eine unerwartete Tensorgröße."
        }
        val real = spectrogram.real.copyOf()
        val imaginary = spectrogram.imaginary.copyOf()
        for (channel in 0 until 2) {
            for (frame in 0 until spectrogram.frames) {
                for (bin in 0 until SPLEETER_BINS) {
                    val tensorIndex = spleeterTensorIndex(
                        layout = plan.layout,
                        splitCount = plan.splitCount,
                        channel = channel,
                        frame = frame,
                        bin = bin
                    )
                    val vocal = vocalsSpec[tensorIndex]
                    val other = accompanimentSpec[tensorIndex]
                    val denominator = vocal * vocal + other * other + 1e-10f
                    val mask = (vocal * vocal + 5e-11f) / denominator
                    val specIndex = spectrogram.index(channel, bin, frame)
                    real[specIndex] *= mask
                    imaginary[specIndex] *= mask
                }
            }
        }
        return SongStft.inverse(spectrogram.copy(real = real, imaginary = imaginary))
    }

    private fun separateKim(audio: FloatArray): FloatArray {
        val runtime = requirePrimary()
        require(audio.size / 2 == KIM_SAMPLES_PER_CHANNEL) {
            "Kim Vocal 2 benötigt exakt $KIM_SAMPLES_PER_CHANNEL Samples je Kanal pro Chunk."
        }
        val spectrogram = SongStft.forward(
            interleavedStereo = audio,
            fftSize = KIM_FFT,
            hopSize = KIM_HOP,
            centered = true
        )
        check(spectrogram.frames == KIM_FRAMES) {
            "Kim Vocal 2 STFT hat ${spectrogram.frames} statt $KIM_FRAMES Frames."
        }
        val packedBins = spectrogram.bins * 2
        val input = FloatArray(packedBins * KIM_FRAMES * 2)
        for (bin in 0 until spectrogram.bins) {
            for (channel in 0 until 2) {
                val packedBin = bin * 2 + channel
                for (frame in 0 until KIM_FRAMES) {
                    val source = spectrogram.index(channel, bin, frame)
                    val target = ((packedBin * KIM_FRAMES + frame) * 2)
                    input[target] = spectrogram.real[source]
                    input[target + 1] = spectrogram.imaginary[source]
                }
            }
        }
        val mask = runtime.runFloat(
            input = input,
            shape = longArrayOf(1, packedBins.toLong(), KIM_FRAMES.toLong(), 2)
        )
        check(mask.size == input.size) { "Kim Vocal 2 lieferte eine unerwartete Tensorgröße." }
        val real = spectrogram.real.copyOf()
        val imaginary = spectrogram.imaginary.copyOf()
        for (bin in 0 until spectrogram.bins) {
            for (channel in 0 until 2) {
                val packedBin = bin * 2 + channel
                for (frame in 0 until KIM_FRAMES) {
                    val target = ((packedBin * KIM_FRAMES + frame) * 2)
                    val maskRe = mask[target]
                    val maskIm = mask[target + 1]
                    val source = spectrogram.index(channel, bin, frame)
                    val mixRe = spectrogram.real[source]
                    val mixIm = spectrogram.imaginary[source]
                    real[source] = mixRe * maskRe - mixIm * maskIm
                    imaginary[source] = mixRe * maskIm + mixIm * maskRe
                }
            }
        }
        return SongStft.inverse(spectrogram.copy(real = real, imaginary = imaginary))
    }

    private fun requirePrimary(): OnnxSongSeparatorRuntime =
        checkNotNull(primary) { "ONNX-Separator-Runtime fehlt für ${model.modelLabel}." }

    override fun close() {
        runCatching { nativeRuntime?.close() }
        runCatching { secondary?.close() }
        primary?.close()
    }

    companion object {
        fun open(model: SongSeparationModel, modelDirectory: File, threads: Int): SongSeparatorEngine {
            val directory = File(modelDirectory, model.id)
            model.artifacts.forEach { artifact ->
                val file = File(directory, artifact.fileName)
                require(artifact.isInstalledFile(file)) {
                    "${model.modelLabel} ist nicht vollständig installiert."
                }
            }
            return when (model) {
                SongSeparationModel.QUICK -> SongSeparatorEngine(
                    model = model,
                    primary = OnnxSongSeparatorRuntime.open(File(directory, "umxhq-vocals.onnx"), threads),
                    secondary = null,
                    nativeRuntime = null
                )
                SongSeparationModel.BALANCED -> {
                    val vocals = OnnxSongSeparatorRuntime.open(File(directory, "vocals.fp16.onnx"), threads)
                    try {
                        SongSeparatorEngine(
                            model = model,
                            primary = vocals,
                            secondary = OnnxSongSeparatorRuntime.open(
                                File(directory, "accompaniment.fp16.onnx"),
                                threads
                            ),
                            nativeRuntime = null
                        )
                    } catch (failure: Throwable) {
                        vocals.close()
                        throw failure
                    }
                }
                SongSeparationModel.NATIVE_GGUF -> SongSeparatorEngine(
                    model = model,
                    primary = null,
                    secondary = null,
                    nativeRuntime = CrispSongSeparatorRuntime.open(
                        File(directory, "mel-band-roformer-vocals-f16.gguf"),
                        threads
                    )
                )
                SongSeparationModel.HIGH_QUALITY -> SongSeparatorEngine(
                    model = model,
                    primary = OnnxSongSeparatorRuntime.open(File(directory, "kim-vocal-2.onnx"), threads),
                    secondary = null,
                    nativeRuntime = null
                )
            }
        }
    }
}

internal fun spleeterTensorPlan(modelShape: LongArray, requiredSplits: Int): SpleeterTensorPlan {
    require(requiredSplits > 0)
    require(modelShape.size == 4) {
        "Spleeter erwartet einen vierdimensionalen ONNX-Eingang."
    }
    require(matchesStaticDimension(modelShape[2], SPLEETER_FRAME_BLOCK) &&
        matchesStaticDimension(modelShape[3], SPLEETER_BINS)
    ) {
        "Spleeter hat eine unerwartete Zeit-/Frequenzform: ${modelShape.contentToString()}."
    }

    val layout = when {
        modelShape[0] == 2L && modelShape[1] != 2L -> SpleeterTensorLayout.CHANNELS_FIRST
        modelShape[1] == 2L && modelShape[0] != 2L -> SpleeterTensorLayout.SPLITS_FIRST
        modelShape[0] == 2L && modelShape[1] == 2L -> SpleeterTensorLayout.CHANNELS_FIRST
        modelShape[0] == 2L && modelShape[1] <= 0L -> SpleeterTensorLayout.CHANNELS_FIRST
        modelShape[1] == 2L && modelShape[0] <= 0L -> SpleeterTensorLayout.SPLITS_FIRST
        else -> error("Spleeter-Kanalachse ist unbekannt: ${modelShape.contentToString()}.")
    }
    val declaredSplits = when (layout) {
        SpleeterTensorLayout.CHANNELS_FIRST -> modelShape[1]
        SpleeterTensorLayout.SPLITS_FIRST -> modelShape[0]
    }
    val splitCount = if (declaredSplits > 0L) {
        check(declaredSplits <= Int.MAX_VALUE) { "Spleeter-Splitzahl ist zu groß." }
        val fixed = declaredSplits.toInt()
        check(requiredSplits <= fixed) {
            "Der Audioabschnitt der Stimmisolierung benötigt $requiredSplits Spleeter-Blöcke, das Modell akzeptiert aber nur $fixed."
        }
        fixed
    } else {
        requiredSplits
    }
    val shape = when (layout) {
        SpleeterTensorLayout.CHANNELS_FIRST -> longArrayOf(
            2,
            splitCount.toLong(),
            SPLEETER_FRAME_BLOCK.toLong(),
            SPLEETER_BINS.toLong()
        )
        SpleeterTensorLayout.SPLITS_FIRST -> longArrayOf(
            splitCount.toLong(),
            2,
            SPLEETER_FRAME_BLOCK.toLong(),
            SPLEETER_BINS.toLong()
        )
    }
    return SpleeterTensorPlan(layout, splitCount, shape)
}

internal fun spleeterTensorIndex(
    layout: SpleeterTensorLayout,
    splitCount: Int,
    channel: Int,
    frame: Int,
    bin: Int
): Int {
    require(channel in 0..1)
    require(frame >= 0)
    require(bin in 0 until SPLEETER_BINS)
    val split = frame / SPLEETER_FRAME_BLOCK
    val frameInSplit = frame % SPLEETER_FRAME_BLOCK
    require(split in 0 until splitCount)
    return when (layout) {
        SpleeterTensorLayout.CHANNELS_FIRST ->
            (((channel * splitCount + split) * SPLEETER_FRAME_BLOCK + frameInSplit) * SPLEETER_BINS) + bin
        SpleeterTensorLayout.SPLITS_FIRST ->
            (((split * 2 + channel) * SPLEETER_FRAME_BLOCK + frameInSplit) * SPLEETER_BINS) + bin
    }
}

private fun matchesStaticDimension(actual: Long, expected: Int): Boolean =
    actual <= 0L || actual == expected.toLong()

private fun magnitude(real: Float, imaginary: Float): Float =
    sqrt(real * real + imaginary * imaginary)

private fun roundUp(value: Int, multiple: Int): Int =
    ((value + multiple - 1) / multiple) * multiple
