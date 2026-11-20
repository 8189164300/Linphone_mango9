// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only navigation on a signed-in physical phone. Never saves sharing changes. */
@RunWith(AndroidJUnit4::class)
class Mango9OpportunityNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private val args get() = InstrumentationRegistry.getArguments()
    private var target = ""

    private fun descendants(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (n == null) emptyList() else listOf(n) + (0 until n.childCount).flatMap { descendants(n.getChild(it)) }

    private fun nodes(): List<AccessibilityNodeInfo> {
        ui.clearCache()
        return descendants(ui.rootInActiveWindow)
    }

    private fun text(n: AccessibilityNodeInfo) = n.contentDescription?.toString().orEmpty().ifBlank {
        n.text?.toString().orEmpty()
    }

    private fun waitFor(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20000
        do {
            nodes().firstOrNull { it.isVisibleToUser && predicate(it) }?.let { return it }
            SystemClock.sleep(150)
        } while (SystemClock.uptimeMillis() < deadline)
        snapshot("missing-" + description.replace(' ', '-'))
        throw AssertionError("Missing $description")
    }

    private fun label(value: String) = waitFor(value) { text(it) == value }

    private fun actionable(n: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = n
        while (target != null) { if (target.isClickable) return true; target = target.parent }
        return false
    }

    private fun tap(n: AccessibilityNodeInfo) {
        var button: AccessibilityNodeInfo? = n
        while (button != null && !button.isClickable) button = button.parent
        assertNotNull("Clickable ancestor", button)
        assertTrue("Enabled control", button!!.isEnabled)
        assertTrue("Click accepted", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(900)
    }

    private fun snapshot(name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "crm-navigation").apply { mkdirs() }
        File(dir, "$target-$name.txt").writeText(nodes().joinToString("\n") { text(it) })
        ui.takeScreenshot()?.let { bitmap ->
            File(dir, "$target-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun openWorkspace() {
        assumeTrue("Requires explicit live read-only opt-in", args.getString("crmNavigationAudit") == "true")
        target = args.getString("crmPackage") ?: instrumentation.targetContext.packageName
        require(target in setOf("com.mango9.phone", "com.ephonix.phone.development"))
        val intent = instrumentation.context.packageManager.getLaunchIntentForPackage(target)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(
                target,
                if (target == "com.mango9.phone") "org.linphone.ui.main.MainActivity" else "com.brekeke.phone.MainActivity"
            )
        instrumentation.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(900)
        if (nodes().any { text(it) == "Search Opportunities" }) return
        tap(waitFor("CRM footer") { (text(it) == "CRM" || it.viewIdResourceName == "tab.crm") && actionable(it) })
        tap(
            waitFor("Opportunities link") {
            (text(it) == "Opportunities" || it.viewIdResourceName == "crm.open.opportunities") && actionable(it)
        }
        )
    }

    /** Draft-only regression on either installed app. Uses a dedicated QA Lead, never a customer record. */
    @Test fun opportunityModalSearchValidationAndCancelFromBothEntries() {
        openWorkspace()
        tap(label("Add Opportunity"))

        fun input(name: String) = waitFor(name) { it.className?.toString() == "android.widget.EditText" && text(it) == name }

        fun set(node: AccessibilityNodeInfo, value: String) {
            val data = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, data)); SystemClock.sleep(700)
        }
        val search = input("Search leads or clients")
        set(search, "NoSuchOpportunityPickerRecordXYZ")
        label("No matching leads or clients.")
        set(input("Search leads or clients"), "opportunity-modal-20261120")
        tap(waitFor("QA Lead search result") { actionable(it) && text(it).contains("QA Opportunity Modal") })
        input("Title (required)")
        tap(label("Save Opportunity"))
        label("Enter a title and choose a valid stage, currency and probability.")
        set(input("Title (required)"), "QA cancelled draft")
        snapshot("opportunity-modal-validation")
        tap(waitFor("Cancel") { text(it).equals("Cancel", true) })
        label("Search Opportunities")
        tap(label("Back"))
        tap(waitFor("Leads") { text(it) == "Leads" && actionable(it) })
        val leadSearch =
            waitFor("Lead search") { it.className?.toString() == "android.widget.EditText" && text(it).contains("Search", true) }
        set(leadSearch, "opportunity-modal-20261120")
        waitFor("Dedicated QA Lead") { text(it).contains("QA Opportunity Modal") }
        tap(waitFor("Lead actions") { text(it) == "Contact actions" || text(it) == "⋯" })
        tap(label("Add Opportunity"))
        val title = input("Title (required)")
        assertTrue(title.text.isNullOrBlank() || title.text.toString() == "Title (required)")
        assertFalse(nodes().any { text(it) == "Search leads or clients" })
        waitFor("Preselected QA Lead") { text(it) == "QA Opportunity Modal 20261120" }
        snapshot("opportunity-modal-preselected-lead")
        tap(waitFor("Cancel") { text(it).equals("Cancel", true) })
        waitFor("Lead list restored") { text(it) == "Contact actions" || text(it) == "⋯" }
        snapshot("opportunity-modal-cancel-restores-leads")
    }

    @Test fun ownerChoicesAndEmptyListStayNavigable() {
        openWorkspace()
        label("Search Opportunities")
        if (args.getString("filterPersistencePhase") == "verify") {
            tap(waitFor("More filters") { text(it).startsWith("More filters") || text(it).startsWith("Filters") })
            waitFor("Restored overdue after restart") { text(it).contains("Overdue") || it.text?.toString() == "Overdue" }
            snapshot("saved-filter-after-restart")
            tap(waitFor("Reset filters") { text(it).equals("Reset", true) || text(it) == "Reset filters" })
            tap(waitFor("Apply filters") { text(it).equals("Apply", true) || text(it) == "Apply filters" })
            label("Search Opportunities"); tap(label("Back"))
            return
        }
        assertFalse(
            "Currency moved out of the list toolbar",
            nodes().any {
            it.isVisibleToUser && text(it) == "All currencies"
        }
        )
        tap(waitFor("More filters") { text(it).startsWith("More filters") || text(it).startsWith("Filters") })
        tap(
            waitFor("Follow-up filter") {
            text(it) == "Filter followup" || text(it).startsWith("Follow-up,") || text(it) == "Follow-up" && actionable(it) || text(it) == "All follow-ups" && actionable(it)
        }
        )
        tap(waitFor("Overdue option") { text(it) == "Overdue" && actionable(it) })
        tap(waitFor("Apply filters") { text(it).equals("Apply", true) || text(it) == "Apply filters" })
        label("Search Opportunities")
        if (args.getString("filterPersistencePhase") == "prepare") return
        tap(label("Back")); openWorkspace()
        tap(waitFor("More filters") { text(it).startsWith("More filters") || text(it).startsWith("Filters") })
        waitFor("Restored overdue filter") { text(it).contains("Overdue") || it.text?.toString() == "Overdue" }
        snapshot("saved-filter-reopened")
        tap(waitFor("Cancel configuration") { text(it).equals("Cancel", true) || text(it) == "Close filters" })
        repeat(2) {
            tap(waitFor("More filters") { text(it).startsWith("Filters") || text(it).startsWith("More filters") })
            label("Opportunity filters")
            tap(
                waitFor("Owner filter") {
                text(it) == "Filter owner" || text(it) == "Owner" && actionable(it) || text(it).startsWith("Owner,") || text(it) == "All owners" && actionable(it)
            }
            )
            label("All owners")
            snapshot("owner-choices-$it")
            tap(waitFor("All owners option") { text(it) == "All owners" && actionable(it) })
            label("Opportunity filters")
            snapshot("configuration-$it")
            tap(waitFor("Reset filters") { text(it).equals("Reset", true) || text(it) == "Reset filters" })
            tap(waitFor("Apply filters") { text(it).equals("Apply", true) || text(it) == "Apply filters" })
            label("Search Opportunities")
        }
        tap(waitFor("More filters") { text(it).startsWith("Filters") || text(it).startsWith("More filters") })
        label("Opportunity filters")
        tap(waitFor("Cancel configuration") { text(it).equals("Cancel", true) || text(it) == "Close filters" })
        label("Search Opportunities")
        tap(label("Back"))
        waitFor("CRM workspace restored") { text(it) == "Opportunities" || it.viewIdResourceName == "crm.open.opportunities" }
    }

    @Test fun opportunitySharingAndBackNavigationRemainUsable() {
        openWorkspace()
        repeat(2) { index ->
            label("Search Opportunities")
            val first = waitFor("Opportunity card") { text(it).startsWith("Open Opportunity ") }
            val bounds = Rect(); first.getBoundsInScreen(bounds)
            assertTrue("Card is visible below the status bar", bounds.top >= 0 && bounds.height() > 0)
            snapshot("list-$index")
            tap(waitFor("Opportunity card") { text(it).startsWith("Open Opportunity ") })
            label("Expected close")
            val actions =
                waitFor("Opportunity actions") {
                    text(it) == "Opportunity actions" || it.viewIdResourceName == "crm.opportunity.actions"
                }
            tap(actions)
            tap(waitFor("Sharing action") { text(it) in setOf("Manage sharing", "Sharing") })
            label("Shared by you")
            val close = label("Close")
            val cb = Rect(); close.getBoundsInScreen(cb)
            assertTrue("Sharing close remains on screen", cb.width() > 0 && cb.height() > 0)
            snapshot("sharing-$index")
            tap(label("Close"))
            label("Opportunity actions")
            tap(label("Back"))
            label("Search Opportunities")
        }
        tap(label("Back"))
        waitFor("CRM workspace restored") { text(it) == "Opportunities" || it.viewIdResourceName == "crm.open.opportunities" }
        snapshot("returned-to-crm")
    }
}
