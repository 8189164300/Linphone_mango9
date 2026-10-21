// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.graphics.Bitmap
import java.io.File
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.fragment.Mango9LiveChatFragment
import org.linphone.ui.main.crm.fragment.Mango9TemplatesFragment
import org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel
import org.linphone.ui.main.crm.viewmodel.Mango9TemplatesViewModel
import java.util.UUID

/** Real phone + server template round-trip. Creates/removes only its own named template, never sends a chat message. */
@RunWith(AndroidJUnit4::class)
class Mango9TemplatesDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private fun leaf(f: Fragment): Fragment = f.childFragmentManager.primaryNavigationFragment?.let(::leaf) ?: f
    private fun screen(block: (MainActivity, Fragment) -> Boolean): Boolean {
        var result = false
        instrument.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()?.let { main ->
                val host = main.supportFragmentManager.findFragmentById(R.id.main_nav_container) as? NavHostFragment
                if (host != null) result = block(main, leaf(host))
            }
        }
        return result
    }
    private fun waitFor(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (!check()) { if (SystemClock.elapsedRealtime() > end) fail("Template phone check timed out"); SystemClock.sleep(100) }
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun click(v: View, label: String) {
        val text = views(v).filterIsInstance<TextView>().first { it.text.toString() == label }
        var target: View = text
        while (!target.isClickable) target = target.parent as View
        assertTrue(target.isEnabled); assertTrue(target.performClick())
    }
    private fun capture(name: String) {
        var secured = false
        screen { main, _ -> secured = main.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0; main.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); true }
        try {
            SystemClock.sleep(500)
            val image = requireNotNull(instrument.uiAutomation.takeScreenshot())
            File(context.cacheDir, "templates-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        } finally { if (secured) screen { main, _ -> main.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); true } }
    }
    @Test fun savedTemplateEditAndInsertPreservesLiveChatDraft() {
        org.junit.Assume.assumeTrue("Requires an explicitly requested template phone check", InstrumentationRegistry.getArguments().getString("templateLiveCheck") == "1")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
        waitFor { screen { _, _ -> true } }
        val lease = requireNotNull(Mango9SessionStore(context).captureSession())
        val api = Mango9TemplateApi(context)
        val title = "Phone template check " + UUID.randomUUID().toString().take(8)
        val receipt = runBlocking { api.request(lease, method = "POST", body = JSONObject().put("requestId", UUID.randomUUID().toString()).put("values", JSONObject().put("title", title).put("content", "Template draft only 👋"))) }
        val id = receipt.getJSONObject("template").getLong("id")
        try {
            val liveId = runBlocking { Mango9LiveChatApi(context).request(lease, "list") }.getJSONArray("conversations").getJSONObject(0).getString("id")
            screen { main, _ ->
                val args = android.os.Bundle().apply { Mango9NavigationSession.bind(this, lease); putString("liveId", liveId) }
                (main.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment).navController.navigate(R.id.mango9LiveChatFragment, args); true
            }
            waitFor { screen { _, f -> f is Mango9LiveChatFragment && ViewModelProvider(f)[Mango9LiveChatViewModel::class.java].current().authorized } }
            screen { _, f ->
                val input = views(f.requireView()).filterIsInstance<EditText>().first(); input.setText("Existing draft\n"); input.setSelection(input.length())
                assertTrue(views(f.requireView()).any { it.contentDescription == "Add to message" })
                Mango9TemplatesFragment.open(f, lease, input); true
            }
            waitFor { screen { _, f -> f is Mango9TemplatesFragment && ViewModelProvider(f)[Mango9TemplatesViewModel::class.java].loaded } }
            screen { _, f -> click(f.requireView(), title); true }
            waitFor { screen { _, f -> f is Mango9TemplatesFragment && ViewModelProvider(f)[Mango9TemplatesViewModel::class.java].selected != null } }
            screen { _, f -> click(f.requireView(), "Edit"); true }
            screen { _, f ->
                val fields = views(f.requireView()).filterIsInstance<EditText>(); fields.last().setText("Edited template 👋")
                true
            }
            capture("editor-portrait")
            screen { main, _ -> main.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; true }
            waitFor { screen { _, f -> f is Mango9TemplatesFragment && f.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE } }
            screen { _, f ->
                val field = views(f.requireView()).filterIsInstance<EditText>().last(); field.requestFocus()
                (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field, 0); true
            }
            SystemClock.sleep(800); capture("editor-landscape")
            screen { main, _ -> main.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; true }
            waitFor { screen { _, f -> f is Mango9TemplatesFragment && f.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT } }
            screen { _, f -> click(f.requireView(), "Save"); true
            }
            waitFor { screen { _, f -> f is Mango9TemplatesFragment && ViewModelProvider(f)[Mango9TemplatesViewModel::class.java].let { it.mode == Mango9TemplatesViewModel.Mode.DETAIL && !it.busy } } }
            assertEquals("Edited template 👋", runBlocking { api.request(lease, id.toString()) }.getJSONObject("template").getString("content"))
            screen { _, f -> click(f.requireView(), "Insert"); true }
            waitFor { screen { _, f -> f is Mango9LiveChatFragment && views(f.requireView()).filterIsInstance<EditText>().firstOrNull()?.text.toString() == "Existing draft\nEdited template 👋" } }
            screen { _, f -> views(f.requireView()).filterIsInstance<EditText>().first().setText(""); true }
            assertEquals(1, Mango9TemplatesFragment.characters("👨‍👩‍👧‍👦"))
        } finally {
            val fresh = runBlocking { api.request(lease, id.toString()) }.getJSONObject("template")
            runBlocking { api.request(lease, id.toString(), "DELETE", body = JSONObject().put("requestId", UUID.randomUUID().toString()).put("revision", fresh.getString("revision"))) }
        }
        }
    }
}
