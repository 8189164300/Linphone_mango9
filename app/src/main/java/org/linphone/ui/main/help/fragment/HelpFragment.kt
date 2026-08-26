/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of Mango9 for Android, based on linphone-android, and is
 * distributed under the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.help.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.net.toUri
import androidx.navigation.fragment.findNavController
import org.linphone.BuildConfig
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.HelpFragmentBinding
import org.linphone.ui.main.fragment.GenericMainFragment

@UiThread
class HelpFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Help Fragment]"
    }

    private lateinit var binding: HelpFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = HelpFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.versionSubtitle.text = BuildConfig.VERSION_NAME

        binding.setBackClickListener { goBack() }
        binding.setUserGuideClickListener {
            openExternalUri(getString(R.string.website_user_guide_url))
        }
        binding.setPrivacyPolicyClickListener {
            openExternalUri(getString(R.string.website_privacy_policy_url))
        }
        binding.setSafetySupportClickListener {
            openExternalUri(getString(R.string.mango9_help_safety_email))
        }
        binding.setLicensesClickListener {
            if (findNavController().currentDestination?.id == R.id.helpFragment) {
                findNavController().navigate(
                    HelpFragmentDirections.actionHelpFragmentToMango9LicensingFragment()
                )
            }
        }
    }

    private fun openExternalUri(value: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, value.toUri()))
        } catch (exception: ActivityNotFoundException) {
            Log.e("$TAG No application can open URI [$value]: $exception")
        } catch (exception: IllegalStateException) {
            Log.e("$TAG Can't open URI [$value]: $exception")
        }
    }
}
