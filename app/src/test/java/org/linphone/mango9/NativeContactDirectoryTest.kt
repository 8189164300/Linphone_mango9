/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test
import org.linphone.contacts.NativeContactDirectory
import org.linphone.contacts.NativeContactValue
import org.linphone.contacts.NativePhoneValue

class NativeContactDirectoryTest {
    private fun value(id: String, name: String, number: String = "+12025550142") = NativeContactValue(
        id, name, "lookup-$id", false, null, name, null, null, null,
        listOf(NativePhoneValue(number, "mobile")), emptyList(),
    )

    @Test fun searchesAllFiftyThousandValuesAndPagesBeyondSdkGuard() {
        val entries = (0 until 50_000).map {
            value(it.toString(), "Person ${it.toString().padStart(5, '0')}", "+1555${it.toString().padStart(7, '0')}")
        }
        val directory = NativeContactDirectory.build(entries)
        assertEquals("49999", directory.find("native:49999")?.id)
        assertEquals(listOf("49999"), directory.search("Person 49999", "", true, true).map { it.id })
        val all = directory.search("", "", false, true)
        assertEquals(50_000, all.size)
        val page = Mango9ContactPage<NativeContactValue>()
        assertEquals(100, page.replace(all).size)
        repeat(499) { assertEquals(100, page.reveal(page.limit - 1).size) }
        assertEquals(50_000, page.limit)
        assertTrue(page.reveal(49_999).isEmpty())
        var checks = 0
        assertTrue(directory.search("", "", false, true) { ++checks > 17 }.isEmpty())
        assertEquals(18, checks)
    }

    @Test fun searchHandlesAccentsWordsDomainEmptyAndNamesWithoutCollapsingDuplicates() {
        val a = value("1", "Élodie Jones").copy(sipAddresses = listOf("sip:elodie@example.invalid"))
        val b = value("2", "Élodie Jones", "+12025550143")
        val empty = value("3", "Unnamed").copy(phones = emptyList())
        val directory = NativeContactDirectory.build(listOf(b, empty, a))
        assertEquals(listOf("1", "2"), directory.search(" JONES\tElodie ", "", true, true).map { it.id })
        assertEquals(listOf("1"), directory.search("", "EXAMPLE.INVALID", true, true).map { it.id })
        assertEquals(listOf("1"), directory.search("", "*", true, true).map { it.id })
        assertEquals(3, directory.search("", "", false, true).size)
        assertEquals(2, directory.search("", "", true, true).size)
    }

    @Test fun exactNumbersAndNormalizedNumbersMatchButAmbiguousAndSuffixesDoNot() {
        val a = value("1", "One", "(202) 555-0142").copy(phones = listOf(NativePhoneValue("(202) 555-0142", "mobile", "+12025550142")))
        val b = value("2", "Two", "+12025550143")
        val directory = NativeContactDirectory.build(listOf(a, b))
        assertEquals(a, directory.findDestination("sip:+12025550142@example.invalid", "+12025550142"))
        assertEquals(a, directory.findDestination("", "2025550142"))
        assertNull(directory.findDestination("", "5550142"))
        val duplicate = NativeContactDirectory.build(listOf(a, b.copy(phones = a.phones)))
        assertNull(duplicate.findDestination("", "+12025550142"))
        assertNull(duplicate.findDestination("", "2025550142"))
    }

    @Test fun snapshotCopiesMutableListsAndRejectsInvalidIdentity() {
        val numbers = mutableListOf(NativePhoneValue("12345", "work"))
        val source = mutableListOf(value("1", "One").copy(phones = numbers))
        val directory = NativeContactDirectory.build(source)
        numbers.clear()
        source.clear()
        assertEquals("12345", directory.find("1")?.phones?.single()?.number)
        assertTrue(NativeContactDirectory.EMPTY.contacts.isEmpty())
        assertNull(NativeContactDirectory.EMPTY.find("1"))
        assertThrows(IllegalStateException::class.java) { NativeContactDirectory.build(listOf(value("", "Invalid"))) }
        assertThrows(IllegalStateException::class.java) {
            NativeContactDirectory.build(listOf(value("1", "One"), value("1", "Two")))
        }
    }

    @Test fun structuredNamesSortByBothPartsBeforeIdentity() {
        val amy = value("9", "Amy Jones").copy(firstName = "Amy", lastName = "Jones")
        val bob = value("1", "Bob Jones").copy(firstName = "Bob", lastName = "Jones")
        val adams = value("8", "Amy Adams").copy(firstName = "Amy", lastName = "Adams")
        val directory = NativeContactDirectory.build(listOf(bob, amy, adams))
        assertEquals(listOf("8", "9", "1"), directory.search("", "", false, true).map { it.id })
        assertEquals(listOf("8", "9", "1"), directory.search("", "", false, false).map { it.id })
    }

    @Test fun formattedPhoneQueriesAndSipDisplayNamesUseExactDestinationRules() {
        val phone = value("1", "Telephone", "+12025550142")
        val sip = value("2", "SIP user").copy(phones = emptyList(), sipAddresses = listOf("\"SIP user\" <SIPS:User@Example.INVALID:5061>"))
        val ipv6 = value("3", "IPv6 user").copy(phones = emptyList(), sipAddresses = listOf("sip:User@[2001:db8::1]:5060"))
        val directory = NativeContactDirectory.build(listOf(phone, sip, ipv6))
        assertEquals(listOf("1"), directory.search("+1 (202) 555-0142", "", true, true).map { it.id })
        assertEquals(listOf("1"), directory.search("202.555.0142", "", true, true).map { it.id })
        assertNull(directory.findDestination("sip:agent12025550142@example.invalid", "agent12025550142"))
        assertEquals(sip, directory.findDestination("sips:User@example.invalid:5061;transport=tls", null))
        assertNull(directory.findDestination("sip:user@example.invalid:5061", null))
        assertEquals(listOf("2"), directory.search("", "EXAMPLE.INVALID", true, true).map { it.id })
        assertEquals(listOf("3"), directory.search("", "[2001:db8::1]", true, true).map { it.id })
    }
}
