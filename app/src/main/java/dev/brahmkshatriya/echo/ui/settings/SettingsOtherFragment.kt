package dev.brahmkshatriya.echo.ui.settings

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
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

                EditTextPreference(context).apply {
                    key = LastfmRepository.KEY_USERNAME
                    title = getString(R.string.lastfm_username)
                    summary = getString(R.string.lastfm_username)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    addPreference(this)
                }

                EditTextPreference(context).apply {
                    key = LastfmRepository.KEY_API_KEY
                    title = getString(R.string.lastfm_api_key)
                    summary = getString(R.string.lastfm_api_key_summary)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
                    addPreference(this)
                }

                EditTextPreference(context).apply {
                    key = LastfmRepository.KEY_API_SECRET
                    title = getString(R.string.lastfm_api_secret)
                    summary = getString(R.string.lastfm_api_secret)
                    layoutResource = R.layout.preference
                    isIconSpaceReserved = false
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
            val input = EditText(fragment.requireContext()).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = getString(R.string.lastfm_password)
            }
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.lastfm_connect)
                .setView(input)
                .setPositiveButton(R.string.connect) { _, _ ->
                    val repository = LastfmRepository(fragment.requireContext())
                    val password = input.text.toString()
                    if (password.isBlank()) return@setPositiveButton
                    fragment.lifecycleScope.launch {
                        val message = runCatching {
                            repository.connect(repository.username, password)
                        }.fold(
                            onSuccess = { fragment.getString(R.string.lastfm_connected, it) },
                            onFailure = {
                                it.message ?: fragment.getString(R.string.lastfm_failed)
                            }
                        )
                        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}