/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.telecom

/** A native call's lifetime, never its peer address or a late-arriving SIP Call-ID. */
internal class TelecomCallRegistry<T> {
    class Entry<T>(val callKey: Long, val value: T)

    private val entries = LinkedHashMap<Long, Entry<T>>()

    @Synchronized fun claim(callKey: Long, value: T): Entry<T>? {
        require(callKey != 0L)
        if (entries.containsKey(callKey)) return null
        return Entry(callKey, value).also { entries[callKey] = it }
    }

    @Synchronized operator fun get(callKey: Long): Entry<T>? = entries[callKey]

    @Synchronized fun isCurrent(entry: Entry<T>): Boolean = entries[entry.callKey] === entry

    @Synchronized fun snapshot(): List<Entry<T>> = entries.values.toList()

    @Synchronized fun release(entry: Entry<T>) {
        if (isCurrent(entry)) entries.remove(entry.callKey)
    }

    @Synchronized fun clear(): List<Entry<T>> = entries.values.toList().also { entries.clear() }
}
