package dev.brahmkshatriya.echo.playback.loudness

import android.content.Context
import androidx.core.content.edit
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings

/**
 * Turns loudness information into the gain the player should apply.
 *
 * Two sources are supported and selected by [mode]:
 * - ReplayGain tags carried in [Track.extras] -- the reference level is baked
 *   into the tag, so the gain is used directly.
 * - EBU R128 integrated loudness, either supplied by the extension or measured
 *   by [LoudnessScanner] and read back from [LoudnessStore] -- here the gain is
 *   the distance from the track's loudness to the target.
 */
class LoudnessController(
    context: Context,
    private val provider: LoudnessGainProvider,
    private val store: LoudnessStore = LoudnessStore(context),
) {

    private val settings = context.getSettings()

    var enabled: Boolean
        get() = settings.getBoolean(KEY_ENABLED, false)
        set(value) = settings.edit { putBoolean(KEY_ENABLED, value) }

    var mode: String
        get() = settings.getString(KEY_MODE, MODE_AUTO).orEmpty()
        set(value) = settings.edit { putString(KEY_MODE, value) }

    /** Target integrated loudness in LUFS. Spotify and most streaming use -14. */
    var targetLufs: Int
        get() = settings.getInt(KEY_TARGET, DEFAULT_TARGET_LUFS)
        set(value) = settings.edit { putInt(KEY_TARGET, value) }

    /** Extra gain in dB, applied on top of the computed correction. */
    var preampDb: Int
        get() = settings.getInt(KEY_PREAMP, 0)
        set(value) = settings.edit { putInt(KEY_PREAMP, value) }

    /** Applies the correction for [track], or unity when normalization is off. */
    fun apply(track: Track?) {
        val gain = if (enabled) gainFor(track) else 0f
        provider.setGainDb(gain)
    }

    fun gainFor(track: Track?): Float {
        track ?: return 0f
        val correction = when (mode) {
            MODE_REPLAYGAIN -> replayGainOf(track)
            MODE_EBU -> ebuGainOf(track)
            else -> ebuGainOf(track) ?: replayGainOf(track)
        } ?: return 0f
        return (correction + preampDb).coerceIn(MAX_GAIN_DOWN_DB, MAX_GAIN_UP_DB)
    }

    /** ReplayGain tags are relative to the reference level, so use them as-is. */
    private fun replayGainOf(track: Track): Float? {
        val tag = track.extras[EXTRA_TRACK_GAIN] ?: track.extras[EXTRA_ALBUM_GAIN] ?: return null
        return tag.toGainDb()
    }

    private fun ebuGainOf(track: Track): Float? {
        val lufs = track.extras[EXTRA_LUFS]?.toDoubleOrNull() ?: store.loudness(track.id)
        lufs ?: return null
        return targetLufs.toFloat() - lufs.toFloat()
    }

    /** Parses values such as "+3.2 dB", "-4.5dB" or "-4.5". */
    private fun String.toGainDb(): Float? = trim()
        .replace("dB", "", ignoreCase = true)
        .replace("db", "")
        .trim()
        .toFloatOrNull()

    companion object {
        const val KEY_ENABLED = "loudness_enabled"
        const val KEY_MODE = "loudness_mode"
        const val KEY_TARGET = "loudness_target"
        const val KEY_PREAMP = "loudness_preamp"

        const val MODE_AUTO = "auto"
        const val MODE_REPLAYGAIN = "replaygain"
        const val MODE_EBU = "ebu_r128"
        val modes = listOf(MODE_AUTO, MODE_REPLAYGAIN, MODE_EBU)

        /** Extra keys an extension may use to hand us loudness information. */
        const val EXTRA_TRACK_GAIN = "replaygain_track_gain"
        const val EXTRA_ALBUM_GAIN = "replaygain_album_gain"
        const val EXTRA_LUFS = "lufs"

        const val DEFAULT_TARGET_LUFS = -14
        const val MIN_TARGET_LUFS = -23
        const val MAX_TARGET_LUFS = -5
        const val MAX_PREAMP_DB = 10
        const val MAX_GAIN_UP_DB = 20f
        const val MAX_GAIN_DOWN_DB = -20f
    }
}
