/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.main.settings.viewmodel

import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9CallForwardingPolicy
import org.linphone.mango9.Mango9CallSettings
import org.linphone.mango9.Mango9CrmRepository
import org.linphone.mango9.Mango9LineIdentity
import org.linphone.mango9.Mango9LineIdentityStore
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9SessionStore
import org.linphone.mango9.Mango9LatestRequestGate
import org.linphone.ui.GenericViewModel

@UiThread
class Mango9CallSettingsViewModel internal constructor(
    private val sessions: Mango9SessionStore,
    private val lineIdentities: Mango9LineIdentityStore,
    private val loadSettings: suspend (Mango9SessionLease) -> Mango9CallSettings,
    private val saveSettings: suspend (Mango9SessionLease, Boolean, String) -> Mango9CallSettings,
) : GenericViewModel() {
    constructor() : this(Mango9CrmRepository(coreContext.context))

    private constructor(repository: Mango9CrmRepository) : this(
        Mango9SessionStore(coreContext.context), Mango9LineIdentityStore(coreContext.context),
        repository::callSettings, repository::updateCallForwarding,
    )

    val hasSession = MutableLiveData(false)
    val settings = MutableLiveData<Mango9CallSettings?>()
    val forwardingEnabled = MutableLiveData(false)
    val forwardingDestination = MutableLiveData("")
    val lineLabel = MutableLiveData("")
    val loading = MutableLiveData(false)
    val saving = MutableLiveData(false)
    val errorMessage = MutableLiveData<String?>()
    val statusMessage = MutableLiveData<String?>()
    val canSave = MutableLiveData(false)

    private var selected: Mango9SessionLease? = null
    private val requests = Mango9LatestRequestGate()
    private var work: Job? = null
    private var visible = false
    private var needsRefresh = true

    init {
        viewModelScope.launch {
            Mango9SessionStore.contextChanges.collect {
                if (synchronizeAccount() && visible) reload()
            }
        }
    }

    fun resume() {
        visible = true
        reload()
        updateCanSave()
    }

    fun pause() {
        visible = false
        // A cancelled write may already have reached the server. Re-read on return;
        // never report cancellation as a server failure or silently retry the write.
        if (loading.value == true || saving.value == true) needsRefresh = true
        cancelWork()
        updateCanSave()
    }

    private fun synchronizeAccount(): Boolean {
        val captured = sessions.captureSession()
        if (selected?.let { sessions.currentSession(it) != null } == true ||
            (selected == null && captured == null)
        ) {
            return false
        }
        cancelWork()
        selected = captured
        needsRefresh = true
        hasSession.value = captured != null
        settings.value = null
        forwardingEnabled.value = false
        forwardingDestination.value = ""
        lineLabel.value = ""
        errorMessage.value = null
        statusMessage.value = null
        updateCanSave()
        return true
    }

    fun reload(force: Boolean = false) {
        synchronizeAccount()
        val lease = selected ?: return
        if (!visible || saving.value == true ||
            (!force && (loading.value == true || (settings.value != null && !needsRefresh)))
        ) {
            return
        }
        runRequest(lease, isSave = false) { loadSettings(lease) }
    }

    fun save() {
        if (synchronizeAccount()) { if (visible) reload(); return }
        updateCanSave()
        if (canSave.value != true) return
        val lease = selected ?: return
        val enabled = forwardingEnabled.value == true
        val destination = try {
            Mango9CallForwardingPolicy.validatedDestination(
                enabled,
                forwardingDestination.value.orEmpty(),
            )
        } catch (error: Mango9ApiException.InvalidForwardingDestination) {
            forwardingEnabled.value = false
            errorMessage.value = error.userMessage
            statusMessage.value = null
            return
        }
        runRequest(lease, isSave = true) { saveSettings(lease, enabled, destination) }
    }

    private fun runRequest(
        lease: Mango9SessionLease, isSave: Boolean, operation: suspend () -> Mango9CallSettings,
    ) {
        cancelWork()
        val request = requests.next()
        loading.value = !isSave
        saving.value = isSave
        errorMessage.value = null
        statusMessage.value = null
        updateCanSave()
        work = viewModelScope.launch {
            try {
                val updated = operation()
                if (isCurrent(request, lease)) {
                    sessions.withCurrentSession(lease) {
                        lineIdentities.save(Mango9LineIdentity(updated.extension, updated.activeNumber), lease.session.sipIdentity)
                        apply(updated)
                        needsRefresh = false
                        if (isSave) {
                            statusMessage.value = if (updated.forwardingEnabled) {
                            "Call forwarding was enabled successfully."
                        } else {
                            "Call forwarding was turned off successfully."
                        }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrent(request, lease)) {
                    errorMessage.value = userMessage(error)
                    if (isSave) settings.value?.let(::apply)
                }
            } finally {
                if (isCurrent(request, lease)) {
                    loading.value = false
                    saving.value = false
                    updateCanSave()
                }
            }
        }
    }

    private fun isCurrent(request: Long, lease: Mango9SessionLease) =
        visible && requests.isLatest(request) && sessions.currentSession(lease) != null

    private fun cancelWork() {
        requests.invalidate()
        work?.cancel()
        work = null
        loading.value = false
        saving.value = false
    }

    fun requestForwardingEnabled(enabled: Boolean): Boolean {
        if (!isEditable()) return false
        if (
            enabled &&
            !Mango9CallForwardingPolicy.isValidDestination(forwardingDestination.value.orEmpty())
        ) {
            forwardingEnabled.value = false
            errorMessage.value = Mango9ApiException.InvalidForwardingDestination.userMessage
            statusMessage.value = null
            updateCanSave()
            return false
        }
        forwardingEnabled.value = enabled
        if (errorMessage.value == Mango9ApiException.InvalidForwardingDestination.userMessage) {
            errorMessage.value = null
        }
        updateCanSave()
        return true
    }

    fun updateForwardingDestination(destination: String) {
        if (!isEditable()) return
        forwardingDestination.value = destination
        if (
            Mango9CallForwardingPolicy.isValidDestination(destination) &&
            errorMessage.value == Mango9ApiException.InvalidForwardingDestination.userMessage
        ) {
            errorMessage.value = null
        }
        updateCanSave()
    }

    private fun apply(loaded: Mango9CallSettings) {
        settings.value = loaded
        val validDestination = Mango9CallForwardingPolicy.isValidDestination(loaded.forwardingDestination)
        forwardingEnabled.value = loaded.forwardingEnabled
        forwardingDestination.value = loaded.forwardingDestination
        if (loaded.forwardingEnabled && !validDestination) {
            errorMessage.value = Mango9ApiException.InvalidForwardingDestination.userMessage
        }
        lineLabel.value = Mango9LineIdentityStore.accountLineLabel(
            loaded.extension,
            Mango9LineIdentityStore.formatPhoneNumber(loaded.activeNumber),
            fallback = "",
        )
    }

    private fun updateCanSave() {
        val destination = forwardingDestination.value.orEmpty()
        val validDestination = Mango9CallForwardingPolicy.isValidDestination(destination)
        canSave.value = isEditable() &&
            (
                validDestination ||
                    (forwardingEnabled.value != true && destination.isBlank())
            )
    }

    private fun isEditable(): Boolean = visible && settings.value != null &&
        loading.value != true && saving.value != true &&
        selected?.let { sessions.currentSession(it) != null } == true

    override fun onCleared() {
        visible = false
        cancelWork()
        super.onCleared()
    }

    private fun userMessage(error: Exception): String =
        (error as? Mango9ApiException)?.userMessage
            ?: error.message?.takeIf(String::isNotBlank)
            ?: "The CRM could not update call forwarding."

}
