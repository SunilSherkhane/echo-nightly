package dev.brahmkshatriya.echo.playback.loudness

import android.content.Context
import androidx.core.content.edit
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings

/** Settings bridge for the [CrossfadeAudioProcessor]. */
class CrossfadeController(
    context: Context,
    private val processor: CrossfadeAudioProcessor,
) {

    private val settings = context.getSettings()

    var enabled: Boolean
        get() = settings.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) = settings.edit { putBoolean(KEY_ENABLED, value) }

    var durationMs: Int
        get() = settings.getInt(KEY_DURATION, CrossfadeAudioProcessor.DEFAULT_DURATION_MS)
        set(value) = settings.edit {
            putInt(KEY_DURATION, value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        }

    /**
     * Overlap used for automix transitions. Automix reaches for a longer blend
     * than a manual skip because the listener did not ask for the change.
     */
    var djTransitionMs: Int
        get() = settings.getInt(KEY_DJ_TRANSITION, DEFAULT_DJ_TRANSITION_MS)
        set(value) = settings.edit {
            putInt(KEY_DJ_TRANSITION, value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        }

    /** Pushes the current settings into the processor. */
    fun apply(automix: Boolean = false) {
        processor.enabled = enabled
        processor.durationMs =
            if (automix) maxOf(durationMs, djTransitionMs) else durationMs
    }

    companion object {
        const val KEY_ENABLED = "crossfade_enabled"
        const val KEY_DURATION = "crossfade_duration"
        const val KEY_DJ_TRANSITION = "dj_transition_ms"

        const val DEFAULT_ENABLED = false
        const val DEFAULT_DJ_TRANSITION_MS = 2500
        const val MIN_DURATION_MS = CrossfadeAudioProcessor.MIN_DURATION_MS
        const val MAX_DURATION_MS = CrossfadeAudioProcessor.MAX_DURATION_MS
    }
}
