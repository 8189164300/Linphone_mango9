/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Immutable provider values. Building/searching this index never touches the SIP SDK. */
class NativeContactDirectory private constructor(
    val contacts: List<NativeContactValue>,
    private val byId: Map<String, NativeContactValue>,
    private val byDestination: Map<String, NativeContactValue?>,
    private val searchValues: List<Pair<NativeContactValue, String>>,
) {
    fun find(id: String): NativeContactValue? = byId[id.removePrefix(REF_PREFIX)]

    fun findDestination(uri: String, phone: String?): NativeContactValue? =
        byDestination[sipKey(uri)] ?: phone?.let { byDestination[phoneKey(it)] }

    fun search(
        filter: String,
        domain: String,
        hideEmpty: Boolean,
        sortByFirstName: Boolean,
        cancelled: () -> Boolean = { false },
    ): List<NativeContactValue> {
        val phoneFilter = phoneKey(filter).removePrefix("tel:")
        val words = if (phoneFilter.isNotEmpty()) listOf(phoneFilter) else normalize(filter).split(' ').filter(String::isNotEmpty)
        val result = ArrayList<NativeContactValue>()
        for ((contact, text) in searchValues) {
            if (cancelled()) return emptyList()
            if (hideEmpty && contact.phones.isEmpty() && contact.sipAddresses.isEmpty()) continue
            if (domain.isNotEmpty() && contact.sipAddresses.none { address ->
                    val host = sipKey(address).substringAfter('@', "").let {
                        if (it.startsWith('[')) it.substringBefore(']') + "]" else it.substringBefore(':')
                    }
                    host.isNotEmpty() && (domain == "*" || host.equals(domain, true))
                }
            ) {
                    continue
                }
            if (words.all(text::contains)) result.add(contact)
        }
        val collator = Collator.getInstance(Locale.getDefault())
        result.sortWith { a, b ->
            val compared = collator.compare(a.sortName(sortByFirstName), b.sortName(sortByFirstName))
            if (compared != 0) compared else a.id.compareTo(b.id)
        }
        return if (cancelled()) emptyList() else result
    }

    companion object {
        const val REF_PREFIX = "native:"
        private val accents = "\\p{M}+".toRegex()
        private val whitespace = "\\s+".toRegex()
        val EMPTY = build(emptyList())

        fun build(values: List<NativeContactValue>): NativeContactDirectory {
            val entries = values.map { it.copy(phones = it.phones.toList(), sipAddresses = it.sipAddresses.toList()) }
            val ids = LinkedHashMap<String, NativeContactValue>(entries.size)
            val destinations = HashMap<String, NativeContactValue?>()
            val search = ArrayList<Pair<NativeContactValue, String>>(entries.size)

            fun add(key: String, value: NativeContactValue) {
                if (key.isEmpty()) return
                // Ambiguous numbers must not pick an unrelated same-number contact.
                if (destinations.containsKey(key) && destinations[key]?.id != value.id) {
                    destinations[key] = null
                } else if (!destinations.containsKey(key)) destinations[key] = value
            }
            for (value in entries) {
                check(value.id.isNotBlank() && ids.put(value.id, value) == null) { "Invalid native contact identity" }
                value.phones.forEach { phone ->
                    add(phoneKey(phone.number), value)
                    phone.normalizedNumber?.let { add(phoneKey(it), value) }
                }
                value.sipAddresses.forEach { add(sipKey(it), value) }
                val searchable = listOfNotNull(value.displayName, value.firstName, value.lastName, value.organization) +
                    value.phones.flatMap { listOfNotNull(it.number, it.normalizedNumber, phoneKey(it.number).removePrefix("tel:")) } +
                    value.sipAddresses
                search.add(value to normalize(searchable.joinToString(" ")))
            }
            return NativeContactDirectory(entries, ids, destinations, search)
        }

        private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(accents, "").lowercase(Locale.ROOT).trim().replace(whitespace, " ")

        private fun phoneKey(value: String): String {
            val number = value.trim().let { if (it.startsWith("tel:", true)) it.substring(4) else it }
            // A SIP username such as agent42 is not the telephone number 42.
            if (number.any { !it.isDigit() && !it.isWhitespace() && it !in "+()-./" }) return ""
            val digits = number.mapNotNull { it.digitToIntOrNull() }.joinToString("")
            return if (digits.isEmpty()) "" else "tel:$digits"
        }

        private fun sipKey(value: String): String {
            val enclosed = value.trim().let { if ('<' in it && '>' in it) it.substringAfter('<').substringBefore('>') else it }
            val raw = enclosed.substringBefore(';').substringBefore('?')
            val withoutScheme = when {
                raw.startsWith("sips:", true) -> raw.substring(5)
                raw.startsWith("sip:", true) -> raw.substring(4)
                else -> raw
            }
            if (!withoutScheme.contains('@')) return ""
            return "sip:${withoutScheme.substringBefore('@')}@${withoutScheme.substringAfter('@').lowercase(Locale.ROOT)}"
        }
    }
}

fun NativeContactValue.sortName(first: Boolean): String {
    val orderedNames = if (first) listOf(firstName, lastName) else listOf(lastName, firstName)
    return orderedNames.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ").takeIf(String::isNotEmpty)
        ?: displayName.takeIf(String::isNotBlank) ?: organization?.takeIf(String::isNotBlank)
        ?: phones.firstOrNull()?.number ?: sipAddresses.firstOrNull().orEmpty()
}
