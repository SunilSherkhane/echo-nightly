package dev.brahmkshatriya.echo.playback.loudness

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/**
 * Measures the integrated loudness of a local audio file with [EbuR128] and
 * caches the result.
 *
 * Scanning decodes the entire file on [Dispatchers.Default], so it is only ever
 * started for files that already live on disk (downloads) and only when no
 * cached value exists. Results are keyed by track id, so a re-download is the
 * only thing that triggers a fresh measurement.
 */
class LoudnessScanner(
    context: Context,
    private val scope: CoroutineScope,
    private val store: LoudnessStore = LoudnessStore(context),
) {

    /** Starts a scan for [file] unless a value is already cached for [trackId]. */
    fun scan(trackId: String, file: File) {
        if (store.loudness(trackId) != null) return
        if (!file.exists() || file.length() == 0L) return
        scope.launch(Dispatchers.Default) {
            val measured = runCatching { measure(file) }.getOrNull()
            if (measured != null && measured.isFinite()) store.save(trackId, measured)
        }
    }

    /** Decodes [file] to 16-bit PCM and runs the EBU R128 meter over it. */
    private suspend fun measure(file: File): Double = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return@withContext Double.NEGATIVE_INFINITY

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val analyzer = EbuR128(sampleRate, channels)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                decode(extractor, codec, analyzer)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec, analyzer: EbuR128): Double {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val scratch = ShortArray(MAX_SAMPLES_PER_BUFFER)

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = codec.getInputBuffer(inputIndex) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> if (isFloatPcm(codec.outputFormat)) {
                    // The meter only understands 16-bit PCM; skip float output.
                    return Double.NEGATIVE_INFINITY
                }

                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        // Raw PCM is 16-bit signed in native byte order; pin it so
                        // samples are not byte-swapped on a big-endian buffer.
                        buffer.order(ByteOrder.nativeOrder())
                        val shortBuffer = buffer.asShortBuffer()
                        while (shortBuffer.hasRemaining()) {
                            val count = minOf(shortBuffer.remaining(), scratch.size)
                            shortBuffer.get(scratch, 0, count)
                            analyzer.feed(scratch, 0, count)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }

        return analyzer.finish()
    }

    private fun isFloatPcm(format: MediaFormat): Boolean =
        format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
            AudioFormat.ENCODING_PCM_FLOAT

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_SAMPLES_PER_BUFFER = 1 shl 14
    }
}
