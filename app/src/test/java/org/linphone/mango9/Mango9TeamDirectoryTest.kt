package org.linphone.mango9

import org.junit.Assert.*
import org.junit.Test

class Mango9TeamDirectoryTest {
    @Test fun serverRolesKeepAgentSubtypesAndClientDistinct() {
        val labels = listOf("agent", "super_agent", "lead_buyer", "client").map {
            Mango9ChatUser(1, "Teammate", "", "agent", role = it).roleLabel
        }
        assertEquals(listOf("Agent", "Super Agent", "Buyer Agent", "Client"), labels)
    }

    @Test fun serviceAndInactiveAccountsCannotBeSelectedButHistoryKeepsTheirIdentity() {
        val system = Mango9ChatUser(2, "Notifications", "", "system")
        val legacy = Mango9ChatUser(3, "Service", "", "agent", systemAccount = true)
        val inactive = Mango9ChatUser(4, "Teammate", "", "agent", groupEligible = false)
        assertFalse(system.canJoinGroups)
        assertFalse(legacy.canJoinGroups)
        assertFalse(inactive.canJoinGroups)
        val room = Mango9ChatRoom("10", listOf(2,3,4,5), "", "", 0, false)
        assertEquals(listOf(4,5), room.humanMemberIds(listOf(system, legacy, inactive)))
        assertEquals(listOf(2,3,4,5), room.userIds)
        assertEquals("Notifications", system.name)
    }
}
