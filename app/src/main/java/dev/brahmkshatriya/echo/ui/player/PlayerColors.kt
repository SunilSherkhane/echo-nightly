package dev.brahmkshatriya.echo.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.palette.graphics.Palette
import com.google.android.material.color.MaterialColors
import dev.brahmkshatriya.echo.MainActivity.Companion.isAmoled
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isNightMode

data class PlayerColors(
    val background: Int,
    val accent: Int,
    val onBackground: Int,
) {
    companion object {
        // Palette only needs a thumbnail; extracting from a full-size cover is
        // wasted work and used to hitch the UI on every track change.
        private const val MAX_DIMENSION = 64
        private val cache = object : LruCache<String, PlayerColors>(CACHE_SIZE) {}

        /**
         * Extracts player colours from [bitmap].
         *
         * Pass [cacheKey] to identify the artwork (a track id works well) so a
         * track that comes back around does not pay for extraction twice.
         */
        fun Context.getColorsFrom(bitmap: Bitmap?, cacheKey: String? = null): PlayerColors? {
            bitmap ?: return null
            cacheKey?.let { key -> cache.get(key)?.let { return it } }
            val colors = build(Palette.from(bitmap.downsample()).generate())
            if (colors != null) cacheKey?.let { key -> cache.put(key, colors) }
            return colors
        }

        private fun Context.build(palette: Palette): PlayerColors? {
            return if (!isAmoled()) {
                val lightMode = !isNightMode()
                val lightSwatch = palette.run {
                    lightVibrantSwatch ?: vibrantSwatch ?: lightMutedSwatch
                }
                val darkSwatch = palette.run {
                    darkVibrantSwatch ?: darkMutedSwatch ?: mutedSwatch
                }
                val bgSwatch = if (lightMode) lightSwatch else darkSwatch
                val accentSwatch = if (lightMode) darkSwatch else lightSwatch
                bgSwatch?.run {
                    PlayerColors(rgb, accentSwatch?.rgb ?: titleTextColor, bodyTextColor)
                }
            } else defaultPlayerColors().let { default ->
                val dominantColor = palette.run {
                    vibrantSwatch?.rgb ?: getDominantColor(0).takeIf { it != 0 }
                } ?: return null
                PlayerColors(default.background, dominantColor, default.onBackground)
            }
        }

        fun Context.defaultPlayerColors(): PlayerColors {
            val background = MaterialColors.getColor(
                this, R.attr.navBackground, 0
            )
            val primary = MaterialColors.getColor(
                this, androidx.appcompat.R.attr.colorPrimary, 0
            )
            val onSurface = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurface, 0
            )
            return PlayerColors(background, primary, onSurface)
        }

        fun getDominantColor(bitmap: Bitmap): Int {
            return Palette.from(bitmap.downsample()).generate().getDominantColor(0)
        }

        private fun Bitmap.downsample(): Bitmap {
            val largest = maxOf(width, height)
            if (largest <= MAX_DIMENSION) return this
            val scale = MAX_DIMENSION.toFloat() / largest
            val width = (width * scale).toInt().coerceAtLeast(1)
            val height = (height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(this, width, height, true)
        }

        private const val CACHE_SIZE = 32
    }
}