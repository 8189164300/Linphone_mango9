/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.notifications

import java.util.UUID

/** Core-thread confined. Neither a SIP address nor a late-arriving Call-ID identifies a live call. */
internal class CallNotificationRegistry<T>(private val createValue: (Int, String) -> T) {
    data class Entry<T>(val callKey: Long, val token: String, val notificationId: Int, val value: T)

    private val byCall = LinkedHashMap<Long, Entry<T>>()
    private var nextNotificationId = 100 // Below this are the fixed service/missed-call IDs.

    val entries: List<Entry<T>> get() = byCall.values.toList()

    fun getOrCreate(callKey: Long): Entry<T> {
        require(callKey != 0L)
        return byCall.getOrPut(callKey) {
            // Unlike Call.hashCode(), these IDs cannot collide between concurrent calls.
            while (byCall.values.any { it.notificationId == nextNotificationId }) advanceId()
            val id = nextNotificationId
            advanceId()
            val token = UUID.randomUUID().toString()
            Entry(callKey, token, id, createValue(id, token))
        }
    }

    operator fun get(callKey: Long): Entry<T>? = byCall[callKey]

    fun find(token: String?): Entry<T>? =
        token?.takeIf { it.isNotBlank() }?.let { target -> byCall.values.find { it.token == target } }

    fun remove(callKey: Long): Entry<T>? = byCall.remove(callKey)

    fun clear() = byCall.clear()

    private fun advanceId() {
        nextNotificationId = if (nextNotificationId == Int.MAX_VALUE) 100 else nextNotificationId + 1
    }
}
