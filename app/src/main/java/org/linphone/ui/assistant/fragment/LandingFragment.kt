/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL and Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.assistant.fragment

import android.os.Bundle
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
        observeToastEvents(viewModel)

        binding.setBackClickListener { requireActivity().finish() }
        binding.setHelpClickListener {
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                findNavController().navigate(LandingFragmentDirections.actionLandingFragmentToHelpFragment())
            }
        }
        binding.setQrCodeClickListener {
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                findNavController().navigate(LandingFragmentDirections.actionLandingFragmentToQrCodeScannerFragment())
            }
        }
        binding.setManualSipClickListener {
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                findNavController().navigate(
                    LandingFragmentDirections.actionLandingFragmentToThirdPartySipAccountLoginFragment(),
                )
            }
        }

        viewModel.showPassword.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                delay(50)
                binding.password.setSelection(binding.password.text?.length ?: 0)
            }
        }
        viewModel.accountLoggedInEvent.observe(viewLifecycleOwner) { event ->
            event.consume {
                Log.i("$TAG Mango9 account connected, leaving assistant")
                requireActivity().finish()
            }
        }
        viewModel.restoreSavedSessionIfNeeded()
    }
}
