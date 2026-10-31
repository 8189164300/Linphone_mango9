// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.fragment.Mango9SupervisorFragment
import org.linphone.ui.main.crm.fragment.Mango9CalendarFragment
import org.linphone.ui.main.crm.viewmodel.Mango9CalendarViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.linphone.ui.main.crm.viewmodel.Mango9SupervisorViewModel
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on a physical phone in the isolated package. No live AI, email, task or deletion. */
@RunWith(AndroidJUnit4::class)
class Mango9SupervisorParityDeviceTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val context = instrument.targetContext
    private val chatId = "62000000-0000-4000-8000-000000000001"
    private val voiceId = "62000000-0000-4000-8000-000000000002"
    private val actionId = "62000000-0000-4000-8000-000000000003"

    private inner class Fixture {
        val session = Mango9Session("crm", "https://fixture.example.invalid", "https://fixture.example.invalid/app/api/v2", "42", "1", "client", "qa@example.invalid", "QA ONLY", "fixture-access", "fixture-refresh", "", "", 1_900_000_000, "sip:supervisor@fixture.example.invalid").copy(loginEpoch = "supervisor-parity-fixture")
        val lease = Mango9SessionLease(session, 1, 1)
        val sessions = object : Mango9SessionAccess {
            override fun captureSession() = lease

            override fun currentSession(lease: Mango9SessionLease) = session.takeIf { lease == this@Fixture.lease }

            override fun replaceTokens(lease: Mango9SessionLease, expected: Mango9Session, refreshed: Mango9Session) = false
        }
        val store = Mango9SupervisorStore(context)
        val requests = CopyOnWriteArrayList<JSONObject>()
        var failDecision = false
        var offline = false
        var deleted = false
        var decisionCount = 0
        var configurationState = "PENDING_APPROVAL"
        lateinit var model: Mango9SupervisorViewModel
        lateinit var fragment: Mango9SupervisorFragment

        val calendar = Mango9CalendarRepository(sessions, { error("No token refresh") }) { request ->
            requests += JSONObject().put("path", request.url.encodedPath)
            val value = when (request.url.pathSegments.last()) {
                "42" -> JSONObject("""{"id":42,"event_id":null,"title":"QA native task details","description":"Read-only task fixture","status":"pending","type":"manual","priority":"normal","execution":"manual","timezone":"UTC","assignees":[{"name":"Alex"}]}""")
                "settings" -> Mango9AppointmentReminderInstrumentedTest.Fixture().settings
                "metadata" -> JSONObject("""{"timezone":"UTC","statuses":[],"activities":["appointment"],"priorities":["medium"],"reminder_minutes":[15],"reminder_channels":[],"share_recipients":[],"assignees":[],"capabilities":{"create":false}}""")
                "events" -> JSONObject().put("events", JSONArray()).put("snapshot", "fixture").put("pagination", JSONObject().put("page", 1).put("limit", 100).put("total", 0).put("has_more", false))
                else -> JSONObject()
            }
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", value).toString())
        }

        fun summary(id: String, voice: Boolean) = JSONObject().put("id", id).put("title", if (voice) "QA voice summary" else "QA follow-up task")
            .put("updatedAt", "2026-10-31T17:00:00Z").put("messageCount", if (voice) 1 else 120).put("hasVoice", voice)

        fun configuration(): JSONObject {
            val recap = JSONObject().put("version", 1).put("title", "Follow-up task").put("effect", "Remind Alex to follow up tomorrow.")
                .put("fields", JSONArray().put(JSONObject().put("label", "Owner").put("value", "Alex")))
            if (configurationState == "SUCCEEDED") {
                recap.put(
                    "creation",
                    JSONObject().put("version", 1).put("statusText", "Task created")
                .put("links", JSONArray().put(JSONObject().put("label", "Open task").put("href", "/app/tasks/42")))
                .put("recipients", JSONArray().put(JSONObject().put("name", "Alex").put("email", "alex@example.invalid")))
                )
            }
            return JSONObject().put("id", actionId).put("state", configurationState).put("approvalDigest", "a".repeat(64)).put("recap", recap)
        }

        val api = Mango9SupervisorApi(sessions, { error("No refresh expected") }, store::accepted) { request ->
            if (offline) throw IOException("Fixture offline")
            val buffer = okio.Buffer(); request.body?.writeTo(buffer)
            val body = buffer.readUtf8().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
            requests += JSONObject(body.toString()).put("path", request.url.encodedPath)
            val result = when {
                request.url.encodedPath.endsWith("/identity") -> JSONObject().put("enabled", true).put("ownerOnly", true).put("supervisorAuth", "mobile_owner").put("userId", "42").put("tenantId", "42")
                request.url.encodedPath.endsWith("/supervisor") -> JSONObject().put("enabled", true)
                request.url.encodedPath.endsWith("/decision") -> {
                    decisionCount++
                    if (failDecision) throw IOException("Fixture lost response")
                    configurationState = "SUCCEEDED"; configuration()
                }
                request.url.encodedPath.endsWith("/actions/$actionId") -> configuration()
                body.optString("action") == "list" -> {
                    val items = listOfNotNull(if (deleted) null else summary(chatId, false), summary(voiceId, true))
                        .filter { Mango9SupervisorPresentation.historyMatches(it, body.optString("query"), body.optString("channel", "all")) }
                    JSONObject().put("items", JSONArray(items))
                }
                body.optString("action") == "delete" -> {
                    deleted = true; JSONObject().put("deleted", true).put("id", body.getString("conversationId"))
                }
                body.optString("action") == "read" -> {
                    val before = body.optString("before").toIntOrNull()
                    val start = if (before != null) (before - 40).coerceAtLeast(0) else if (body.optBoolean("latest")) 80 else 120
                    val end = before ?: 120
                    val rows = (start until end).map { index ->
                        JSONObject().put("id", "message-$index").put("role", "assistant")
                            .put("text", "QA ONLY message $index — " + "Readable native text. ".repeat(8))
                            .put("metadata", JSONObject().put("createdAt", "2026-10-31T17:00:00Z").put("actionIds", JSONArray(if (index == 119) listOf(actionId) else emptyList<String>())))
                    }
                    JSONObject().put("messages", JSONArray(rows)).put("cursor", "120").apply {
                        if (start in 1..119) put("olderCursor", start.toString())
                    }
                }
                else -> error("Unexpected fixture request")
            }
            Mango9CalendarResponse(200, JSONObject().put("success", true).put("data", result).toString())
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap {
        views(view.getChildAt(it))
    }
    } else {
        emptyList()
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) {
            var done = false; instrument.runOnMainSync { done = condition() }
            if (done) { instrument.waitForIdleSync(); return }
            SystemClock.sleep(50)
        }
        fail("Supervisor parity state did not settle")
    }

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { result = runCatching { action(it) }; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); result!!.getOrThrow()
    }

    private fun fixture(test: (ActivityScenario<MainActivity>, Fixture) -> Unit) {
        assumeTrue("Use isolated package to protect the user's accounts", context.packageName.endsWith(".debug"))
        val f = Fixture(); var account: Account? = null; var previous: Account? = null
        f.store.clearDisplay(f.session); f.store.consent(f.session, true); f.store.pending(f.session, null); f.store.conversation(f.session, chatId)
        try {
            core { sdk ->
                previous = sdk.defaultAccount
                account = sdk.createAccount(
                    sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress(f.session.sipIdentity!!)
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                ).also { sdk.addAccount(it); sdk.defaultAccount = it }
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                        object : FragmentManager.FragmentLifecycleCallbacks() {
                        override fun onFragmentPreCreated(fm: FragmentManager, fragment: Fragment, savedInstanceState: Bundle?) {
                            if (fragment is Mango9SupervisorFragment) {
                                f.fragment = fragment
                                f.model = ViewModelProvider(
                                    fragment,
                                    object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T = Mango9SupervisorViewModel(f.sessions, f.store, f.api) as T
                                }
                                )[Mango9SupervisorViewModel::class.java]
                                f.model.bind(f.lease)
                            } else if (fragment is Mango9CalendarFragment) {
                                ViewModelProvider(
                                    fragment,
                                    object : ViewModelProvider.Factory {
                                    @Suppress("UNCHECKED_CAST")
                                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                        Mango9CalendarViewModel(activity.application, f.calendar, MutableStateFlow(0L), MutableStateFlow(0L)) as T
                                }
                                )[Mango9CalendarViewModel::class.java]
                            }
                        }
                    },
                        true
                    )
                    (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment).navController.navigate(R.id.action_global_mango9SupervisorFragment)
                }
                await {
                    runCatching {
                        !f.model.loading && f.model.authorized && f.model.configurationDetails.containsKey(actionId) && f.fragment.view != null
                    }.getOrDefault(false)
                }
                test(scenario, f)
            }
        } finally {
            f.store.clearDisplay(f.session); f.store.consent(f.session, false); f.store.pending(f.session, null)
            core { sdk -> sdk.defaultAccount = previous; account?.let(sdk::removeAccount); sdk.config.sync() }
            Mango9SdkTestCleanup.flush()
        }
    }

    private fun tap(f: Fixture, text: String) = instrument.runOnMainSync {
        assertTrue(
            views(f.fragment.requireView()).filterIsInstance<TextView>().first {
            it.text.toString() == text
        }.performClick()
        )
    }

    private fun scroll(f: Fixture) = views(f.fragment.requireView()).filterIsInstance<ScrollView>().first()

    @Test fun latestOpeningOlderPagingAndArrowAreStable() = fixture { scenario, f ->
        await { !scroll(f).canScrollVertically(1) && scroll(f).scrollY > 0 }
        assertTrue(f.requests.any { it.optBoolean("latest") })
        instrument.runOnMainSync { scroll(f).scrollTo(0, 0) }
        await { views(f.fragment.requireView()).first { it.contentDescription == "Latest messages" }.isShown }
        tap(f, "Load earlier messages")
        await { f.model.messages.size == 80 && !f.model.loadingOlder }
        instrument.runOnMainSync { assertTrue(scroll(f).canScrollVertically(1)); scroll(f).scrollTo(0, 0) }
        tap(f, "Load earlier messages")
        await { f.model.messages.size == 120 && !f.model.moreMessages && !f.model.loadingOlder }
        instrument.runOnMainSync {
            assertTrue(scroll(f).canScrollVertically(1))
            views(f.fragment.requireView()).first { it.contentDescription == "Latest messages" }.performClick()
        }
        await {
            !scroll(f).canScrollVertically(1) && !views(f.fragment.requireView()).first {
                it.contentDescription == "Latest messages"
            }.isShown
        }
        val prior = f.model
        scenario.onActivity { activity ->
            val nav = (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment).navController
            nav.popBackStack(); nav.navigate(R.id.action_global_mango9SupervisorFragment)
        }
        await {
            f.model !== prior && !f.model.loading && f.model.messages.isNotEmpty() && !scroll(f).canScrollVertically(1) && scroll(f).scrollY > 0
        }
        instrument.runOnMainSync {
            val root = f.fragment.requireView(); val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap)); File(context.getExternalFilesDir(null), "supervisor-parity-latest.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }; bitmap.recycle()
        }
    }

    @Test fun historySearchFiltersAndSingleDeleteConfirmation() = fixture { _, f ->
        instrument.runOnMainSync {
            views(f.fragment.requireView()).first { it.contentDescription == "Supervisor options" }.performClick()
        }
        instrument.waitForIdleSync()
        waitVisible("History")
        onView(withText("History")).perform(click())
        onView(withText("With voice")).perform(click())
        await {
            !f.model.loadingConversations && f.model.conversations.size == 1 && f.model.conversations.single().optBoolean("hasVoice")
        }
        onView(withText("Text only")).perform(click())
        await {
            !f.model.loadingConversations && f.model.conversations.size == 1 && !f.model.conversations.single().optBoolean("hasVoice")
        }
        onView(androidx.test.espresso.matcher.ViewMatchers.withHint("Search conversation titles")).perform(androidx.test.espresso.action.ViewActions.replaceText("missing"))
        await { f.model.historyQuery == "missing" && !f.model.loadingConversations && f.model.conversations.isEmpty() }
        onView(androidx.test.espresso.matcher.ViewMatchers.withHint("Search conversation titles")).perform(androidx.test.espresso.action.ViewActions.replaceText(""), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        await { !f.model.loadingConversations && f.model.conversations.size == 1 }
        onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Delete conversation")).perform(click())
        onView(withText("Cancel")).perform(click()); assertFalse(f.deleted)
        onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Delete conversation")).perform(click())
        onView(withText("Delete")).perform(click())
        await { f.deleted && f.model.deletingConversation == null }
        assertEquals(1, f.requests.count { it.optString("action") == "delete" })
    }

    @Test fun cachedSettingsAreDisplayOnlyAndApplyShowsCreationProof() = fixture { _, f ->
        val cached = f.store.savedChats(f.session).single().getJSONArray("configurations").getJSONObject(0)
        assertTrue(cached.getBoolean("summaryOnly")); assertFalse(cached.has("approvalDigest"))
        assertTrue(f.store.savedChats(f.session.copy(loginEpoch = "new-login")).isEmpty())
        assertTrue(f.store.savedChats(f.session.copy(userId = "other")).isEmpty())
        instrument.runOnMainSync { f.model.background() }
        await { views(f.fragment.requireView()).filterIsInstance<TextView>().any { it.text == "Apply" && !it.isEnabled } }
        instrument.runOnMainSync { f.model.foreground() }
        await {
            actionId !in f.model.savedConfigurationIds && views(f.fragment.requireView()).filterIsInstance<TextView>().any {
                it.text == "Apply" && it.isEnabled
            }
        }
        tap(f, "Apply")
        await { f.model.configurationDetails[actionId]?.optString("state") == "SUCCEEDED" && !f.model.actionBusy }
        instrument.runOnMainSync {
            val text = views(f.fragment.requireView()).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(text.contains("✓ Task created")); assertTrue(text.contains("Open task")); assertTrue(text.contains("Alex · alex@example.invalid"))
        }
        assertEquals(1, f.decisionCount)
    }

    @Test fun lostApplyResponseIsNotAutomaticallyResent() = fixture { _, f ->
        f.failDecision = true; tap(f, "Apply")
        await { !f.model.actionBusy && f.decisionCount == 1 }
        SystemClock.sleep(1200)
        assertEquals(1, f.decisionCount)
        assertTrue(f.requests.any { it.optString("path").endsWith("/actions/$actionId") })
    }

    @Test fun createdTaskLinkLoadsNativeDetailsInItsOriginalAccount() = fixture { scenario, f ->
        tap(f, "Apply")
        await { f.model.configurationDetails[actionId]?.optString("state") == "SUCCEEDED" && !f.model.actionBusy }
        tap(f, "Open task")
        await { f.requests.any { it.optString("path").endsWith("/tasks/42") } }
        waitVisible("QA native task details")
        scenario.onActivity { activity ->
            val nav = (activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment).navController
            assertEquals(R.id.mango9CalendarFragment, nav.currentDestination?.id)
            val args = requireNotNull(nav.currentBackStackEntry?.arguments)
            assertEquals(42, args.getInt("mango9_task_id"))
            assertEquals(f.lease, Mango9NavigationSession.matching(args, f.lease))
            assertNull(Mango9NavigationSession.matching(args, f.lease.copy(selectionGeneration = 2)))
        }
    }

    private fun waitVisible(label: String) {
        val end = SystemClock.elapsedRealtime() + 5000
        var dimensions = ""
        while (SystemClock.elapsedRealtime() < end) {
            var visible = false
            onView(withText(label)).check { view, error ->
                if (error != null) throw error
                val rect = android.graphics.Rect()
                visible = view.getGlobalVisibleRect(rect) && rect.width() >= view.width * .9 && rect.height() >= view.height * .9
                dimensions = "${view.width}x${view.height} visible=$rect root=${view.rootView.width}x${view.rootView.height}"
                if (!visible && view.rootView.width > 0 && view.rootView.height > 0) {
                    val root = view.rootView; val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap)); File(context.getExternalFilesDir(null), "supervisor-popup.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }; bitmap.recycle()
                }
            }
            if (visible) return
            SystemClock.sleep(100)
        }
        fail("Popup did not become visible: $dimensions")
    }

    @Test fun sharedChatArrowClearsAtBottomAndAfterRowsAreRemoved() = fixture { scenario, _ ->
        lateinit var list: androidx.recyclerview.widget.RecyclerView
        lateinit var overlay: android.widget.FrameLayout
        var count = 80
        scenario.onActivity { activity ->
            list = androidx.recyclerview.widget.RecyclerView(activity).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
                itemAnimator = null
                adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    override fun getItemCount() = count

                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                        TextView(activity).apply {
                        layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, 150)
                    }
                    ) {}

                    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                        (holder.itemView as TextView).text = "QA message $position"
                    }
                }
            }
            overlay = org.linphone.ui.main.crm.fragment.Mango9ChatNavigation.wrap(list)
            activity.findViewById<android.widget.FrameLayout>(android.R.id.content).addView(overlay, android.widget.FrameLayout.LayoutParams(-1, 900))
        }
        try {
            await { list.canScrollVertically(1) && overlay.getChildAt(1).isShown }
            instrument.runOnMainSync { overlay.getChildAt(1).performClick() }
            await { !list.canScrollVertically(1) && !overlay.getChildAt(1).isShown }
            instrument.runOnMainSync { list.scrollToPosition(0) }
            await { overlay.getChildAt(1).isShown }
            instrument.runOnMainSync { count = 0; list.adapter!!.notifyDataSetChanged() }
            await { !overlay.getChildAt(1).isShown }
        } finally { scenario.onActivity { (overlay.parent as ViewGroup).removeView(overlay) } }
    }
}
