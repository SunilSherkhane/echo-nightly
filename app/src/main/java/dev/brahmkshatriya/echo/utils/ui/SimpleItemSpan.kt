package dev.brahmkshatriya.echo.utils.ui

import android.content.Context
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import dev.brahmkshatriya.echo.R

class SimpleItemSpan(
    val context: Context,
    private val color: (() -> Int)? = null,
    private val onItemClicked: () -> Unit
) : ClickableSpan() {

    override fun onClick(widget: View) {
        onItemClicked()
    }

    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.isUnderlineText = false
        // Callers on a runtime-palette surface pass a provider so the link
        // follows the palette instead of the day/night foreground.
        ds.color = color?.invoke()
            ?: context.resources.getColor(R.color.button_player, context.theme)
    }
}