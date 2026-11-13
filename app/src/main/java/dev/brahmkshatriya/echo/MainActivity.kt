package dev.brahmkshatriya.echo

import android.content.Context
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.add
import androidx.fragment.app.commit
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.navigation.NavigationBarView
import dev.brahmkshatriya.echo.databinding.ActivityMainBinding
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.ui.common.ExceptionUtils.setupExceptionHandler
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.setupIntents
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler.Companion.setupSnackBar
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.setupNavBarAndInsets
import dev.brahmkshatriya.echo.ui.common.UiViewModel.Companion.setupPlayerBehavior
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel.Companion.configureExtensionsUpdater
import dev.brahmkshatriya.echo.ui.main.MainFragment
import dev.brahmkshatriya.echo.ui.player.PlayerFragment
import dev.brahmkshatriya.echo.ui.player.PlayerFragment.Companion.PLAYER_COLOR
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.PermsUtils.checkAppPermissions
import dev.brahmkshatriya.echo.utils.ui.UiUtils.isNightMode
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

open class MainActivity : AppCompatActivity() {

    class Back : MainActivity()

    val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val uiViewModel by viewModel<UiViewModel>()
    private val extensionLoader by inject<ExtensionLoader>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(getAppTheme())
        applyCornerOverlay()
        val dynamicOptions = applyUiChanges(this, uiViewModel)
        if (getSettings().getBoolean(DYNAMIC_COLOR_KEY, true)) {
            DynamicColors.applyToActivityIfAvailable(this, dynamicOptions)
        }

        setContentView(binding.root)

        enableEdgeToEdge(
            SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            if (isNightMode()) SystemBarStyle.dark(TRANSPARENT)
            else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        )

        setupNavBarAndInsets(uiViewModel, binding.root, binding.navView as NavigationBarView)
        setupPlayerBehavior(uiViewModel, binding.playerFragmentContainer)
        setupExceptionHandler(setupSnackBar(uiViewModel, binding.root))
        checkAppPermissions { extensionLoader.setPermGranted() }
        configureExtensionsUpdater()
        supportFragmentManager.commit {
            if (savedInstanceState != null) return@commit
            add<MainFragment>(R.id.navHostFragment, "main")
            add<PlayerFragment>(R.id.playerFragmentContainer, "player")
        }
        setupIntents(uiViewModel)
    }

    companion object {
        const val THEME_KEY = "theme"
        const val AMOLED_KEY = "amoled"
        const val BIG_COVER = "big_cover"
        const val CUSTOM_THEME_KEY = "custom_theme"
        const val COLOR_KEY = "color"

        const val UI_STYLE_KEY = "ui_style"
        const val UI_STYLE_MATERIAL = "material"
        const val UI_STYLE_LIQUID = "liquid"
        const val UI_STYLE_GLASS = "glass"

        const val CORNER_KEY = "corner_radius"
        const val CORNER_SHARP = "sharp"
        const val CORNER_DEFAULT = "default"
        const val CORNER_ROUND = "round"

        const val DYNAMIC_COLOR_KEY = "dynamic_color"

        fun Context.getAppTheme(): Int {
            val settings = getSettings()
            val style = settings.getString(UI_STYLE_KEY, UI_STYLE_GLASS)
            val bigCover = settings.getBoolean(BIG_COVER, false)
            val amoled = settings.getBoolean(AMOLED_KEY, false)
            return when (style) {
                UI_STYLE_LIQUID -> when {
                    amoled && bigCover -> R.style.LiquidGlassAmoledBigCover
                    amoled -> R.style.LiquidGlassAmoled
                    bigCover -> R.style.LiquidGlassBigCover
                    else -> R.style.LiquidGlass
                }

                UI_STYLE_MATERIAL -> when {
                    amoled && bigCover -> R.style.AmoledBigCover
                    amoled -> R.style.Amoled
                    bigCover -> R.style.BigCover
                    else -> R.style.Default
                }

                // White frost is the default interface style.
                else -> when {
                    amoled && bigCover -> R.style.GlassmorphicAmoledBigCover
                    amoled -> R.style.GlassmorphicAmoled
                    bigCover -> R.style.GlassmorphicBigCover
                    else -> R.style.Glassmorphic
                }
            }
        }

        /** Corner radius overlay, layered on top of the style once it is set. */
        fun Context.applyCornerOverlay() {
            val corner = getSettings().getString(CORNER_KEY, CORNER_DEFAULT)
            val overlay = when (corner) {
                CORNER_SHARP -> R.style.CornerSharp
                CORNER_ROUND -> R.style.CornerRound
                else -> R.style.CornerDefault
            }
            theme.applyStyle(overlay, true)
        }

        /** True when the active style uses translucent surfaces (and window blur). */
        fun Context.isGlassStyle(): Boolean {
            val style = getSettings().getString(UI_STYLE_KEY, UI_STYLE_GLASS)
            return style == UI_STYLE_LIQUID || style == UI_STYLE_GLASS
        }

        fun Context.defaultColor() =
            ContextCompat.getColor(this, R.color.app_color)

        fun Context.isAmoled() = getSettings().getBoolean(AMOLED_KEY, false)

        fun applyUiChanges(context: Context, uiViewModel: UiViewModel): DynamicColorsOptions {
            val settings = context.getSettings()
            val mode = when (settings.getString(THEME_KEY, "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)

            val custom = settings.getBoolean(CUSTOM_THEME_KEY, true)
            val color = if (custom) settings.getInt(COLOR_KEY, context.defaultColor()) else null
            val playerColor = settings.getBoolean(PLAYER_COLOR, false)
            val customColor = uiViewModel.playerColors.value?.accent?.takeIf { playerColor }

            val builder = DynamicColorsOptions.Builder()
            (customColor ?: color)?.let { builder.setContentBasedSource(it) }
            return builder.build()
        }

        const val BACK_ANIM = "back_anim"
        fun Context.getMainActivity() = if (getSettings().getBoolean(BACK_ANIM, false))
            Back::class.java else MainActivity::class.java
    }
}