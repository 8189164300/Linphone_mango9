/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.telecom

/** One system audio session for a local conference, independent sessions for other calls. */
internal class TelecomCallGrouping {
    data class Member(val callKey: Long, val localConferenceKey: Long? = null, val eligible: Boolean = true)

    private val owners = mutableMapOf<Long, Long>()

    // Core queue only. Keep the representative stable across SDK call-list reordering.
    fun representatives(members: List<Member>): Set<Long> {
        // Keep terminating members associated with their conference, but never
        // enroll them as independent calls while the SDK is tearing it down.
        val eligible = members.filter { it.eligible }
        val groups = eligible.filter { it.localConferenceKey != null }.groupBy { it.localConferenceKey!! }
        owners.keys.retainAll(groups.keys)
        groups.forEach { (key, calls) ->
            if (calls.none { it.callKey == owners[key] }) owners[key] = calls.first().callKey
        }
        return eligible.filter { it.localConferenceKey == null || owners[it.localConferenceKey] == it.callKey }
            .mapTo(linkedSetOf()) { it.callKey }
    }

    fun clear() = owners.clear()
}
