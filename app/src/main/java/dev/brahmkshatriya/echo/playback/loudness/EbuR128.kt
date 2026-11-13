package dev.brahmkshatriya.echo.playback.loudness

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming EBU R128 / ITU-R BS.1770-4 loudness meter.
 *
 * Feed 16-bit interleaved PCM through [feed] and read the integrated loudness in
 * LUFS from [finish]. The meter applies the K-weighting filter chain (pre-filter
 * high shelf plus RLB high pass), accumulates 400 ms blocks with a 100 ms hop,
 * then applies the -70 LUFS absolute gate followed by the -10 LU relative gate.
 *
 * Both filters are fed each sample exactly once; blocks overlap on the already
 * filtered stream, so no sample is filtered twice and no full-file buffer is
 * needed.
 */
class EbuR128(
    sampleRate: Int,
    channelCount: Int,
    private val blockSeconds: Double = 0.4,
    private val hopSeconds: Double = 0.1,
) {
    private val blockSize = (blockSeconds * sampleRate).toInt().coerceAtLeast(1)
    private val hop = (hopSeconds * sampleRate).toInt().coerceAtLeast(1)
    private val channels = channelCount.coerceAtLeast(1)

    private val preFilter = Biquad.highShelf(sampleRate)
    private val rlbFilter = Biquad.highPass(sampleRate)

    // BS.1770: front channels (L, R, C) weigh 1.0, surrounds weigh 1.41, LFE is
    // excluded. For stereo every channel is a front channel.
    private val weights = DoubleArray(channels) { index -> if (index >= 3) 1.41 else 1.0 }

    private val filtered = DoubleArray(blockSize * channels)
    private var filled = 0

    private val energies = ArrayList<Double>()
    private val loudness = ArrayList<Double>()

    /** Feeds [length] interleaved 16-bit samples starting at [offset]. */
    fun feed(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        var i = offset
        val end = offset + length
        while (i < end) {
            var channel = 0
            while (channel < channels) {
                val raw = samples[i + channel] / 32768.0
                filtered[filled * channels + channel] = rlbFilter.process(preFilter.process(raw))
                channel++
            }
            filled++
            i += channels
            if (filled >= blockSize) {
                processBlock()
                val keep = blockSize - hop
                System.arraycopy(filtered, hop * channels, filtered, 0, keep * channels)
                filled = keep
            }
        }
    }

    private fun processBlock() {
        var total = 0.0
        for (channel in 0 until channels) {
            var meanSquare = 0.0
            var frame = 0
            while (frame < blockSize) {
                val value = filtered[frame * channels + channel]
                meanSquare += value * value
                frame++
            }
            total += weights[channel] * (meanSquare / blockSize)
        }
        energies.add(total)
        loudness.add(blockLoudness(total))
    }

    /** Integrated loudness in LUFS, or [Double.NEGATIVE_INFINITY] if nothing was fed. */
    fun finish(): Double {
        if (energies.isEmpty()) return Double.NEGATIVE_INFINITY
        val absolute = blockLoudness(gatedMean(absoluteGate = -70.0))
        val relative = absolute - 10.0
        return blockLoudness(gatedMean(absoluteGate = relative))
    }

    private fun gatedMean(absoluteGate: Double): Double {
        var sum = 0.0
        var count = 0
        for (index in energies.indices) {
            if (loudness[index] > absoluteGate) {
                sum += energies[index]
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun safeLog10(value: Double): Double =
        if (value <= 0.0) -Double.MAX_VALUE else log10(value)

    /** Block loudness in LUFS, used for gating. */
    private fun blockLoudness(energy: Double) = -0.691 + 10.0 * safeLog10(energy)

    /**
     * Direct-form II transposed biquad. Coefficients are derived with the RBJ
     * cookbook formulas at the actual sample rate rather than reused from a
     * 48 kHz reference, so the meter stays correct for 44.1 kHz and 96 kHz.
     */
    private class Biquad private constructor(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: Double): Double {
            val output = input * b0 + z1
            z1 = input * b1 - output * a1 + z2
            z2 = input * b2 - output * a2
            return output
        }

        companion object {
            // K-weighting stage 1: high shelf at 1681.97 Hz, +3.9998 dB, Q 0.7072.
            fun highShelf(sampleRate: Int): Biquad {
                val frequency = 1681.974450955533
                val q = 0.7071752369554196
                val gainDb = 3.99984385397
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * Math.PI * frequency / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cos = cos(w0)
                val sqrtA = sqrt(a)
                return Biquad(
                    b0 = a * ((a + 1) + (a - 1) * cos + 2 * sqrtA * alpha),
                    b1 = -2 * a * ((a - 1) + (a + 1) * cos),
                    b2 = a * ((a + 1) + (a - 1) * cos - 2 * sqrtA * alpha),
                    a1 = 2 * ((a - 1) - (a + 1) * cos),
                    a2 = (a + 1) - (a - 1) * cos - 2 * sqrtA * alpha,
                ).normalized(a0 = (a + 1) - (a - 1) * cos + 2 * sqrtA * alpha)
            }

            // K-weighting stage 2: RLB high pass at 38.14 Hz, Q 0.5003.
            fun highPass(sampleRate: Int): Biquad {
                val frequency = 38.13547087602444
                val q = 0.5003270372324666
                val w0 = 2.0 * Math.PI * frequency / sampleRate
                val alpha = sin(w0) / (2.0 * q)
                val cos = cos(w0)
                return Biquad(
                    b0 = (1 + cos) / 2,
                    b1 = -(1 + cos),
                    b2 = (1 + cos) / 2,
                    a1 = -2 * cos,
                    a2 = 1 - alpha,
                ).normalized(a0 = 1 + alpha)
            }

            private fun Biquad.normalized(a0: Double) = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}
