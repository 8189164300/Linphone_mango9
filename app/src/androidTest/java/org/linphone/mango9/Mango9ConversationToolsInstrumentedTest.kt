/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity
import org.linphone.databinding.Mango9MessagingConversationFragmentBinding
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachment
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachmentsView
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.linphone.ui.main.crm.fragment.Mango9ConversationTools

/** Real production search controls, adapter spans, asynchronous differ and recycled attachment rows. */
@RunWith(AndroidJUnit4::class)
class Mango9ConversationToolsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun selectionMovesAllThreeChannelBubblesAndUsesTheSameFont() = fixture { f ->
        val social = Mango9MessageListItem.Social(Mango9SocialMessage.parse(org.json.JSONObject("""{"id":3,"text":"Social fixture","direction":"inbound","createdAt":"2026-10-17T12:00:00Z","deleted":false}""")), emptyList())
        val rows = listOf(team("1", "Team fixture"), sms("2", "SMS fixture"), social)
        f.submit(rows)
        ui {
            val holder = f.adapter.onCreateViewHolder(f.binding.messages, 0)
            val sizes = mutableListOf<Float>()
            for (index in rows.indices) {
                f.adapter.onBindViewHolder(holder, index)
                sizes += holder.itemView.findViewById<TextView>(R.id.message).textSize
            }
            assertEquals(1, sizes.distinct().size)
            f.adapter.select(setOf(rows[0].stableId))
            for (index in rows.indices) {
                f.adapter.onBindViewHolder(holder, index)
                assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.selected).visibility)
                assertTrue(holder.itemView.paddingLeft >= dp(48))
            }
            f.adapter.onBindViewHolder(holder, 2)
            holder.itemView.findViewById<View>(R.id.bubble).performClick()
            assertTrue(social.stableId in f.adapter.selected)
            f.adapter.select(null)
            f.adapter.onBindViewHolder(holder, 2)
            assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.selected).visibility)
        }
    }

    @Test fun smsAndTeamMatchesNavigateWithoutHidingHistoryAndRecycledHighlightsClear() = fixture { f ->
        f.submit(listOf(team("1", "Alpha first"), sms("1", "middle"), sms("2", "alpha latest ALPHA")))
        ui { f.tools.openSearch(); f.binding.searchQuery.setText("alpha") }
        await { f.binding.searchCount.text.toString() == "2 of 2" }
        ui {
            assertEquals(3, f.adapter.itemCount)
            val holder = f.adapter.onCreateViewHolder(f.binding.messages, 0)
            f.adapter.onBindViewHolder(holder, 2)
            assertEquals(2, spans(holder.itemView.findViewById(R.id.message)))
            f.adapter.onBindViewHolder(holder, 1)
            assertEquals(0, spans(holder.itemView.findViewById(R.id.message)))
            f.binding.searchPrevious.performClick()
            assertEquals("1 of 2", f.binding.searchCount.text.toString())
            f.adapter.onBindViewHolder(holder, 0)
            assertEquals(1, spans(holder.itemView.findViewById(R.id.message)))
            f.binding.searchPrevious.performClick()
            assertEquals("2 of 2", f.binding.searchCount.text.toString())
            f.binding.searchNext.performClick()
            assertEquals("1 of 2", f.binding.searchCount.text.toString())
            f.binding.searchClose.performClick()
            assertEquals(View.GONE, f.binding.searchPanel.visibility)
            f.adapter.onBindViewHolder(holder, 0)
            assertEquals(0, spans(holder.itemView.findViewById(R.id.message)))
        }
    }

    @Test fun rapidQueriesAcrossLargeHistoryPublishOnlyLatestResult() = fixture { f ->
        f.submit((0 until 10_000).map { sms("$it", "message $it") })
        ui {
            f.tools.openSearch()
            f.binding.searchQuery.setText("message")
            f.binding.searchQuery.setText("9999")
        }
        await { f.binding.searchCount.text.toString() == "1 of 1" }
        ui {
            assertFalse(f.binding.searchNext.isEnabled)
            f.binding.searchQuery.setText("no-such-message")
        }
        await { f.binding.searchCount.text.toString() == context.getString(R.string.conversation_search_no_match_found) }
        ui { assertFalse(f.binding.searchPrevious.isEnabled); assertEquals(10_000, f.adapter.itemCount) }
    }

    @Test fun snapshotReplacementDropsHiddenMatchesAndKeepsStableMessageSelection() = fixture { f ->
        f.submit(listOf(sms("1", "match"), sms("2", "match")))
        ui { f.tools.openSearch(); f.binding.searchQuery.setText("match") }
        await { f.binding.searchCount.text.toString() == "2 of 2" }
        f.submit(listOf(sms("1", "match"), sms("2", "match"), sms("3", "match")))
        await { f.binding.searchCount.text.toString() == "2 of 3" }
        f.submit(listOf(sms("1", "match"), sms("3", "match")))
        await { f.binding.searchCount.text.toString() == "2 of 2" }
        f.submit(emptyList())
        await { f.binding.searchCount.text.toString() == context.getString(R.string.conversation_search_no_match_found) }
    }

    @Test fun invalidLoginAndDestroyedViewCannotPublishPendingSearch() = fixture { f ->
        f.submit(listOf(sms("1", "secret")))
        ui {
            f.tools.openSearch()
            f.binding.searchQuery.setText("secret")
            f.current = false
        }
        Thread.sleep(350)
        ui {
            assertFalse(f.binding.searchNext.isEnabled)
            assertNotEquals("1 of 1", f.binding.searchCount.text.toString())
            f.tools.dispose()
            assertEquals(View.GONE, f.binding.searchPanel.visibility)
        }
        Thread.sleep(200)
        ui { assertEquals("", f.binding.searchCount.text.toString()) }
    }

    @Test fun largeTextSearchControlsFitNarrowConversationAndKeepComposerBelowHistory() = fixture(1.8f) { f ->
        f.submit(listOf(sms("1", "An appointment message")))
        ui { f.tools.openSearch(); f.binding.searchQuery.setText("appointment") }
        await { f.binding.searchCount.text.toString() == "1 of 1" }
        ui {
            measure(f.binding.root, 360, 760)
            assertTrue(f.binding.searchQuery.width > 100)
            assertTrue(f.binding.searchNext.width >= dp(48))
            assertTrue(f.binding.searchCount.height >= f.binding.searchCount.layout.height)
            assertTrue(f.binding.messages.height > dp(100))
            assertTrue(f.binding.composerPanel.top >= f.binding.messages.bottom)
            snapshot(f.binding.root, "mango9-conversation-search.png")
        }
    }

    @Test fun attachmentListsRecycleAndShowEveryDocumentWithoutDownloadingMedia() {
        lateinit var browser: Mango9ConversationAttachmentsView
        var opened: Mango9ConversationAttachment? = null
        val rows = Mango9ConversationAttachment.collect(
            (0 until 500).map {
            sms("$it", "", "https://example.com/report-$it.pdf")
        },
            true
        )
        ui {
            browser = Mango9ConversationAttachmentsView(ContextThemeWrapper(context, R.style.Theme_Linphone), true) { opened = it }
            browser.submit(rows)
            measure(browser, 360, 560)
        }
        await { browser.recycler.adapter?.itemCount == 500 }
        try {
            ui {
                measure(browser, 360, 560)
                assertTrue(browser.recycler.childCount in 1..20)
                assertEquals(View.GONE, browser.empty.visibility)
                browser.recycler.getChildAt(0).performClick()
                assertEquals("sms:499", opened?.messageId)
                browser.recycler.scrollToPosition(499)
            }
            instrumentation.waitForIdleSync()
            ui {
                measure(browser, 360, 560)
                val manager = browser.recycler.layoutManager as LinearLayoutManager
                assertTrue(manager.findLastVisibleItemPosition() >= 499)
                snapshot(browser, "mango9-conversation-documents.png")
                browser.submit(emptyList())
                assertEquals(View.VISIBLE, browser.empty.visibility)
            }
        } finally { ui { browser.dispose() } }
    }

    @Test fun realAttachmentDialogOpensWithUsableRowsAndRejectsStaleAccountClicks() {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        var oldDefault: Account? = null
        var fixtureAccount: Account? = null
        val sessions = Mango9SessionStore(context)
        val oldIdentity = sessions.activeIdentity
        var tools: Mango9ConversationTools? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var current = true
        var opened = 0
        try {
            core { core ->
                oldDefault = core.defaultAccount
                val params = core.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:conversation-tools@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false
                    pushNotificationAllowed = false
                }
                fixtureAccount = core.createAccount(params)
                core.addAccount(fixtureAccount)
                core.defaultAccount = fixtureAccount
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val binding = Mango9MessagingConversationFragmentBinding.inflate(activity.layoutInflater)
                    val adapter = Mango9MessageAdapter({}, {})
                    binding.messages.layoutManager = LinearLayoutManager(activity)
                    binding.messages.adapter = adapter
                    tools = Mango9ConversationTools(binding, adapter, scope, { current }, { opened++ })
                    val items = listOf(sms("1", "", "https://example.com/dialog-report.pdf"))
                    adapter.submitList(items) {
                        tools!!.submit(items)
                        tools!!.openAttachments(documents = true)
                    }
                }
                val document = node { it.text?.toString() == "dialog-report.pdf" }
                val bounds = Rect()
                document.getBoundsInScreen(bounds)
                assertTrue("Dialog row has no visible height", bounds.height() > 0)
                assertTrue(bounds.bottom < context.resources.displayMetrics.heightPixels)
                ui { current = false }
                var clickable: AccessibilityNodeInfo? = document
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                assertNotNull(clickable)
                clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                instrumentation.waitForIdleSync()
                ui { assertEquals(0, opened) }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir, "mango9-conversation-dialog.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
                ui { tools!!.dispose() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            ui { tools?.dispose(); scope.cancel() }
            core { core ->
                core.defaultAccount = oldDefault
                fixtureAccount?.let { fixture -> if (core.accountList.any { it === fixture }) core.removeAccount(fixture) }
                core.config.sync()
            }
            if (sessions.activeIdentity != oldIdentity) sessions.activate(oldIdentity)
        }
    }

    private inner class Fixture(fontScale: Float) {
        private val themed =
            ContextThemeWrapper(
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                this.fontScale = fontScale
            }
                ),
                R.style.Theme_Linphone
            )
        val binding = Mango9MessagingConversationFragmentBinding.inflate(LayoutInflater.from(themed))
        val adapter = Mango9MessageAdapter({}, {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var current = true
        val tools = Mango9ConversationTools(binding, adapter, scope, { current }, {})

        init { binding.messages.layoutManager = LinearLayoutManager(themed); binding.messages.adapter = adapter }

        fun submit(items: List<Mango9MessageListItem>) {
            ui { adapter.submitList(items) { tools.submit(items) } }
            await { adapter.currentList == items }
        }

        fun dispose() { tools.dispose(); scope.cancel(); binding.messages.adapter = null; binding.unbind() }
    }

    private fun fixture(fontScale: Float = 1f, body: (Fixture) -> Unit) {
        lateinit var fixture: Fixture
        ui { fixture = Fixture(fontScale) }
        try { body(fixture) } finally { ui { fixture.dispose() } }
    }

    private fun ui(body: () -> Unit) = instrumentation.runOnMainSync(body)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            ui { ready = condition() }
            if (ready) return
            Thread.sleep(20)
        }
        fail("Conversation UI condition timed out")
    }

    private fun spans(view: TextView): Int = (view.text as? Spanned)?.getSpans(0, view.text.length, BackgroundColorSpan::class.java)?.size ?: 0

    private fun sms(id: String, text: String, files: String = "") = Mango9MessageListItem.Sms(Mango9SmsMessage(id, "2025550100", text, "2026-09-13T10:30:00Z", "", 2, true, files))

    private fun team(id: String, text: String) = Mango9MessageListItem.Team(Mango9ChatMessage(id, 1, "fixture", text, "2026-09-13T10:30:00Z", 2, ""), false, "Teammate")

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun snapshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (predicate(node)) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            found = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (found != null) return found
            Thread.sleep(50)
        }
        error("Expected conversation dialog node was not found")
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { core ->
            try { action(core) } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core callback timed out", done.await(10, TimeUnit.SECONDS))
        failure?.let { throw it }
    }
}
