/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL and Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.assistant.fragment

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.linphone.BuildConfig
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantLandingFragmentBinding
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.viewmodel.Mango9LoginViewModel

@UiThread
class LandingFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Mango9 Login Fragment]"
    }

    private lateinit var binding: AssistantLandingFragmentBinding
    private val viewModel: Mango9LoginViewModel by navGraphViewModels(R.id.assistant_nav_graph)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = AssistantLandingFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        val loginForm = binding.mango9LoginForm
        view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(right - left - (50 * resources.displayMetrics.density).toInt(),
                (520 * resources.displayMetrics.density).toInt())
            if (width > 0 && loginForm.layoutParams.width != width) {
                loginForm.layoutParams = loginForm.layoutParams.apply { this.width = width }
            }
        }
        observeToastEvents(viewModel)

        binding.setBackClickListener {
            viewModel.cancelPendingActions()
            requireActivity().finish()
        }
        binding.setHelpClickListener {
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                viewModel.cancelPendingActions()
                findNavController().navigate(LandingFragmentDirections.actionLandingFragmentToHelpFragment())
            }
        }
        binding.setManualSipClickListener { showConnectionSettingsGate() }

        viewModel.showPassword.observe(viewLifecycleOwner) {
            viewLifecycleOwner.lifecycleScope.launch {
                delay(50)
                binding.password.setSelection(binding.password.text?.length ?: 0)
            }
        }
        viewModel.accountLoggedInEvent.observe(viewLifecycleOwner) { event ->
            event?.consume {
                Log.i("$TAG Mango9 account connected, leaving assistant")
                requireActivity().finish()
            }
        }
        viewModel.restoreSavedSessionIfNeeded()
    }

    private fun showConnectionSettingsGate() {
        val field = TextInputEditText(requireContext()).apply {
            id = R.id.mango9_settings_password
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
        }
        val input = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.mango9_settings_password)
            addView(field)
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mango9_connection_settings)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mango9_settings_unlock, null)
            .create()
        fun unlock() {
            if (field.text?.toString()?.trim() != BuildConfig.VERSION_NAME) {
                input.error = getString(R.string.mango9_settings_password_error)
                return
            }
            dialog.dismiss()
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                viewModel.cancelPendingActions()
                findNavController().navigate(LandingFragmentDirections.actionLandingFragmentToThirdPartySipAccountLoginFragment())
            }
        }
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { unlock() } }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { unlock(); true } else false
        }
        dialog.show()
    }

    override fun onStop() {
        viewModel.cancelPendingActions()
        super.onStop()
    }

    override fun onDestroyView() {
        viewModel.cancelPendingActions()
        binding.unbind()
        super.onDestroyView()
    }
}
