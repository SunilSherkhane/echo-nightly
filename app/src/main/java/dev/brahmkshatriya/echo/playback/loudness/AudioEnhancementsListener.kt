package dev.brahmkshatriya.echo.playback.loudness

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.downloaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.playback.automix.Automix
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import java.io.File

/**
 * Applies loudness normalization and the crossfade whenever the track changes,
 * and re-applies both when the related settings change.
 *
 * Downloaded tracks are handed to [LoudnessScanner] so an EBU R128 value can be
 * measured once and cached; streamed tracks fall back to whatever loudness
 * metadata the extension supplied.
 */
class AudioEnhancementsListener(
    context: Context,
    private val player: ExoPlayer,
    private val loudness: LoudnessController,
    private val crossfade: CrossfadeController,
    private val scanner: LoudnessScanner? = null,
) : Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val settings = context.getSettings()
    private var lastTrack: Track? = null
    private var lastWakeMode: Int? = null

    private fun automixEnabled() = settings.getBoolean(Automix.KEY_ENABLED, false)

    init {
        crossfade.apply(automixEnabled())
        settings.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        lastTrack = mediaItem?.track
        crossfade.apply(automixEnabled())
        loudness.apply(lastTrack)

        // Local playback does not need the network wake lock, and holding it
        // anyway keeps the radio on for the entire track.
        val local = !mediaItem?.downloaded.isNullOrEmpty()
        val wakeMode = if (local) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NETWORK
        if (lastWakeMode != wakeMode) {
            lastWakeMode = wakeMode
            player.setWakeMode(wakeMode)
        }

        val path = mediaItem?.downloaded?.firstOrNull() ?: return
        scanner?.scan(mediaItem.mediaId, File(path))
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            CrossfadeController.KEY_ENABLED,
            CrossfadeController.KEY_DURATION,
            CrossfadeController.KEY_DJ_TRANSITION -> crossfade.apply(automixEnabled())

            LoudnessController.KEY_ENABLED,
            LoudnessController.KEY_MODE,
            LoudnessController.KEY_TARGET,
            LoudnessController.KEY_PREAMP -> loudness.apply(lastTrack)
        }
    }

    fun release() {
        settings.unregisterOnSharedPreferenceChangeListener(this)
    }
}
