package de.matthiasennen.transcript.song

import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

private const val SONG_SAMPLE_RATE = 44_100
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

/**
 * Keeps the selected separator loaded while bounded song chunks are processed.
 * Closing this engine releases every ONNX session before Whisper is loaded.
 */
internal class SongSeparatorEngine private constructor(
    val model: SongSeparationModel,
    private val primary: OnnxSongSeparatorRuntime,
    private val secondary: OnnxSongSeparatorRuntime?
) : Closeable {

    fun separateVocals(interleavedStereo44100: FloatArray): FloatArray {
        require(interleavedStereo44100.size % 2 == 0) { "Song-Audio muss Stereo sein." }
        return when (model) {
            SongSeparationModel.QUICK -> separateUmx(interleavedStereo44100)
            SongSeparationModel.BALANCED -> separateSpleeter(interleavedStereo44100)
            SongSeparationModel.HIGH_QUALITY -> separateKim(interleavedStereo44100)
        }
    }

    private fun separateUmx(audio: FloatArray): FloatArray {
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
        val estimate = primary.runFloat(
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
        val accompaniment = checkNotNull(secondary) { "Spleeter-Accompaniment-Modell fehlt." }
        val spectrogram = SongStft.forward(
            interleavedStereo = audio,
            fftSize = SPLEETER_FFT,
            hopSize = SPLEETER_HOP,
            centered = false
        )
        val paddedFrames = roundUp(spectrogram.frames, SPLEETER_FRAME_BLOCK)
        val chunkCount = paddedFrames / SPLEETER_FRAME_BLOCK
        val input = FloatArray(2 * paddedFrames * SPLEETER_BINS)
        for (channel in 0 until 2) {
            for (frame in 0 until spectrogram.frames) {
                for (bin in 0 until SPLEETER_BINS) {
                    val source = spectrogram.index(channel, bin, frame)
                    val target = ((channel * chunkCount * SPLEETER_FRAME_BLOCK + frame) * SPLEETER_BINS) + bin
                    input[target] = magnitude(spectrogram.real[source], spectrogram.imaginary[source])
                }
            }
        }
        val shape = longArrayOf(2, chunkCount.toLong(), SPLEETER_FRAME_BLOCK.toLong(), SPLEETER_BINS.toLong())
        val vocalsSpec = primary.runFloat(input, shape)
        val accompanimentSpec = accompaniment.runFloat(input, shape)
        check(vocalsSpec.size == input.size && accompanimentSpec.size == input.size) {
            "Spleeter lieferte eine unerwartete Tensorgröße."
        }
        val real = spectrogram.real.copyOf()
        val imaginary = spectrogram.imaginary.copyOf()
        for (channel in 0 until 2) {
            for (frame in 0 until spectrogram.frames) {
                for (bin in 0 until SPLEETER_BINS) {
                    val tensorIndex = ((channel * chunkCount * SPLEETER_FRAME_BLOCK + frame) * SPLEETER_BINS) + bin
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
        val mask = primary.runFloat(
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

    override fun close() {
        runCatching { secondary?.close() }
        primary.close()
    }

    companion object {
        fun open(model: SongSeparationModel, modelDirectory: File, threads: Int): SongSeparatorEngine {
            val directory = File(modelDirectory, model.id)
            model.artifacts.forEach { artifact ->
                val file = File(directory, artifact.fileName)
                require(file.isFile && file.length() == artifact.expectedBytes) {
                    "${model.modelLabel} ist nicht vollständig installiert."
                }
            }
            return when (model) {
                SongSeparationModel.QUICK -> SongSeparatorEngine(
                    model = model,
                    primary = OnnxSongSeparatorRuntime.open(File(directory, "umxhq-vocals.onnx"), threads),
                    secondary = null
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
                            )
                        )
                    } catch (failure: Throwable) {
                        vocals.close()
                        throw failure
                    }
                }
                SongSeparationModel.HIGH_QUALITY -> SongSeparatorEngine(
                    model = model,
                    primary = OnnxSongSeparatorRuntime.open(File(directory, "kim-vocal-2.onnx"), threads),
                    secondary = null
                )
            }
        }
    }
}

private fun magnitude(real: Float, imaginary: Float): Float =
    sqrt(real * real + imaginary * imaginary)

private fun roundUp(value: Int, multiple: Int): Int =
    ((value + multiple - 1) / multiple) * multiple
