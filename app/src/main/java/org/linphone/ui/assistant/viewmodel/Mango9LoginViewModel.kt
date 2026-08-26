/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.ui.assistant.viewmodel

import androidx.annotation.UiThread
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.mango9.Mango9ApiException
import org.linphone.mango9.Mango9LoginRepository
import org.linphone.ui.GenericViewModel
import org.linphone.utils.Event

@UiThread
class Mango9LoginViewModel : GenericViewModel() {
    private enum class LoginMode { Password, EmailRequest, EmailVerification }

    val showBackButton = MutableLiveData(false)
    val username = MutableLiveData("")
    val password = MutableLiveData("")
    val email = MutableLiveData("")
    val verificationCode = MutableLiveData("")
    val showPassword = MutableLiveData(false)
    val rememberLogin = MutableLiveData(true)
    val passwordMode = MutableLiveData(true)
    val emailRequestMode = MutableLiveData(false)
    val emailVerificationMode = MutableLiveData(false)
    val loginSubtitle = MutableLiveData("")
    val primaryButtonTitle = MutableLiveData("")
    val errorMessage = MutableLiveData<String?>(null)
    val inProgress = MutableLiveData(false)
    val resendCountdown = MutableLiveData(0)
    val resendButtonTitle = MutableLiveData("")
    val primaryActionEnabled = MediatorLiveData<Boolean>()
    val accountLoggedInEvent = MutableLiveData<Event<Boolean>>()

    private val repository = Mango9LoginRepository(coreContext.context)
    private var mode = LoginMode.Password
    private var resendJob: Job? = null
    private var restoreAttempted = false

    init {
        rememberLogin.value = repository.sessions.rememberLogin
        updateMode(LoginMode.Password)
        arrayOf(username, password, email, verificationCode, inProgress).forEach {
            primaryActionEnabled.addSource(it) { updatePrimaryActionEnabled() }
        }
        coreContext.postOnCoreThread { core ->
            showBackButton.postValue(core.accountList.isNotEmpty())
        }
    }

    fun restoreSavedSessionIfNeeded() {
        if (restoreAttempted || repository.sessions.rememberLogin.not()) return
        restoreAttempted = true
        val session = repository.sessions.load() ?: return
        username.value = session.loginId
        coreContext.postOnCoreThread { core ->
            if (core.accountList.isEmpty()) {
                coreContext.postOnMainThread {
                    runAction {
                        repository.restore(session)
                        accountLoggedInEvent.postValue(Event(true))
                    }
                }
            }
        }
    }

    fun primaryAction() {
        when (mode) {
            LoginMode.Password -> signIn()
            LoginMode.EmailRequest -> requestEmailCode()
            LoginMode.EmailVerification -> verifyEmailCode()
        }
    }

    fun primaryActionFromIme(): Boolean {
        primaryAction()
        return true
    }

    fun signIn() {
        val submittedUsername = username.value.orEmpty().trim()
        val submittedPassword = password.value.orEmpty()
        if (submittedUsername.isEmpty() || submittedPassword.isEmpty() || inProgress.value == true) return
        runAction {
            repository.signIn(
                submittedUsername,
                submittedPassword,
                rememberLogin.value != false,
            )
            password.postValue("")
            accountLoggedInEvent.postValue(Event(true))
        }
    }

    fun requestEmailCode() {
        val submittedEmail = email.value.orEmpty().trim().lowercase()
        if (!isValidEmail(submittedEmail) || inProgress.value == true) return
        email.value = submittedEmail
        runAction {
            val resendAfter = repository.requestLoginCode(submittedEmail)
            verificationCode.postValue("")
            coreContext.postOnMainThread {
                updateMode(LoginMode.EmailVerification)
                startResendCountdown(resendAfter)
            }
        }
    }

    fun verifyEmailCode() {
        val submittedEmail = email.value.orEmpty().trim().lowercase()
        val submittedCode = verificationCode.value.orEmpty().trim()
        if (submittedCode.length != 6 || inProgress.value == true) return
        runAction {
            repository.verifyLoginCode(
                submittedEmail,
                submittedCode,
                rememberLogin.value != false,
            )
            verificationCode.postValue("")
            resendJob?.cancel()
            accountLoggedInEvent.postValue(Event(true))
        }
    }

