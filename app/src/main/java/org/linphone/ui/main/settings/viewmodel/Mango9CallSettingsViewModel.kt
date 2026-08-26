/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.settings.viewmodel

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9CallSettings
import org.linphone.mango9.Mango9CrmRepository
import org.linphone.mango9.Mango9LineIdentity
import org.linphone.mango9.Mango9LineIdentityStore
import org.linphone.ui.GenericViewModel

class Mango9CallSettingsViewModel : GenericViewModel() {
    val hasSession = MutableLiveData(false)
    val settings = MutableLiveData<Mango9CallSettings?>()
    val forwardingEnabled = MutableLiveData(false)
    val forwardingDestination = MutableLiveData("")
    val lineLabel = MutableLiveData("")
    val loading = MutableLiveData(false)
    val saving = MutableLiveData(false)
    val errorMessage = MutableLiveData<String?>()
    val statusMessage = MutableLiveData<String?>()
    val canSave = MediatorLiveData<Boolean>()

    private val repository = Mango9CrmRepository(coreContext.context)
    private val lineIdentities = Mango9LineIdentityStore(coreContext.context)
    private var loadedIdentity: String? = null

    init {
        canSave.addSource(forwardingEnabled) { updateCanSave() }
        canSave.addSource(forwardingDestination) { updateCanSave() }
        canSave.addSource(saving) { updateCanSave() }
        updateCanSave()
    }

    fun reload(force: Boolean = false) {
        val identity = repository.activeIdentity()
        val available = identity != null && repository.hasActiveSession()
        hasSession.value = available
        if (!available) {
            loadedIdentity = null
            settings.value = null
            lineLabel.value = ""
            errorMessage.value = null
            statusMessage.value = null
            return
        }
        if (loadedIdentity != identity) {
            loadedIdentity = identity
            settings.value = null
            forwardingEnabled.value = false
            forwardingDestination.value = ""
            lineLabel.value = ""
        }
        if (loading.value == true || (!force && settings.value != null)) return

        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null
            statusMessage.value = null
            try {
                val loaded = repository.callSettings()
                if (repository.activeIdentity() == identity) apply(loaded)
            } catch (error: Exception) {
                if (repository.activeIdentity() == identity) errorMessage.value = userMessage(error)
            } finally {
                loading.value = false
            }
        }
    }

    fun save() {
        if (saving.value == true || canSave.value != true) return
        val identity = repository.activeIdentity() ?: return
        val enabled = forwardingEnabled.value == true
        val destination = forwardingDestination.value.orEmpty().trim()
        viewModelScope.launch {
            saving.value = true
            errorMessage.value = null
            statusMessage.value = null
            try {
                val updated = repository.updateCallForwarding(enabled, destination)
                if (repository.activeIdentity() == identity) {
                    apply(updated)
                    statusMessage.value = if (updated.forwardingEnabled) {
                        "Call forwarding was enabled successfully."
                    } else {
                        "Call forwarding was turned off successfully."
                    }
                }
            } catch (error: Exception) {
                if (repository.activeIdentity() == identity) {
                    errorMessage.value = userMessage(error)
                    settings.value?.let(::apply)
                }
            } finally {
                saving.value = false
            }
        }
    }

    private fun apply(loaded: Mango9CallSettings) {
        settings.value = loaded
        forwardingEnabled.value = loaded.forwardingEnabled
        forwardingDestination.value = loaded.forwardingDestination
        lineIdentities.save(
            Mango9LineIdentity(loaded.extension, loaded.activeNumber),
            repository.activeIdentity(),
        )
        lineLabel.value = Mango9LineIdentityStore.accountLineLabel(
            loaded.extension,
            Mango9LineIdentityStore.formatPhoneNumber(loaded.activeNumber),
            fallback = "",
        )
    }

    private fun updateCanSave() {
        canSave.value = saving.value != true &&
            (forwardingEnabled.value != true || forwardingDestination.value.orEmpty().trim().isNotEmpty())
    }

    private fun userMessage(error: Exception): String =
        (error as? Mango9ApiException)?.userMessage
            ?: error.message?.takeIf(String::isNotBlank)
            ?: "The CRM could not update call forwarding."

}
