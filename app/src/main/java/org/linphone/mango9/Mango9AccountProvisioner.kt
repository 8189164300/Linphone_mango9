/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

import android.os.SystemClock
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.GlobalState
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log

object Mango9AccountProvisioner {
    private const val TAG = "[Mango9 Account Provisioner]"

    suspend fun install(enrollment: Mango9SipEnrollment, displayName: String?) {
        val normalizedIdentity = Mango9SessionStore.normalizedIdentity(enrollment.identity)
            ?: throw Mango9ApiException.InvalidResponse
        installOnCore(enrollment, displayName)
        val registered = withTimeoutOrNull(REGISTRATION_TIMEOUT_MS) {
            var failedSince: Long? = null
            var pushFallbackAttempted = false
            while (true) {
                val snapshot = registrationSnapshot(normalizedIdentity)
                when (snapshot?.state) {
                    RegistrationState.Ok -> return@withTimeoutOrNull true
                    RegistrationState.Failed -> {
                        if (
                            !pushFallbackAttempted &&
                            shouldRetryWithoutSipPush(snapshot.protocolCode, snapshot.phrase) &&
                            retryWithoutSipPush(normalizedIdentity)
                        ) {
                            pushFallbackAttempted = true
                            failedSince = null
                            continue
                        }
                        val now = SystemClock.elapsedRealtime()
                        val firstFailure = failedSince ?: now.also { failedSince = it }
                        if (now - firstFailure >= FAILURE_GRACE_MS) {
                            throw Mango9ApiException.RegistrationFailed
                        }
                    }
                    null -> throw Mango9ApiException.InvalidResponse
                    else -> failedSince = null
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        if (registered != true) throw Mango9ApiException.RegistrationTimedOut
    }

    fun fromPassword(
        identity: String,
        username: String,
        password: String,
        domain: String,
        realm: String,
    ): Mango9SipEnrollment {
        val digest = MessageDigest.getInstance("MD5")
            .digest("$username:$realm:$password".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Mango9SipEnrollment(identity, username, domain, realm, digest)
    }

    private suspend fun installOnCore(enrollment: Mango9SipEnrollment, displayName: String?) =
        suspendCancellableCoroutine { continuation ->
            coreContext.postOnCoreThread { core ->
                try {
                    if (core.globalState != GlobalState.On) throw Mango9ApiException.RegistrationFailed
                    val identity = Factory.instance().createAddress(enrollment.identity)
                        ?: throw Mango9ApiException.InvalidResponse
                    displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { identity.displayName = it }
                    val normalizedIdentity = Mango9SessionStore.normalizedIdentity(identity.asStringUriOnly())
                        ?: throw Mango9ApiException.InvalidResponse
                    val authInfo = Factory.instance().createAuthInfo(
                        enrollment.username,
                        null,
                        null,
                        enrollment.ha1,
                        enrollment.realm,
                        enrollment.domain,
                    )
                    val params = core.createAccountParams()
                    params.identityAddress = identity
                    if (BuildConfig.MANGO9_FCM_ENABLED) {
                        val identityDomain = identity.domain?.trim()?.lowercase()
                        if (!identityDomain.isNullOrEmpty()) {
                            val compatibleDomains = core.config
                                .getStringList("app", "push_notification_domains", emptyArray())
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                            if (identityDomain !in compatibleDomains) {
                                core.config.setStringList(
                                    "app",
                                    "push_notification_domains",
                                    (compatibleDomains + identityDomain).distinct().toTypedArray(),
                                )
                            }
                        }
                    }
                    val proxy = Factory.instance().createAddress(Mango9Configuration.SIP_PROXY_URI)
                        ?: throw Mango9ApiException.InvalidResponse
                    proxy.transport = TransportType.Tls
                    params.serverAddress = proxy
                    params.setRoutesAddresses(arrayOf(proxy.clone()))
                    params.expires = Mango9Configuration.MOBILE_REGISTRATION_EXPIRES_SECONDS
                    params.isRegisterEnabled = true
                    params.pushNotificationAllowed = BuildConfig.MANGO9_FCM_ENABLED

                    val matching = core.accountList.filter {
                        Mango9SessionStore.normalizedIdentity(it.params.identityAddress?.asStringUriOnly()) ==
                            normalizedIdentity
                    }
                    val existing = matching.firstOrNull { it == core.defaultAccount } ?: matching.firstOrNull()
                    val selected: Account
                    if (existing != null) {
                        matching.filter { it != existing }.forEach(core::removeAccount)
                        existing.findAuthInfo()?.let(core::removeAuthInfo)
                        core.addAuthInfo(authInfo)
                        existing.params = params
                        selected = existing
                    } else {
                        selected = core.createAccount(params)
                        core.addAuthInfo(authInfo)
                        core.addAccount(selected)
                    }
                    core.defaultAccount = selected
                    selected.refreshRegister()
                    core.config.setString("misc", "config-uri", null)
                    core.provisioningUri = null
                    Log.i("$TAG Installed managed account [$normalizedIdentity] through ${Mango9Configuration.SIP_PROXY_HOST}")
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    private suspend fun registrationSnapshot(identity: String): RegistrationSnapshot? =
        suspendCancellableCoroutine { continuation ->
            coreContext.postOnCoreThread { core: Core ->
                val account = core.accountList.firstOrNull {
                    Mango9SessionStore.normalizedIdentity(it.params.identityAddress?.asStringUriOnly()) == identity
                }
                val errorInfo = account?.errorInfo
                val snapshot = account?.let {
                    RegistrationSnapshot(it.state, errorInfo?.protocolCode, errorInfo?.phrase)
                }
                if (continuation.isActive) continuation.resume(snapshot)
            }
        }

    private suspend fun retryWithoutSipPush(identity: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            coreContext.postOnCoreThread { core: Core ->
                try {
                    val account = core.accountList.firstOrNull {
                        Mango9SessionStore.normalizedIdentity(it.params.identityAddress?.asStringUriOnly()) == identity
                    }
                    val errorInfo = account?.errorInfo
                    val retried = account != null && retryWithoutSipPushIfUnsupported(
                        account,
                        errorInfo?.protocolCode,
                        errorInfo?.phrase,
                    )
                    if (continuation.isActive) continuation.resume(retried)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    internal fun shouldRetryWithoutSipPush(protocolCode: Int?, phrase: String?): Boolean =
        protocolCode == PUSH_NOT_SUPPORTED_CODE &&
            phrase?.contains(PUSH_NOT_SUPPORTED_PHRASE, ignoreCase = true) == true

    internal fun retryWithoutSipPushIfUnsupported(
        account: Account,
        protocolCode: Int?,
        phrase: String?,
    ): Boolean {
        val params = account.params
        if (
            !params.pushNotificationAllowed ||
            !shouldRetryWithoutSipPush(protocolCode, phrase)
        ) {
            return false
        }

        val updatedParams = params.clone()
        updatedParams.pushNotificationAllowed = false
        account.params = updatedParams
        account.refreshRegister()
        Log.w(
            "$TAG Proxy rejected FCM SIP push with 555; " +
                "retrying registration without SIP push parameters"
        )
        return true
    }

    private data class RegistrationSnapshot(
        val state: RegistrationState,
        val protocolCode: Int?,
        val phrase: String?,
    )

    private const val REGISTRATION_TIMEOUT_MS = 15_000L
    private const val FAILURE_GRACE_MS = 1_000L
    private const val POLL_INTERVAL_MS = 250L
    private const val PUSH_NOT_SUPPORTED_CODE = 555
    private const val PUSH_NOT_SUPPORTED_PHRASE = "Push Notification Service Not Supported"
}
