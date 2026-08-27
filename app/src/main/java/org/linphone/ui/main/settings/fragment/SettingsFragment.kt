/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.settings.fragment

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.databinding.SettingsFragmentBinding
import org.linphone.mango9.Mango9CallForwardingPolicy
import org.linphone.ui.GenericActivity
import org.linphone.ui.main.fragment.GenericMainFragment
import org.linphone.ui.main.settings.viewmodel.Mango9CallSettingsViewModel
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.ui.main.settings.viewmodel.SettingsViewModel
import org.linphone.utils.AppUtils
import org.linphone.utils.DialogUtils
import org.linphone.utils.Event
import java.lang.Exception

@UiThread
class SettingsFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Settings Fragment]"

        private const val RINGTONE_PICKER_INTENT_ID = 89
    }

    private lateinit var binding: SettingsFragmentBinding

    private val viewModel: SettingsViewModel by navGraphViewModels(
        R.id.main_nav_graph
    )

    private val mango9CallSettingsViewModel: Mango9CallSettingsViewModel by viewModels()

    private var renderingMango9CallSettings = false

    private val sortContactsByListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val label = viewModel.sortContactsByNames[position]
            val value = viewModel.sortContactsByValues[position]
            Log.i("$TAG Selected contact sorting is now [$label] ($value)")
            viewModel.setContactSorting(value)

            sharedViewModel.forceRefreshContactsList.postValue(Event(true))
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }

    private val layoutListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val label = viewModel.availableLayoutsNames[position]
            val value = viewModel.availableLayoutsValues[position]
            Log.i("$TAG Selected meeting default layout is now [$label] ($value)")
            viewModel.setDefaultLayout(value)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }

    private val themeListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val label = viewModel.availableThemesNames[position]
            val value = viewModel.availableThemesValues[position]
            Log.i("$TAG Selected theme is now [$label] ($value)")
            viewModel.setTheme(value)

            when (value) {
                0 -> Compatibility.forceLightMode(requireContext())
                1 -> Compatibility.forceDarkMode(requireContext())
                else -> Compatibility.setAutoLightDarkMode(requireContext())
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }

    private val colorListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val label = viewModel.availableColorsNames[position]
            val value = viewModel.availableColorsValues[position]
            Log.i("$TAG Selected color is now [$label] ($value)")
            // Be careful not to create an infinite loop
            if (value != viewModel.color.value.orEmpty()) {
                viewModel.setColor(value)
                requireActivity().recreate()
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }

    private val tunnelModeListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            viewModel.tunnelModeIndex.value = position
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SettingsFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        observeToastEvents(viewModel)
        setupMango9CallSettings()

        binding.setBackClickListener {
            goBack()
        }

        binding.setAdvancedCallSettingsClickListener {
            if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                val action = SettingsFragmentDirections.actionSettingsFragmentToSettingsAdvancedCallFragment()
                findNavController().navigate(action)
            }
        }

        binding.setAdvancedSettingsClickListener {
            if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                val action = SettingsFragmentDirections.actionSettingsFragmentToSettingsAdvancedFragment()
                findNavController().navigate(action)
            }
        }

        binding.setDeveloperSettingsClickListener {
            if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                val action = SettingsFragmentDirections.actionSettingsFragmentToSettingsDeveloperFragment()
                findNavController().navigate(action)
            }
        }

        viewModel.recreateActivityEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.w("$TAG Recreate Activity")
                requireActivity().recreate()
            }
        }

        viewModel.showRingtonePickerEvent.observe(viewLifecycleOwner) {
            it.consume { currentRingtone ->
                try {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_TYPE,
                            RingtoneManager.TYPE_RINGTONE
                        )
                        if (currentRingtone != null) {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentRingtone)
                        }
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, AppUtils.getString(R.string.settings_calls_change_ringtone_pick_title))
                    }
                    startActivityForResult(intent, RINGTONE_PICKER_INTENT_ID)
                } catch (e: Exception) {
                    Log.e("$TAG Failed start ringtone picker: $e")
                    val toastMessage = getString(R.string.settings_calls_change_ringtone_picker_unavailable_toast)
                    (requireActivity() as GenericActivity).showRedToast(toastMessage, R.drawable.warning_circle)
                }
            }
        }

        // Setup sort contacts by spinner
        val sortContactsByAdapter = ArrayAdapter(
            requireContext(),
            R.layout.drop_down_item,
            viewModel.sortContactsByNames
        )
        sortContactsByAdapter.setDropDownViewResource(R.layout.generic_dropdown_cell)
        binding.contactsSettings.sortContactsByFirstNameSpinner.adapter = sortContactsByAdapter

        viewModel.sortContactsBy.observe(viewLifecycleOwner) { sort ->
            binding.contactsSettings.sortContactsByFirstNameSpinner.setSelection(
                viewModel.sortContactsByValues.indexOf(sort)
            )
        }
        binding.contactsSettings.sortContactsByFirstNameSpinner.onItemSelectedListener = sortContactsByListener

        viewModel.addLdapServerEvent.observe(viewLifecycleOwner) {
            it.consume {
                if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                    val action =
                        SettingsFragmentDirections.actionSettingsFragmentToLdapServerConfigurationFragment(
                            null
                        )
                    findNavController().navigate(action)
                }
            }
        }

        viewModel.editLdapServerEvent.observe(viewLifecycleOwner) {
            it.consume { name ->
                if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                    val action =
                        SettingsFragmentDirections.actionSettingsFragmentToLdapServerConfigurationFragment(
                            name
                        )
                    findNavController().navigate(action)
                }
            }
        }

        viewModel.addCardDavServerEvent.observe(viewLifecycleOwner) {
            it.consume {
                if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                    val action =
                        SettingsFragmentDirections.actionSettingsFragmentToCardDavAddressBookConfigurationFragment(
                            null
                        )
                    findNavController().navigate(action)
                }
            }
        }

        viewModel.editCardDavServerEvent.observe(viewLifecycleOwner) {
            it.consume { name ->
                if (findNavController().currentDestination?.id == R.id.settingsFragment) {
                    val action =
                        SettingsFragmentDirections.actionSettingsFragmentToCardDavAddressBookConfigurationFragment(
                            name
                        )
                    findNavController().navigate(action)
                }
            }
        }

        // Meeting default layout related
        val layoutAdapter = ArrayAdapter(
            requireContext(),
            R.layout.drop_down_item,
            viewModel.availableLayoutsNames
        )
        layoutAdapter.setDropDownViewResource(R.layout.generic_dropdown_cell)
        binding.meetingsSettings.layoutSpinner.adapter = layoutAdapter

        viewModel.defaultLayout.observe(viewLifecycleOwner) { layout ->
            binding.meetingsSettings.layoutSpinner.setSelection(
                viewModel.availableLayoutsValues.indexOf(layout)
            )
        }
        binding.meetingsSettings.layoutSpinner.onItemSelectedListener = layoutListener

        // Light/Dark theme related
        val themeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.drop_down_item,
            viewModel.availableThemesNames
        )
        themeAdapter.setDropDownViewResource(R.layout.generic_dropdown_cell)
        binding.userInterfaceSettings.themeSpinner.adapter = themeAdapter

        viewModel.theme.observe(viewLifecycleOwner) { theme ->
            binding.userInterfaceSettings.themeSpinner.setSelection(
                viewModel.availableThemesValues.indexOf(theme)
            )
            binding.userInterfaceSettings.themeSpinner.onItemSelectedListener = themeListener
        }

        // Choose main color
        val colorAdapter = ArrayAdapter(
            requireContext(),
            R.layout.drop_down_item,
            viewModel.availableColorsNames
        )
        colorAdapter.setDropDownViewResource(R.layout.generic_dropdown_cell)
        binding.userInterfaceSettings.colorSpinner.adapter = colorAdapter

        viewModel.color.observe(viewLifecycleOwner) { color ->
            binding.userInterfaceSettings.colorSpinner.setSelection(
                viewModel.availableColorsValues.indexOf(color)
            )
            binding.userInterfaceSettings.colorSpinner.onItemSelectedListener = colorListener
        }

        // Tunnel mode
        val tunnelModeAdapter = ArrayAdapter(
            requireContext(),
            R.layout.drop_down_item,
            viewModel.tunnelModeLabels
        )
        tunnelModeAdapter.setDropDownViewResource(R.layout.generic_dropdown_cell)
        binding.tunnelSettings.tunnelModeSpinner.adapter = tunnelModeAdapter
        binding.tunnelSettings.tunnelModeSpinner.onItemSelectedListener = tunnelModeListener

        viewModel.tunnelModeIndex.observe(viewLifecycleOwner) { index ->
            binding.tunnelSettings.tunnelModeSpinner.setSelection(index)
        }

        viewModel.forceRefreshMeetingsListEvent.observe(viewLifecycleOwner) {
            it.consume {
                sharedViewModel.forceRefreshMeetingsListEvent.postValue(Event(true))
            }
        }

        binding.setTurnOnVfsClickListener {
            showConfirmVfsDialog()
        }

        startPostponedEnterTransition()
    }

    private fun setupMango9CallSettings() {
        val section = binding.mango9CallSettings
        section.refresh.setOnClickListener { mango9CallSettingsViewModel.reload(force = true) }
        section.forwardingSwitch.setOnCheckedChangeListener { _, checked ->
            if (!renderingMango9CallSettings) {
                val accepted = mango9CallSettingsViewModel.requestForwardingEnabled(checked)
                if (!accepted) section.destination.requestFocus()
            }
        }
        section.destination.doAfterTextChanged { editable ->
            if (!renderingMango9CallSettings) {
                mango9CallSettingsViewModel.updateForwardingDestination(editable?.toString().orEmpty())
            }
        }
        section.save.setOnClickListener { mango9CallSettingsViewModel.save() }

        mango9CallSettingsViewModel.hasSession.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.settings.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.forwardingEnabled.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.forwardingDestination.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.lineLabel.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.loading.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.saving.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.errorMessage.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.statusMessage.observe(viewLifecycleOwner) { renderMango9CallSettings() }
        mango9CallSettingsViewModel.canSave.observe(viewLifecycleOwner) { renderMango9CallSettings() }
    }

    private fun renderMango9CallSettings() {
        val section = binding.mango9CallSettings
        val visible = mango9CallSettingsViewModel.hasSession.value == true
        section.root.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val loading = mango9CallSettingsViewModel.loading.value == true
        val saving = mango9CallSettingsViewModel.saving.value == true
        val loaded = mango9CallSettingsViewModel.settings.value != null
        val enabled = mango9CallSettingsViewModel.forwardingEnabled.value == true
        val destination = mango9CallSettingsViewModel.forwardingDestination.value.orEmpty()
        val validDestination = Mango9CallForwardingPolicy.isValidDestination(destination)
        section.loading.visibility = if (loading && !loaded) View.VISIBLE else View.GONE
        section.content.visibility = if (loaded) View.VISIBLE else View.GONE
        section.refresh.isEnabled = !loading && !saving
        section.lineLabel.text = mango9CallSettingsViewModel.lineLabel.value.orEmpty()
        section.forwardingStatus.setText(
            if (enabled) R.string.mango9_call_forwarding_enabled else R.string.mango9_call_forwarding_off,
        )
        section.forwardingStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.green_success_500 else R.color.gray_main2_500,
            ),
        )
        renderingMango9CallSettings = true
        section.forwardingSwitch.isChecked = enabled
        if (section.destination.text?.toString() != destination) {
            section.destination.setText(destination)
            section.destination.setSelection(destination.length)
        }
        renderingMango9CallSettings = false
        section.forwardingSwitch.isEnabled = !saving && (enabled || validDestination)
        section.destination.isEnabled = !saving
        section.destinationLayout.error = if (destination.isNotBlank() && !validDestination) {
            getString(R.string.mango9_call_forwarding_invalid_number)
        } else {
            null
        }
        section.destinationLayout.helperText = if (validDestination) {
            null
        } else {
            getString(R.string.mango9_call_forwarding_number_required)
        }
        section.save.isEnabled = mango9CallSettingsViewModel.canSave.value == true
        section.save.text = getString(
            if (saving) R.string.mango9_call_forwarding_saving else R.string.mango9_call_forwarding_save,
        )
        val status = mango9CallSettingsViewModel.statusMessage.value
        section.statusMessage.text = status.orEmpty()
        section.statusMessage.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
        val error = mango9CallSettingsViewModel.errorMessage.value
        section.errorMessage.text = error.orEmpty()
        section.errorMessage.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && requestCode == RINGTONE_PICKER_INTENT_ID) {
            val uri: Uri? = data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                Log.i("$TAG Ringtone picker result is OK, URI found in intent is [$uri]")
                viewModel.setRingtoneUri(uri)
            } else {
                Log.e("$TAG Ringtone picker result is OK but URI is null!")
                // TODO: show error to user
            }
        }
    }

    override fun onResume() {
        super.onResume()

        viewModel.reloadLdapServers()
        viewModel.reloadConfiguredCardDavServers()
        viewModel.reloadShowDeveloperSettings()
        mango9CallSettingsViewModel.reload()
    }

    override fun onPause() {
        if (viewModel.isTunnelAvailable.value == true) {
            viewModel.saveTunnelConfig()
        }

        super.onPause()
    }

    private fun showConfirmVfsDialog() {
        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getConfirmTurningOnVfsDialog(
            requireActivity(),
            model
        )

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                viewModel.isVfsEnabled.value = false
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.w("$TAG Try turning on VFS")
                viewModel.enableVfs()

                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
