package dev.brahmkshatriya.echo.playback.loudness

import android.content.Context
import androidx.core.content.edit

/**
 * Persistent cache of measured integrated loudness, keyed by track id.
 *
 * Measuring a track means decoding the whole file, so results are written once
 * and reused. Stored as a plain preference because it is small, cache-like data
 * that is safe to lose -- a missing entry only means another scan.
 */
class LoudnessStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loudness(trackId: String): Double? {
        val key = key(trackId)
        if (!prefs.contains(key)) return null
        return prefs.getFloat(key, 0f).toDouble()
    }

    fun save(trackId: String, lufs: Double) {
        prefs.edit { putFloat(key(trackId), lufs.toFloat()) }
    }

    fun clear() {
        prefs.edit { clear() }
    }

    private fun key(trackId: String) = "lufs_$trackId"

    private companion object {
        const val PREFS = "loudness"
    }
}
