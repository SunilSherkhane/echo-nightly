package dev.brahmkshatriya.echo.playback.renderer

import android.content.Context
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import dev.brahmkshatriya.echo.playback.loudness.CrossfadeAudioProcessor
import dev.brahmkshatriya.echo.playback.loudness.LoudnessGainProvider

@androidx.annotation.OptIn(UnstableApi::class)
class RenderersFactory(
    context: Context,
    private val gainProvider: LoudnessGainProvider,
    private val crossfade: CrossfadeAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ) = run {
        val silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(
            2_000_000,
            (20_000 / 2_000_000).toFloat(),
            2_000_000,
            0,
            256,
        )

        // Crossfade runs on raw decoded PCM before the loudness gain, so the
        // overlap is mixed at the track's own level and then normalized.
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(crossfade, GainProcessor(gainProvider)),
                    silenceSkippingAudioProcessor,
                    SonicAudioProcessor()
                )
            )
            .build()
    }
}