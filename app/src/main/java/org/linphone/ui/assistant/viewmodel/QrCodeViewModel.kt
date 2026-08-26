/*
 * Copyright (c) 2010-2026 Belledonne Communications SARL and Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9Configuration
import org.linphone.mango9.Mango9LoginRepository
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils

class QrCodeViewModel
    @UiThread
    constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Mango9 QR Scanner ViewModel]"
    }

    val remoteProvisioningSuccessfulEvent = MutableLiveData<Event<Boolean>>()
    val onErrorEvent = MutableLiveData<Event<Boolean>>()
    private val repository = Mango9LoginRepository(coreContext.context)

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onQrcodeFound(core: Core, result: String?) {
            val remoteUrl = result?.let(LinphoneUtils::getRemoteProvisioningUrlFromUri)
            val verifiedUrl = Mango9Configuration.verifiedProvisioningUrl(remoteUrl)
            if (verifiedUrl == null) {
                Log.e("$TAG Rejected QR code outside the Mango9 provisioning origin")
                showRedToast(R.string.assistant_qr_code_invalid_toast, R.drawable.warning_circle)
                return
            }

            Log.i("$TAG Accepted one-time Mango9 enrollment URL")
            core.nativePreviewWindowId = null
            core.isVideoPreviewEnabled = false
            core.isQrcodeVideoPreviewEnabled = false
            viewModelScope.launch {
                try {
                    repository.enrollFromQr(verifiedUrl.toString())
                    remoteProvisioningSuccessfulEvent.postValue(Event(true))
                } catch (error: Exception) {
                    val message = (error as? Mango9ApiException)?.userMessage
                        ?: "We couldn't reach Mango9. Check your connection and try again."
                    showFormattedRedToast(message, R.drawable.warning_circle)
                    onErrorEvent.postValue(Event(true))
                }
            }
        }
    }

    init {
        coreContext.postOnCoreThread { core ->
            core.addListener(coreListener)
            if (core.globalState != GlobalState.On) {
                Log.e("$TAG Core isn't ON, video preview may not start")
            }
        }
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread { core -> core.removeListener(coreListener) }
        super.onCleared()
    }

    @UiThread
    fun setBackCamera() {
        coreContext.postOnCoreThread { core ->
            core.reloadVideoDevices()
            if (!coreContext.setBackCamera()) {
                core.videoDevicesList.firstOrNull { it != "StaticImage: Static picture" }?.let {
                    core.videoDevice = it
                }
            }
        }
    }
}
