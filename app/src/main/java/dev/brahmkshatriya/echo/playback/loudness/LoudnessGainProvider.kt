package dev.brahmkshatriya.echo.playback.loudness

import androidx.media3.common.audio.GainProcessor
import kotlin.math.pow

/**
 * Applies a per-track loudness gain through Media3's [GainProcessor].
 *
 * The gain is expressed in decibels and converted to a linear factor, so a
 * track measured at -20 LUFS played with a -14 LUFS target comes through 6 dB
 * hotter. Changes are ramped over a few milliseconds to avoid a click at the
 * moment the target changes.
 */
class LoudnessGainProvider : GainProcessor.GainProvider {

    @Volatile private var fromFactor = 1f
    @Volatile private var toFactor = 1f
    @Volatile private var rampStart = Long.MAX_VALUE
    @Volatile private var rampEnd = Long.MAX_VALUE
    @Volatile private var rampPending = false
    @Volatile private var pendingRampMs = DEFAULT_RAMP_MS
    @Volatile private var sampleRate = 48_000

    fun setSampleRate(rate: Int) {
        if (rate > 0) sampleRate = rate
    }

    /** Sets the gain in dB, ramping from the current value over [rampMs]. */
    fun setGainDb(gainDb: Float, rampMs: Int = DEFAULT_RAMP_MS) {
        val target = linear(gainDb)
        if (!rampPending && target == toFactor) return
        fromFactor = toFactor
        toFactor = target
        // Latched to the first sample position queried after the change, so the
        // ramp always starts at the buffer currently being rendered.
        rampStart = Long.MAX_VALUE
        rampEnd = Long.MAX_VALUE
        rampPending = true
        pendingRampMs = rampMs
    }

    fun reset() {
        fromFactor = 1f
        toFactor = 1f
        rampPending = false
        rampStart = Long.MAX_VALUE
        rampEnd = Long.MAX_VALUE
    }

    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleCount: Int): Float {
        if (rampPending && rampStart == Long.MAX_VALUE) {
            rampStart = samplePosition
            val duration = if (pendingRampMs > 0) {
                pendingRampMs.toLong() * sampleRate / 1000L
            } else 1L
            rampEnd = samplePosition + duration
        }
        if (!rampPending) return toFactor
        if (samplePosition >= rampEnd) {
            rampPending = false
            return toFactor
        }
        if (samplePosition <= rampStart) return fromFactor
        val progress = (samplePosition - rampStart).toFloat() / (rampEnd - rampStart).toFloat()
        return fromFactor + (toFactor - fromFactor) * progress
    }

    /**
     * Only consulted by [GainProcessor] while the returned factor is exactly
     * 1.0, so [Long.MAX_VALUE] is a safe "nothing to do" answer outside a ramp.
     */
    override fun isUnityUntil(samplePosition: Long, sampleCount: Int): Long =
        if (rampPending && samplePosition < rampEnd) samplePosition else Long.MAX_VALUE

    companion object {
        const val DEFAULT_RAMP_MS = 25

        /** Decibels to a linear amplitude factor. */
        fun linear(gainDb: Float): Float = 10f.pow(gainDb / 20f)
    }
}
