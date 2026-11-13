package dev.brahmkshatriya.echo.ui.common

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.utils.GlassBlur

/**
 * Bottom sheet that frosts the content behind it (API 31+).
 *
 * Glass only reads as glass when there is something behind it to refract, so
 * sheets opt in to a real window backdrop blur. Below API 31 the translucent
 * glass surface from the LiquidGlass theme is used instead.
 */
open class GlassBottomSheetDialogFragment : BottomSheetDialogFragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.let { GlassBlur.apply(it) }
        return dialog
    }
}