    fun showEmailLogin() {
        resendJob?.cancel()
        resendCountdown.value = 0
        errorMessage.value = null
        verificationCode.value = ""
        if (email.value.isNullOrEmpty() && username.value.orEmpty().contains('@')) {
            email.value = username.value
        }
        updateMode(LoginMode.EmailRequest)
    }

    fun showPasswordLogin() {
        resendJob?.cancel()
        resendCountdown.value = 0
        verificationCode.value = ""
        errorMessage.value = null
        updateMode(LoginMode.Password)
    }

    fun changeEmail() {
        verificationCode.value = ""
        resendJob?.cancel()
        resendCountdown.value = 0
        updateMode(LoginMode.EmailRequest)
    }

    fun resendCode() {
        if (resendCountdown.value == 0) requestEmailCode()
    }

    fun toggleShowPassword() {
        showPassword.value = showPassword.value != true
    }

    private fun runAction(action: suspend () -> Unit) {
        if (inProgress.value == true) return
        inProgress.value = true
        errorMessage.value = null
        updatePrimaryButtonTitle()
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (mode == LoginMode.EmailVerification) verificationCode.postValue("")
                errorMessage.postValue(
                    (error as? Mango9ApiException)?.userMessage
                        ?: "We couldn't reach Mango9. Check your connection and try again.",
                )
            } finally {
                inProgress.postValue(false)
                coreContext.postOnMainThread {
                    updatePrimaryButtonTitle()
                    updatePrimaryActionEnabled()
                }
            }
        }
    }

    private fun updateMode(newMode: LoginMode) {
        mode = newMode
        passwordMode.value = newMode == LoginMode.Password
        emailRequestMode.value = newMode == LoginMode.EmailRequest
        emailVerificationMode.value = newMode == LoginMode.EmailVerification
        loginSubtitle.value = when (newMode) {
            LoginMode.Password -> "Sign in with your Mango9 account or use a code sent to your email."
            LoginMode.EmailRequest -> "Enter the email address on your Mango9 account."
            LoginMode.EmailVerification -> "Enter the 6-digit code sent to ${email.value.orEmpty()}."
        }
        updatePrimaryButtonTitle()
        updatePrimaryActionEnabled()
    }

    private fun updatePrimaryButtonTitle() {
        primaryButtonTitle.value = if (inProgress.value == true) {
            if (mode == LoginMode.EmailRequest) "Sending…" else "Signing in…"
        } else {
            when (mode) {
                LoginMode.Password -> "Sign in"
                LoginMode.EmailRequest -> "Send Login Code"
                LoginMode.EmailVerification -> "Verify and Sign In"
            }
        }
    }

    private fun updatePrimaryActionEnabled() {
        primaryActionEnabled.value = inProgress.value != true && when (mode) {
            LoginMode.Password -> username.value.orEmpty().trim().isNotEmpty() && password.value.orEmpty().isNotEmpty()
            LoginMode.EmailRequest -> isValidEmail(email.value.orEmpty())
            LoginMode.EmailVerification -> verificationCode.value.orEmpty().length == 6
        }
    }

    private fun startResendCountdown(seconds: Int) {
        resendJob?.cancel()
        resendCountdown.value = seconds.coerceAtLeast(0)
        updateResendButtonTitle()
        if (resendCountdown.value == 0) return
        resendJob = viewModelScope.launch {
            while ((resendCountdown.value ?: 0) > 0) {
                delay(1_000)
                resendCountdown.postValue((resendCountdown.value ?: 1) - 1)
                coreContext.postOnMainThread(::updateResendButtonTitle)
            }
        }
    }

    private fun updateResendButtonTitle() {
        val seconds = resendCountdown.value ?: 0
        resendButtonTitle.value = if (seconds > 0) "Resend in ${seconds}s" else "Resend code"
    }

    private fun isValidEmail(candidate: String): Boolean {
        val value = candidate.trim()
        val at = value.indexOf('@')
        return at > 0 && value.indexOf('.', startIndex = at + 2) > at + 1
    }

    override fun onCleared() {
        resendJob?.cancel()
        super.onCleared()
    }
}
