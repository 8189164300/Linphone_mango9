/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

/** Phone numbers and SIP aliases may be reused by a replacement CRM login. */
internal data class Mango9SmsHistoryKey(val identity: String, val login: Long, val phone: String) {
    companion object {
        fun from(session: Mango9SessionLease, phone: String) = Mango9SmsHistoryKey(
            requireNotNull(session.session.sipIdentity), session.loginGeneration, Mango9PhoneNumber.normalized(phone),
        )
    }
}
