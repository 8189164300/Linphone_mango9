/*
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of the Mango9 Android client and is distributed under
 * the GNU General Public License version 3 or later.
 */
package org.linphone.mango9

/** Converts raw SIP registration failures into the same actionable categories used by iOS. */
data class Mango9RegistrationFailure(val kind: Kind, val sipCode: Int?) {
    enum class Kind {
        Credentials,
        PushConfiguration,
        AccountNotFound,
        SecureConnection,
        Network,
        RateLimited,
        ServiceUnavailable,
        Unknown,
    }

    constructor(sipMessage: String) : this(classify(sipMessage), extractSipCode(sipMessage))

    fun userMessage(line: String?): String {
        val extension = line?.trim().orEmpty()
        val subject = if (extension.isEmpty()) "This line" else "Extension $extension"
        val diagnostic = sipCode?.let { " (SIP $it)" }.orEmpty()
        return when (kind) {
            Kind.Credentials ->
                "$subject could not authenticate. Reconnect the account or verify its SIP credentials.$diagnostic"
            Kind.PushConfiguration ->
                "$subject cannot register because call notifications are not supported by the voice server. " +
                    "Update the app or contact Mango9 support.$diagnostic"
            Kind.AccountNotFound ->
                "$subject is not provisioned on this voice server. Contact your Mango9 administrator.$diagnostic"
            Kind.SecureConnection ->
                "$subject could not establish a secure voice connection. Check the network and try again.$diagnostic"
            Kind.Network ->
                "$subject cannot reach the Mango9 voice service. Check the internet connection; " +
                    "the app will keep retrying.$diagnostic"
            Kind.RateLimited ->
                "$subject made too many connection attempts. Mango9 will retry automatically in a moment.$diagnostic"
            Kind.ServiceUnavailable ->
                "The Mango9 voice service could not register ${subject.lowercase()}. " +
                    "It will retry automatically.$diagnostic"
            Kind.Unknown ->
                "$subject could not connect to the Mango9 voice service. The app will keep retrying.$diagnostic"
        }
    }

    companion object {
        private val sipCodePattern = Regex("(?<!\\d)[4-6]\\d{2}(?!\\d)")

        private fun classify(message: String): Kind {
            val normalized = message.lowercase()
            val code = extractSipCode(message)
            return when {
                code == 555 ||
                    normalized.contains("push notification") ||
                    normalized.contains("push service") -> Kind.PushConfiguration
                code == 401 || code == 403 ||
                    normalized.contains("unauthorized") ||
                    normalized.contains("forbidden") ||
                    normalized.contains("authentication") ||
                    normalized.contains("credential") ||
                    normalized.contains("password") -> Kind.Credentials
                code == 404 || normalized.contains("not found") -> Kind.AccountNotFound
                normalized.contains("tls") ||
                    normalized.contains("ssl") ||
                    normalized.contains("certificate") -> Kind.SecureConnection
                code == 408 ||
                    normalized.contains("timeout") ||
                    normalized.contains("timed out") ||
                    normalized.contains("io error") ||
                    normalized.contains("network") ||
                    normalized.contains("unreachable") ||
                    normalized.contains("dns") ||
                    normalized.contains("no route") -> Kind.Network
                code == 429 -> Kind.RateLimited
                code in 500..599 ||
                    normalized.contains("service unavailable") ||
                    normalized.contains("server error") -> Kind.ServiceUnavailable
                else -> Kind.Unknown
            }
        }

        private fun extractSipCode(message: String): Int? =
            sipCodePattern.find(message)?.value?.toIntOrNull()
    }
}
