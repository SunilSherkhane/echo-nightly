package dev.brahmkshatriya.echo.utils

import android.os.Build
import android.view.Window

/**
 * Applies a real backdrop blur to a window.
 *
 * A bottom sheet or dialog is its own window, so blurring that window is the
 * only way in the View system to frost the *content* behind a surface rather
 * than the surface's own pixels. On older APIs this is a no-op and the
 * translucent glass drawable from the LiquidGlass theme carries the look.
 */
object GlassBlur {

    private const val DEFAULT_RADIUS_DP = 24

    fun apply(window: Window, radiusDp: Int = DEFAULT_RADIUS_DP) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching { window.setBackgroundBlurRadius(radiusDp) }
    }
}
