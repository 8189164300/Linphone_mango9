/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of Mango9 for Android and is distributed under the GNU
 * General Public License version 3 or later.
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
import org.linphone.BuildConfig
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.Mango9LicensingFragmentBinding
import org.linphone.ui.main.fragment.GenericMainFragment

@UiThread
class Mango9LicensingFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[Mango9 Licensing]"
        private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
        private const val UPSTREAM_APP_URL =
            "https://github.com/BelledonneCommunications/linphone-android/commit/" +
                "42b1fcce3c8037e6f5a891cf8d108eb47e308386"
        private const val SDK_SOURCE_URL =
            "https://github.com/BelledonneCommunications/linphone-sdk/commit/3896ec0681"
        private const val THIRD_PARTY_URL =
            "https://wiki.linphone.org/xwiki/wiki/public/view/Linphone/Third%20party%20components/"
    }

    private lateinit var binding: Mango9LicensingFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = Mango9LicensingFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.back.setOnClickListener { goBack() }
        bindLink(binding.appLicense, GPL_URL)
        bindLink(binding.upstreamApp, UPSTREAM_APP_URL)
        bindLink(binding.sdkLicense, GPL_URL)
        bindLink(binding.sdkSource, SDK_SOURCE_URL)
        bindLink(binding.thirdParty, THIRD_PARTY_URL)

        val sourceUrl = BuildConfig.MANGO9_SOURCE_CODE_URL.trim()
        if (sourceUrl.isEmpty()) {
            binding.correspondingSource.isEnabled = false
            binding.correspondingSource.alpha = 0.65f
            binding.correspondingSourceSubtitle.setText(R.string.mango9_licensing_source_missing)
        } else {
            binding.correspondingSourceSubtitle.text = getString(
                R.string.mango9_licensing_source_available,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            )
            bindLink(binding.correspondingSource, sourceUrl)
        }
    }

    private fun bindLink(view: View, url: String) {
        view.setOnClickListener { openExternalUri(url) }
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
