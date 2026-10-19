// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.adapter.Mango9MessagingListAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItem
import org.linphone.ui.main.crm.adapter.Mango9MessagingListItems

/** Exercises the real asynchronous list differ and recycled row binding without sending messages. */
@RunWith(AndroidJUnit4::class)
class Mango9ConversationRecencyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun newestSmsGroupAndSocialRowsMoveAndKeepTheirCorrectClickDestination() {
        val oldTime = "2026-10-18 12:00:00"
        val middleTime = "2026-10-19T12:00:00Z"
        val newTime = "2026-10-19T13:00:00Z"
        val sms =
            listOf(Mango9SmsParty("2025550101", middleTime, "first", 0, ""), Mango9SmsParty("2025550102", oldTime, "old", 0, ""))

        fun smsRows(values: List<Mango9SmsParty>) = Mango9MessagingListItems.sms(Mango9ChatState(smsParties = values)) { false }
        checkMove(smsRows(sms), smsRows(Mango9ConversationRecency.smsActivity(sms, "2025550102", newTime, "new reply")), "sms:2025550102")

        val team = Mango9ChatState(
            users = listOf(Mango9ChatUser(1, "Fixture", "", "")),
            rooms = listOf(
            Mango9ChatRoom("direct", listOf(1), middleTime, "first", 0, true),
            Mango9ChatRoom("group", listOf(1, 2), oldTime, "old", 0, false)
        )
        )

        fun teamRows(value: Mango9ChatState) = Mango9MessagingListItems.team(value, { false }, { it.id })
        checkMove(teamRows(team), teamRows(team.copy(rooms = Mango9ConversationRecency.roomActivity(team.rooms, "group", newTime, "new reply"))), "group:group")

        fun thread(id: String, at: String) = Mango9SocialThread(id, 1, id, "instagram", id, "old", null, "", at, false, true, true, false, null, null, false)
        val social = listOf(thread("first", middleTime), thread("second", oldTime))
        val updated = Mango9SocialPages.inbox(social, Mango9SocialPage(listOf(social.last().copy(activityAt = newTime, summary = "new reply")), "cursor"), false)
        checkMove(social.map(Mango9MessagingListItem::Social), updated.map(Mango9MessagingListItem::Social), "social:second")
    }

    private fun checkMove(before: List<Mango9MessagingListItem>, after: List<Mango9MessagingListItem>, expected: String) {
        lateinit var adapter: Mango9MessagingListAdapter
        lateinit var parent: RecyclerView
        var clicked: String? = null
        var moves = 0
        instrumentation.runOnMainSync {
            parent = RecyclerView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone))
            parent.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(parent.context)
            adapter = Mango9MessagingListAdapter({ clicked = it.stableId })
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { moves += itemCount }
            })
            parent.adapter = adapter
        }

        fun submit(rows: List<Mango9MessagingListItem>) {
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync { adapter.submitList(rows) { done.countDown() } }
            assertTrue("List differ completed", done.await(5, TimeUnit.SECONDS))
        }
        submit(before)
        lateinit var holder: Mango9MessagingListAdapter.ViewHolder
        instrumentation.runOnMainSync {
            holder = adapter.onCreateViewHolder(parent, 0)
            adapter.onBindViewHolder(holder, 0)
        }
        submit(after)
        instrumentation.runOnMainSync {
            assertEquals(expected, adapter.currentList.first().stableId)
            assertTrue("Existing row moved instead of staying in its old position", moves > 0)
            adapter.onBindViewHolder(holder, 0)
            assertEquals("new reply", holder.itemView.findViewById<TextView>(R.id.preview).text.toString())
            holder.itemView.performClick()
            assertEquals(expected, clicked)
            parent.adapter = null
        }
    }
}
