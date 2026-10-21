// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.fragment.Mango9LiveChatFragment
import org.linphone.ui.main.crm.viewmodel.Mango9LiveChatViewModel

/** Explicit opt-in, disposable website only. No real visitors, personal media, microphone, or camera. */
@RunWith(AndroidJUnit4::class)
class Mango9LiveChatEndToEndTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private fun all(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
    private fun leaf(fragment: Fragment): Fragment = fragment.childFragmentManager.primaryNavigationFragment?.let(::leaf) ?: fragment
    private fun waitFor(check: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!check()) { if (SystemClock.elapsedRealtime() - start > 30000) fail("Live Chat did not reach the expected state"); SystemClock.sleep(100) }
    }
    @Test fun isolatedWebsiteReceivesNativeImagePdfVoiceAndMessageActions() {
        val id = InstrumentationRegistry.getArguments().getString("liveFixtureId")
        val siteId = InstrumentationRegistry.getArguments().getString("liveFixtureSiteId")
        assumeTrue("Requires an explicitly supplied disposable website and conversation", id != null && siteId != null)
        UUID.fromString(id)
        UUID.fromString(siteId)
        val owner = requireNotNull(Mango9SessionStore(context).captureSession())
        val api = Mango9LiveChatApi(context)
        val initial = runBlocking { api.request(owner, "messages", mapOf("id" to id!!)) }
        val conversation = initial.getJSONObject("conversation")
        assertEquals("Mango9 Android verification", conversation.getString("name"))
        val site = conversation.getJSONObject("site")
        assertEquals("Android parity verification", site.getString("name"))
        assertEquals(siteId, site.getString("id"))
        val root = File(context.cacheDir, "live-e2e-${UUID.randomUUID()}").apply { mkdirs() }
        val image = File(root, "Android-fixture.png")
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff4256ce.toInt()) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val pdf = File(root, "Android-fixture.pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(200, 100, 1).create())
            page.canvas.drawText("Internal Android fixture", 10f, 40f, android.graphics.Paint()); document.finishPage(page)
            pdf.outputStream().use(document::writeTo)
        } finally { document.close() }
        val voice = File(root, "Android-fixture.m4a"); Mango9MediaTestFixtures.createAudio(voice)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                var model: Mango9LiveChatViewModel? = null
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first().navController.navigate(R.id.action_global_mango9LiveChatFragment, Bundle().apply { putString("liveId", id); Mango9NavigationSession.bind(this, owner) })
                }
                waitFor {
                    scenario.onActivity { activity ->
                        val f = leaf(activity.supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().first())
                        if (f is Mango9LiveChatFragment) model = ViewModelProvider(f)[Mango9LiveChatViewModel::class.java]
                    }; model?.current()?.authorized == true
                }
                val vm = requireNotNull(model)
                assertTrue(vm.current().messages.first().selectedText.contains("198.51.100.10"))
                for ((file, mime) in listOf(image to "image/png", pdf to "application/pdf", voice to "audio/mp4")) {
                    scenario.onActivity { vm.importAttachment(Uri.fromFile(file), file.name, mime) }
                    waitFor { !vm.importing }
                    assertNotNull(vm.current().error, vm.attachment)
                    val text = "Internal Android ${file.extension} verification ${UUID.randomUUID()}"
                    scenario.onActivity { activity ->
                        val views = all(activity.findViewById(android.R.id.content)).toList()
                        views.filterIsInstance<EditText>().single { it.hint == "Message" }.setText(text)
                        val send = views.single { it.contentDescription == "Send message" }; assertTrue(send.isEnabled); send.performClick()
                    }
                    waitFor { !vm.current().sending }
                    assertNull(vm.current().error); assertFalse(vm.current().pending)
                    assertTrue(vm.current().messages.any { it.text == text && it.files.any { f -> f.liveText("mime") == mime } })
                    scenario.onActivity { activity ->
                        val editor = all(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().single { it.hint == "Message" }
                        assertEquals("", editor.text.toString()); assertFalse(editor.hasFocus())
                    }
                }
                val latest = vm.current().messages.last()
                scenario.onActivity { vm.react(latest, "👍") }
                waitFor { !vm.current().reacting }
                assertNull(vm.current().error); assertTrue(vm.current().messages.last().reactions.items.any { it.mine && it.emoji == "👍" })
                val agent = vm.current().agents.firstOrNull()
                assertNotNull("Disposable site must have an assignable owner", agent)
                scenario.onActivity { vm.action("assign", JSONObject().put("actorId", agent!!.getInt("id"))) }
                waitFor { !vm.current().sending }
                assertNull(vm.current().error); assertEquals(agent!!.getInt("id"), vm.current().conversation!!.getInt("assignee_id"))
                for ((name, orientation) in listOf("portrait" to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, "landscape" to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                    scenario.onActivity { it.requestedOrientation = orientation }; SystemClock.sleep(1000)
                    var secure = false
                    scenario.onActivity { activity ->
                        val editor = all(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().single { it.hint == "Message" }
                        val rect = android.graphics.Rect(); assertTrue(editor.getGlobalVisibleRect(rect)); assertEquals(editor.height, rect.height())
                        secure = activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
                        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    try {
                        SystemClock.sleep(150)
                        val output = File(context.getExternalFilesDir(null), "live-chat-verification").apply { mkdirs() }
                        instrument.uiAutomation.takeScreenshot()?.let { shot -> File(output, "e2e-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle() }
                    } finally { if (secure) scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } }
                }
                scenario.onActivity { vm.action("visibility", JSONObject().put("messageIds", JSONArray(listOf(latest.id))).put("hidden", true)) }
                waitFor { !vm.current().sending }
                assertNull(vm.current().error)
                val inbox = runBlocking { api.request(owner, "list", mapOf("siteId" to site.getString("id"))) }
                assertFalse(inbox.getJSONArray("conversations").getJSONObject(0).getLong("last_message_id") == latest.id)
                runBlocking { api.request(owner, "visibility", body = JSONObject().put("id", id).put("messageIds", JSONArray(listOf(latest.id))).put("hidden", false)) }
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            }
        } finally { root.deleteRecursively() }
    }
}
