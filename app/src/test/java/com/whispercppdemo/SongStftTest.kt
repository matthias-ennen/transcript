package com.whispercppdemo

import de.matthiasennen.transcript.song.SongStft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class SongStftTest {
    @Test
    fun centeredHannRoundTripKeepsStereoSignal() {
        val sampleCount = 16_384
        val stereo = FloatArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            stereo[2 * i] = (0.4 * sin(2.0 * PI * 440.0 * i / 44_100.0)).toFloat()
            stereo[2 * i + 1] = (0.3 * sin(2.0 * PI * 880.0 * i / 44_100.0)).toFloat()
        }

        val spectrum = SongStft.forward(
            interleavedStereo = stereo,
            fftSize = 2048,
            hopSize = 441,
            centered = true
        )
        val restored = SongStft.inverse(spectrum)

        var maxError = 0f
        for (i in stereo.indices) {
            maxError = maxOf(maxError, abs(stereo[i] - restored[i]))
        }
        assertTrue("maxError=$maxError", maxError < 1e-3f)
    }

    @Test
    fun nonCenteredRoundTripKeepsCoveredSignal() {
        val sampleCount = 12_288
        val stereo = FloatArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            stereo[2 * i] = (0.25 * sin(2.0 * PI * 220.0 * i / 44_100.0)).toFloat()
            stereo[2 * i + 1] = (0.2 * sin(2.0 * PI * 330.0 * i / 44_100.0)).toFloat()
        }

        val spectrum = SongStft.forward(
            interleavedStereo = stereo,
            fftSize = 4096,
            hopSize = 1024,
            centered = false
        )
        val restored = SongStft.inverse(spectrum)

        var averageError = 0.0
        val from = 4096
        val until = sampleCount - 4096
        var count = 0
        for (i in from until until) {
            averageError += abs(stereo[2 * i] - restored[2 * i])
            averageError += abs(stereo[2 * i + 1] - restored[2 * i + 1])
            count += 2
        }
        averageError /= count.coerceAtLeast(1)
        assertTrue("averageError=$averageError", averageError < 1e-4)
    }
}
