package de.matthiasennen.transcript.song

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

data class SongSpectrogram(
    val channels: Int,
    val fftSize: Int,
    val hopSize: Int,
    val frames: Int,
    val real: FloatArray,
    val imaginary: FloatArray,
    val originalSamplesPerChannel: Int,
    val centerPadding: Int
) {
    val bins: Int = fftSize / 2 + 1

    fun index(channel: Int, bin: Int, frame: Int): Int =
        ((channel * bins + bin) * frames) + frame

    fun magnitude(channel: Int, bin: Int, frame: Int): Float {
        val index = index(channel, bin, frame)
        val re = real[index]
        val im = imaginary[index]
        return sqrt(re * re + im * im)
    }
}

internal object SongStft {
    fun periodicHann(size: Int): FloatArray {
        require(size > 0)
        return FloatArray(size) { index ->
            (0.5 - 0.5 * cos(2.0 * PI * index / size)).toFloat()
        }
    }

    fun forward(
        interleavedStereo: FloatArray,
        fftSize: Int,
        hopSize: Int,
        centered: Boolean,
        window: FloatArray = periodicHann(fftSize)
    ): SongSpectrogram {
        require(interleavedStereo.size % 2 == 0) { "Stereo-Audio muss paarweise interleaved sein." }
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "FFT-Größe muss eine Zweierpotenz sein." }
        require(hopSize in 1..fftSize)
        require(window.size == fftSize)

        val samplesPerChannel = interleavedStereo.size / 2
        val pad = if (centered) fftSize / 2 else 0
        val paddedSamples = samplesPerChannel + pad * 2
        val frames = if (paddedSamples <= fftSize) 1 else 1 + (paddedSamples - fftSize + hopSize - 1) / hopSize
        val bins = fftSize / 2 + 1
        val values = 2 * bins * frames
        val real = FloatArray(2 * bins * frames)
        val imaginary = FloatArray(2 * bins * frames)
        val fft = FloatFFT_1D(fftSize.toLong())
        val buffer = FloatArray(fftSize * 2)

        for (channel in 0 until 2) {
            for (frame in 0 until frames) {
                java.util.Arrays.fill(buffer, 0f)
                val frameStart = frame * hopSize - pad
                for (i in 0 until fftSize) {
                    val sourceIndex = reflectedIndex(frameStart + i, samplesPerChannel, centered)
                    val sample = if (sourceIndex >= 0) interleavedStereo[sourceIndex * 2 + channel] else 0f
                    buffer[2 * i] = sample * window[i]
                }
                fft.complexForward(buffer)
                for (bin in 0 until bins) {
                    val target = ((channel * bins + bin) * frames) + frame
                    real[target] = buffer[2 * bin]
                    imaginary[target] = buffer[2 * bin + 1]
                }
            }
        }

        check(real.size == values && imaginary.size == values)
        return SongSpectrogram(
            channels = 2,
            fftSize = fftSize,
            hopSize = hopSize,
            frames = frames,
            real = real,
            imaginary = imaginary,
            originalSamplesPerChannel = samplesPerChannel,
            centerPadding = pad
        )
    }

    fun inverse(
        spectrogram: SongSpectrogram,
        window: FloatArray = periodicHann(spectrogram.fftSize)
    ): FloatArray {
        require(spectrogram.channels == 2)
        require(window.size == spectrogram.fftSize)
        val fftSize = spectrogram.fftSize
        val bins = spectrogram.bins
        val paddedLength = (spectrogram.frames - 1) * spectrogram.hopSize + fftSize
        val channels = Array(2) { FloatArray(paddedLength) }
        val normalization = FloatArray(paddedLength)
        val fft = FloatFFT_1D(fftSize.toLong())
        val buffer = FloatArray(fftSize * 2)

        for (channel in 0 until 2) {
            for (frame in 0 until spectrogram.frames) {
                java.util.Arrays.fill(buffer, 0f)
                for (bin in 0 until bins) {
                    val source = spectrogram.index(channel, bin, frame)
                    buffer[2 * bin] = spectrogram.real[source]
                    buffer[2 * bin + 1] = spectrogram.imaginary[source]
                    if (bin > 0 && bin < fftSize / 2) {
                        val mirror = fftSize - bin
                        buffer[2 * mirror] = spectrogram.real[source]
                        buffer[2 * mirror + 1] = -spectrogram.imaginary[source]
                    }
                }
                fft.complexInverse(buffer, true)
                val start = frame * spectrogram.hopSize
                for (i in 0 until fftSize) {
                    val position = start + i
                    val weight = window[i]
                    channels[channel][position] += buffer[2 * i] * weight
                    if (channel == 0) normalization[position] += weight * weight
                }
            }
        }

        for (i in normalization.indices) {
            val norm = normalization[i]
            if (norm > 1e-8f) {
                channels[0][i] /= norm
                channels[1][i] /= norm
            }
        }

        val cropStart = spectrogram.centerPadding
        val wanted = spectrogram.originalSamplesPerChannel
        val output = FloatArray(wanted * 2)
        for (i in 0 until wanted) {
            val source = (cropStart + i).coerceIn(0, paddedLength - 1)
            output[2 * i] = channels[0][source]
            output[2 * i + 1] = channels[1][source]
        }
        return output
    }

    private fun reflectedIndex(index: Int, length: Int, reflect: Boolean): Int {
        if (index in 0 until length) return index
        if (!reflect || length <= 1) return -1
        val period = 2 * (length - 1)
        var value = index % period
        if (value < 0) value += period
        return if (value < length) value else period - value
    }
}
