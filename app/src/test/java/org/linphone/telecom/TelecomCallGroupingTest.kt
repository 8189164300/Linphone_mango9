/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.telecom

import org.junit.Assert.*
import org.junit.Test
import org.linphone.telecom.TelecomCallGrouping.Member

class TelecomCallGroupingTest {
    @Test fun ordinaryAndRemoteConferenceCallsRemainIndependent() {
        assertEquals(setOf(1L, 2L, 3L), TelecomCallGrouping().representatives(listOf(Member(1), Member(2), Member(3))))
    }

    @Test fun localConferenceHasOneRepresentativeAndDoesNotHideAnUnrelatedCall() {
        assertEquals(setOf(1L, 3L), TelecomCallGrouping().representatives(listOf(Member(1, 100), Member(2, 100), Member(3))))
    }

    @Test fun reorderingMembersCannotSwapTheSystemAudioOwner() {
        val grouping = TelecomCallGrouping()
        grouping.representatives(listOf(Member(1, 100), Member(2, 100)))
        assertEquals(setOf(1L), grouping.representatives(listOf(Member(2, 100), Member(1, 100))))
    }

    @Test fun removingTheRepresentativePromotesAnotherMemberWithoutLosingOtherGroups() {
        val grouping = TelecomCallGrouping()
        grouping.representatives(listOf(Member(1, 100), Member(2, 100), Member(3, 200)))
        assertEquals(setOf(2L, 3L), grouping.representatives(listOf(Member(2, 100), Member(3, 200))))
    }

    @Test fun splittingAConferenceRestoresEachIndependentCall() {
        val grouping = TelecomCallGrouping()
        grouping.representatives(listOf(Member(1, 100), Member(2, 100)))
        assertEquals(setOf(1L, 2L), grouping.representatives(listOf(Member(1), Member(2))))
    }

    @Test fun reusingAConferenceAddressDoesNotRetainItsPreviousOwner() {
        val grouping = TelecomCallGrouping()
        grouping.representatives(listOf(Member(1, 100), Member(2, 100)))
        grouping.representatives(emptyList())
        assertEquals(setOf(2L), grouping.representatives(listOf(Member(2, 100), Member(1, 100))))
    }

    @Test fun coreRestartClearsOwnershipEvenWhenPointersAreReused() {
        val grouping = TelecomCallGrouping()
        grouping.representatives(listOf(Member(1, 100), Member(2, 100)))
        grouping.clear()
        assertEquals(setOf(2L), grouping.representatives(listOf(Member(2, 100), Member(1, 100))))
    }

    @Test fun endingConferenceMembersNeverBecomeIndependentSystemCalls() {
        val grouping = TelecomCallGrouping()
        grouping.representatives(listOf(Member(1, 100), Member(2, 100), Member(3)))
        repeat(3) {
            assertEquals(setOf(3L), grouping.representatives(listOf(Member(1, 100, false), Member(2, 100, false), Member(3))))
        }
        // An explicit SDK detach, unlike an ending conference, restores the calls.
        assertEquals(setOf(1L, 2L, 3L), grouping.representatives(listOf(Member(1), Member(2), Member(3))))
    }

    @Test fun endingRemoteConferenceIsNotEnrolledAndFailedTerminationCanRecover() {
        val grouping = TelecomCallGrouping()
        assertEquals(setOf(2L), grouping.representatives(listOf(Member(1, eligible = false), Member(2))))
        assertEquals(setOf(1L, 2L), grouping.representatives(listOf(Member(1), Member(2))))
    }
}
