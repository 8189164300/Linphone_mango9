// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.EditText
import android.view.ViewGroup
import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.crm.fragment.Mango9EmojiInput
import org.linphone.ui.main.crm.fragment.Mango9ReactionStrip
import org.linphone.ui.main.crm.fragment.Mango9MessageActionPopup
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.json.JSONObject

/** Synthetic views only: no account changes, live reaction writes, or preference resets. */
@RunWith(AndroidJUnit4::class)
class Mango9ReactionParityInstrumentedTest {
    @Test fun publicProfileLinkDoesNotLeakIntoSelectionOrRecycledMessages() {
        val opened = mutableListOf<String>()
        val item = Mango9MessageListItem.Social(Mango9SocialMessage.parse(JSONObject("""{"id":8,"text":"Profile fixture","senderName":"QA profile","direction":"inbound"}""")), emptyList())
        val adapter = Mango9MessageAdapter({}, {}).apply {
            socialProfile = { "https://www.instagram.com/test.user/" }
            openProfile = { opened += it }
        }
        instrumentation.runOnMainSync { adapter.submitList(listOf(item)) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.onBindViewHolder(holder, 0)
            val sender = holder.itemView.findViewById<TextView>(R.id.sender)
            assertTrue(sender.isClickable); sender.performClick()
            assertEquals(listOf("https://www.instagram.com/test.user/"), opened)
            adapter.select(emptySet()); adapter.onBindViewHolder(holder, 0)
            assertFalse(sender.isClickable); sender.performClick(); assertEquals(1, opened.size)
            adapter.select(null); adapter.socialProfile = { null }; adapter.onBindViewHolder(holder, 0)
            assertFalse(sender.isClickable); assertNull(sender.contentDescription)
            sender.performClick(); assertEquals(1, opened.size)
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Linphone)

    @Test fun keyboardInputAcceptsWholeEmojiAndRejectsTextOrMultipleReactions() {
        for (emoji in listOf("👍", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇦🇲", "🇺🇸", "1️⃣", "‼️", "❓"))
            assertTrue(emoji, Mango9EmojiInput.isSingleEmoji(emoji))
        for (invalid in listOf("", "hello", "1", "👍👍", " 👍", "❤️ text", "\uD83D", "a️"))
            assertFalse(invalid, Mango9EmojiInput.isSingleEmoji(invalid))
    }

    @Test fun horizontalStripKeepsMoreVisibleAndMarksRemovalAtNarrowWidth() {
        instrumentation.runOnMainSync {
            val strip = Mango9ReactionStrip(context)
            var chosen: String? = null; var more = 0
            strip.bind(Mango9ReactionChoices.visible(true, true, listOf("👍")), setOf("👍"), true, true, {
                chosen = it
            }, { more++ })
            val density = context.resources.displayMetrics.density
            strip.measure(View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
            assertEquals(View.VISIBLE, strip.more.visibility)
            assertTrue(strip.more.right <= strip.width)
            assertTrue(strip.more.width >= (48 * density).toInt())
            val thumbs = (0 until strip.choices.childCount).map {
                strip.choices.getChildAt(it)
            }.first { (it as TextView).text.toString() == "👍" }
            assertTrue(thumbs.isSelected)
            assertEquals("Remove 👍 reaction", thumbs.contentDescription)
            thumbs.performClick(); assertEquals("👍", chosen)
            strip.more.performClick(); assertEquals(1, more)
        }
    }

    @Test fun removalOnlyAndUnsupportedCapabilitiesDoNotOfferAdds() {
        instrumentation.runOnMainSync {
            val strip = Mango9ReactionStrip(context)
            strip.bind(Mango9ReactionChoices.visible(false, true, listOf("👍🏽")), setOf("👍🏽"), true, false, {}, {})
            assertEquals(1, strip.choices.childCount)
            assertTrue(strip.choices.getChildAt(0).isEnabled)
            assertEquals(View.GONE, strip.more.visibility)
            strip.bind(Mango9ReactionChoices.visible(false, false, listOf("👍🏽")), emptySet(), false, false, {}, {})
            assertEquals(0, strip.choices.childCount)
            assertEquals(View.GONE, strip.more.visibility)
        }
    }

    @Test fun reactionBadgeRequiresHoldAndSelectionDoesNotOpenAMenu() {
        val adapter = Mango9MessageAdapter({}, { holds++ })
        val item = Mango9MessageListItem.Social(Mango9SocialMessage.parse(JSONObject("""{"id":7,"text":"Fixture","direction":"inbound","reactions":{"self":{"emoji":"👍","action":"added","fromMe":true}}}""")), emptyList())
        instrumentation.runOnMainSync { adapter.submitList(listOf(item)) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.onBindViewHolder(holder, 0)
            assertTrue("First bind must not disable holding the text", holder.itemView.findViewById<View>(R.id.message).isLongClickable)
            val badge = holder.itemView.findViewById<View>(R.id.reactions)
            badge.performClick(); assertEquals(0, holds)
            badge.performLongClick(); assertEquals(1, holds)
            adapter.select(emptySet()); adapter.onBindViewHolder(holder, 0)
            badge.performClick(); assertEquals(setOf(item.stableId), adapter.selected)
            badge.performLongClick(); assertTrue(adapter.selected.isEmpty()); assertEquals(1, holds)
        }
    }

    @Test fun longBubblePopupStaysInsideVisibleWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var popup: Mango9MessageActionPopup
            lateinit var overlay: ScrollView
            lateinit var anchor: TextView
            scenario.onActivity { activity ->
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                overlay = ScrollView(activity)
                anchor = TextView(activity).apply { text = "Synthetic long message" }
                overlay.addView(anchor, FrameLayout.LayoutParams(600, 7000))
                content.addView(overlay, FrameLayout.LayoutParams(-1, 700))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                overlay.scrollTo(0, 2200)
                popup = Mango9MessageActionPopup(anchor)
                repeat(30) { popup.action("Synthetic action $it") {} }
                popup.position()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                try {
                    assertTrue(popup.isShowing)
                    val frame = Rect(); anchor.getWindowVisibleDisplayFrame(frame)
                    val root = popup.panel.rootView
                    val xy = IntArray(2); root.getLocationOnScreen(xy)
                    assertTrue("popup top", xy[1] >= frame.top)
                    assertTrue("popup bottom", xy[1] + root.height <= frame.bottom)
                    assertTrue("popup left", xy[0] >= frame.left)
                    assertTrue("popup right", xy[0] + root.width <= frame.right)
                } finally {
                    popup.dismiss()
                    activity.findViewById<FrameLayout>(android.R.id.content).removeView(overlay)
                }
            }
        }
    }

    @Test fun firstBindAndRecycledTextKeepHoldEnabledAcrossAllChannels() {
        val rows = listOf(
            Mango9MessageListItem.Team(Mango9ChatMessage("team-fixture", 1, "room", "Team fixture", "", 2, ""), false, "Teammate"),
            Mango9MessageListItem.Sms(Mango9SmsMessage("sms-fixture", "2025550100", "SMS fixture", "", "", 2, true, "")),
            Mango9MessageListItem.Social(Mango9SocialMessage.parse(JSONObject("""{"id":8,"text":"Social fixture","direction":"inbound"}""")), emptyList())
        )
        val held = mutableListOf<String>()
        val adapter = Mango9MessageAdapter({}, { held += it.stableId })
        instrumentation.runOnMainSync { adapter.submitList(rows) }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            for (index in rows.indices) {
                val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
                repeat(2) {
                    adapter.onBindViewHolder(holder, index)
                    val text = holder.itemView.findViewById<TextView>(R.id.message)
                    assertFalse(text.isTextSelectable)
                    assertTrue(rows[index].stableId, text.isLongClickable)
                    text.performLongClick()
                    assertEquals(rows[index].stableId, held.last())
                }
            }
            assertEquals(6, held.size)
        }
    }

    @Test fun emojiDialogFocusesKeyboardInputAndOnlyConfirmsOneEmoji() {
        fun input(view: View): EditText? = (view as? EditText) ?: (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { input(group.getChildAt(it)) }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chosen: String? = null
            lateinit var dialog: androidx.appcompat.app.AlertDialog
            scenario.onActivity { activity ->
                dialog = Mango9EmojiInput.dialog(activity, {
                true
            }, { chosen = it }).also { it.show() }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                try {
                    val field = requireNotNull(input(dialog.window!!.decorView))
                    assertTrue(field.hasFocus())
                    val confirm = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    field.setText("not an emoji"); assertFalse(confirm.isEnabled); assertNull(chosen)
                    field.setText("👍🏽❤️"); assertFalse(confirm.isEnabled); assertNull(chosen)
                    field.setText("👨‍👩‍👧‍👦"); assertTrue(confirm.isEnabled)
                    confirm.performClick(); assertEquals("👨‍👩‍👧‍👦", chosen)
                } finally { dialog.dismiss() }
            }
        }
    }

    private var holds = 0
}
