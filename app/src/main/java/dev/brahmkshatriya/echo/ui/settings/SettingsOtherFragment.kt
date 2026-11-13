package dev.brahmkshatriya.echo.ui.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toResourceImageHolder
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.SETTINGS_NAME
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import dev.brahmkshatriya.echo.utils.exportSettings
import dev.brahmkshatriya.echo.utils.importSettings
import dev.brahmkshatriya.echo.utils.lastfm.LastfmRepository
import dev.brahmkshatriya.echo.utils.ui.prefs.SwitchLongClickPreference
import dev.brahmkshatriya.echo.utils.ui.prefs.TransitionPreference
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsOtherFragment : BaseSettingsFragment() {
    override val title get() = getString(R.string.other_settings)
    override val icon get() = R.drawable.ic_more_horiz.toResourceImageHolder()
    override val creator = { OtherPreference() }

    class OtherPreference : PreferenceFragmentCompat() {

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            configure()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            preferenceManager.sharedPreferencesName = SETTINGS_NAME
            preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            SwitchLongClickPreference(context).apply {
                title = getString(R.string.check_for_updates)
                summary = getString(R.string.check_for_updates_summary)
                key = "check_for_updates"
                layoutResource = R.layout.preference_switch
                isIconSpaceReserved = false
                setDefaultValue(true)
                screen.addPreference(this)
                setOnLongClickListener {
                    val viewModel by activityViewModel<ExtensionsViewModel>()
                    viewModel.update(requireActivity(), true)
                }
            }

            TransitionPreference(context).apply {
                key = "export"
                title = getString(R.string.export_settings)
                summary = getString(R.string.export_settings_summary)
                layoutResource = R.layout.preference
                isIconSpaceReserved = false
                screen.addPreference(this)
                setOnPreferenceClickListener {
                    val contract = ActivityResultContracts.CreateDocument("application/json")
                    requireActivity().registerActivityResultLauncher(contract) { uri ->
                        uri?.let { context.exportSettings(it) }
                    }.launch("echo-settings.json")
                    true
                }
            }

            TransitionPreference(context).apply {
                key = "import"
                title = getString(R.string.import_settings)
                summary = getString(R.string.import_settings_summary)
                layoutResource = R.layout.preference
                isIconSpaceReserved = false
                screen.addPreference(this)
                setOnPreferenceClickListener {
                    val contract = ActivityResultContracts.OpenDocument()
                    requireActivity().registerActivityResultLauncher(contract) {
                        it?.let {
                            context.importSettings(it)
                            requireActivity().recreate()
                        }
                    }.launch(arrayOf("application/json"))
                    true
                }
            }

            PreferenceCategory(context).apply {
                title = getString(R.string.lastfm)
                screen.addPreference(this)

                SwitchPreferenceCompat(context).apply {
                    key = LastfmRepository.KEY_ENABLED
                    title = getString(R.string.lastfm_scrobble)
                    summary = getString(R.string.lastfm_scrobble_summary)
                    layoutResource = R.layout.preference_switch
                    isIconSpaceReserved = false
                    setDefaultValue(false)
                    addPreference(this)
                }

                TransitionPreference(context).apply {
                    key = "lastfm_connect"
                    title = getString(R.string.lastfm_connect)
                    summary = getString(R.string.lastfm_connect_summary)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    addPreference(this)
                    setOnPreferenceClickListener {
                        showLastfmConnectDialog()
                        true
                    }
                }

                TransitionPreference(context).apply {
                    key = "lastfm_disconnect"
                    title = getString(R.string.lastfm_disconnect)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    addPreference(this)
                    setOnPreferenceClickListener {
                        LastfmRepository(requireContext()).disconnect()
                        Toast.makeText(
                            context, getString(R.string.lastfm_disconnect), Toast.LENGTH_SHORT
                        ).show()
                        requireActivity().recreate()
                        true
                    }
                }
            }
        }

        private fun showLastfmConnectDialog() {
            val fragment = this
            val context = fragment.requireContext()
            val hints = listOf(
                R.string.lastfm_username to InputType.TYPE_CLASS_TEXT,
                R.string.lastfm_password to
                    (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                R.string.lastfm_api_key to InputType.TYPE_CLASS_TEXT,
                R.string.lastfm_api_secret to
                    (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            )
            val fields = hints.map { (hint, type) ->
                EditText(context).apply {
                    inputType = type
                    this.hint = getString(hint)
                }
            }
            val (username, password, apiKey, apiSecret) = fields

            val padding = (20 * resources.displayMetrics.density).toInt()
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                fields.forEach { addView(it) }
            }

            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lastfm_connect)
                .setView(container)
                .setPositiveButton(R.string.connect) { _, _ ->
                    if (username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                        Toast.makeText(
                            context, getString(R.string.lastfm_failed), Toast.LENGTH_SHORT
                        ).show()
                        return@setPositiveButton
                    }
                    val repository = LastfmRepository(context)
                    fragment.lifecycleScope.launch {
                        val message = runCatching {
                            repository.connect(
                                username.text.toString(),
                                password.text.toString(),
                                apiKey.text.toString(),
                                apiSecret.text.toString()
                            )
                        }.fold(
                            onSuccess = { fragment.getString(R.string.lastfm_connected, it) },
                            onFailure = {
                                it.message ?: fragment.getString(R.string.lastfm_failed)
                            }
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}