package dev.brahmkshatriya.echo.playback.loudness

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Overlapping track crossfade.
 *
 * A single [BaseAudioProcessor] only ever sees one audio stream, so a true
 * crossfade is done by keeping the tail of the outgoing track and mixing it
 * into the start of the incoming one. On a flush the ring buffer holding the
 * most recent [durationMs] is snapshotted; the next buffers are then mixed with
 * that snapshot, the old tail fading out while the new track fades in.
 *
 * Because the tail is only ever a short window, a flush caused by a seek also
 * produces a short fade rather than a click, which is the behaviour users
 * generally expect.
 */
class CrossfadeAudioProcessor : BaseAudioProcessor() {

    @Volatile var enabled: Boolean = true

    @Volatile var durationMs: Int = DEFAULT_DURATION_MS

    private var channels = 0
    private var sampleRate = 0
    private var tailFrames = 0
    private var fadeFrames = 1

    private var ring = ShortArray(0)
    private var ringPosition = 0
    private var ringFull = false

    private var pendingTail: ShortArray? = null
    private var pendingTailRead = 0
    private var pendingTailFrames = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate
        tailFrames = durationMs * inputAudioFormat.sampleRate / 1000
        fadeFrames = tailFrames.coerceAtLeast(1)
        ring = ShortArray(tailFrames * channels)
        ringPosition = 0
        ringFull = false
        pendingTail = null
        pendingTailRead = 0
        pendingTailFrames = 0
        return AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate, channels, C.ENCODING_PCM_16BIT
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        ensureRing()
        val byteCount = inputBuffer.remaining()
        val output = replaceOutputBuffer(byteCount)
        val frameCount = byteCount / (2 * channels)

        var frame = 0
        while (frame < frameCount) {
            var channel = 0
            while (channel < channels) {
                val sample = inputBuffer.getShort().toInt()
                var mixed = sample

                val tail = pendingTail
                if (enabled && tail != null && pendingTailRead < tail.size) {
                    val tailSample = tail[pendingTailRead].toInt()
                    pendingTailRead++
                    val progress = (pendingTailFrames.toFloat() / fadeFrames).coerceIn(0f, 1f)
                    // Outgoing tail fades out, incoming track fades in.
                    mixed = (tailSample * (1f - progress) + sample * progress).toInt()
                }

                ring[ringPosition * channels + channel] = sample.toShort()
                output.putShort(mixed.toShort())
                channel++
            }

            ringPosition++
            if (ringPosition >= tailFrames) {
                ringPosition = 0
                ringFull = true
            }
            pendingTailFrames++
            frame++
        }

        output.flip()
    }

    /** Re-sizes the tail window when the duration setting changes at runtime. */
    private fun ensureRing() {
        if (channels == 0) return
        val desired = durationMs * sampleRate / 1000 * channels
        if (desired == ring.size) return
        ring = ShortArray(desired)
        tailFrames = desired / channels
        fadeFrames = tailFrames.coerceAtLeast(1)
        ringPosition = 0
        ringFull = false
    }

    override fun onFlush() {
        if (ring.isNotEmpty()) {
            val snapshot = ShortArray(ring.size)
            if (ringFull) {
                System.arraycopy(ring, ringPosition * channels, snapshot, 0, ring.size - ringPosition * channels)
                System.arraycopy(ring, 0, snapshot, ring.size - ringPosition * channels, ringPosition * channels)
            } else {
                val available = min(ringPosition * channels, ring.size)
                System.arraycopy(ring, 0, snapshot, 0, available)
            }
            pendingTail = snapshot
            pendingTailRead = 0
            pendingTailFrames = 0
        }
        ring.fill(0)
        ringPosition = 0
        ringFull = false
        super.onFlush()
    }

    override fun isActive() = enabled && durationMs > 0

    companion object {
        const val DEFAULT_DURATION_MS = 1200
        const val MIN_DURATION_MS = 200
        const val MAX_DURATION_MS = 5000
    }
}
