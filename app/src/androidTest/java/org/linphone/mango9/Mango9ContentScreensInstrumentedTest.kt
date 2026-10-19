// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.NavOptions
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.*
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.chat.adapter.ConversationsFilesAdapter
import org.linphone.ui.main.chat.viewmodel.ConversationContentsListViewModel
import org.linphone.ui.main.chat.viewmodel.ConversationDocumentsListViewModel
import org.linphone.ui.main.chat.viewmodel.ConversationMediaListViewModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel

/** Production navigation, Fragments, RecyclerViews and lifecycle; only history content is synthetic. */
@RunWith(AndroidJUnit4::class)
class Mango9ContentScreensInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun documentScreenPagesWithoutBlockingAndReleasesOffscreenExportsAcrossRotation() = screen(false)

    @Test fun mediaScreenPagesAndRebindsVisibleThumbnailsAcrossRotation() = screen(true)

    private fun screen(media: Boolean) = fixture { root, _ ->
        val listeners = CopyOnWriteArrayList<ChatRoomListener>()
        val ranges = CopyOnWriteArrayList<Pair<Int, Int>>()
        val total = if (media) 150 else 120
        val page = if (media) 50 else 20
        val source = File(root, "source.png")
        if (media) {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.rgb(60, 100, 180))
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        val history = (0 until total).map { index ->
            fake<Content> { method, _ ->
                when (method) {
                    "getName" -> if (media) "Photo $index.png" else "Document $index.txt"
                    "getFilePath" -> if (media) source.path else File(root, "encrypted-$index").path
                    "isFileEncrypted" -> !media
                    "isFileTransfer" -> false
                    "getType" -> if (media) "image" else "text"
                    "getSubtype" -> if (media) "png" else "plain"
                    "getFileSize", "getSize" -> 64
                    "getCreationTimestamp" -> 1_800_000_000L - index
                    "getRelatedChatMessageId" -> null
                    "exportPlainFile" -> File.createTempFile("export-$index-", ".txt", root).apply {
                        writeText("Synthetic document $index")
                    }.path
                    else -> error("Unexpected content operation $method")
                }
            }
        }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val room = fake<ChatRoom> { method, args ->
            when (method) {
                "getCore" -> coreContext.core
                "getIdentifier" -> "synthetic-content-screen"
                "addListener" -> { listeners.add(args[0] as ChatRoomListener); null }
                "removeListener" -> { listeners.remove(args[0] as ChatRoomListener); null }
                "getMediaContentsSize", "getDocumentContentsSize" -> total
                "getMediaContentsRange", "getDocumentContentsRange" -> {
                    val start = args[0] as Int; val end = args[1] as Int
                    ranges.add(start to end)
                    if (start == page && release.count > 0) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
                    history.subList(start, end).toTypedArray()
                }
                "isEphemeralEnabled" -> false
                "findMessage" -> null
                else -> error("Unexpected room operation $method")
            }
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[SharedMainViewModel::class.java].displayedChatRoom = room
                    activity.findNavController().navigate(R.id.action_global_conversationsListFragment)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    childHost(activity).navController.navigate(
                        if (media) R.id.conversationMediaListFragment else R.id.conversationDocumentsListFragment,
                        Bundle().apply { putString("conversationId", "synthetic-content-screen") },
                    )
                }
                lateinit var vm: ConversationContentsListViewModel
                await {
                    var loaded = false
                    scenario.onActivity { activity ->
                        val fragment = current(activity)
                        vm = if (media) ViewModelProvider(fragment)[ConversationMediaListViewModel::class.java] else ViewModelProvider(fragment)[ConversationDocumentsListViewModel::class.java]
                        loaded = vm.contentsList.value?.size == page && vm.operationInProgress.value == false && recycler(fragment, media).childCount > 0
                    }
                    loaded
                }
                scenario.onActivity { recycler(current(it), media).scrollToPosition(page - 1) }
                assertTrue("Scrolling must request the next raw page", entered.await(15, TimeUnit.SECONDS))
                try {
                    await {
                        var loading = false
                        scenario.onActivity { activity ->
                            val view = current(activity).requireView()
                            loading = view.findViewById<View>(R.id.contents_page_progress).visibility == View.VISIBLE
                            if (loading) assertEquals("Extra pages must not block the list", View.GONE, (view.findViewById<View>(R.id.dialog_title).parent as View).visibility)
                        }
                        loading
                    }
                } finally { release.countDown() }
                await { vm.contentsList.value!!.size >= page * 2 && vm.operationInProgress.value == false }
                while (vm.contentsList.value!!.size < total) {
                    val before = vm.contentsList.value!!.size
                    await {
                        var bound = false
                        scenario.onActivity {
                            val list = recycler(current(it), media)
                            bound = list.adapter?.itemCount == before && !list.hasPendingAdapterUpdates()
                        }
                        bound
                    }
                    scenario.onActivity { recycler(current(it), media).scrollToPosition(before - 1) }
                    await { vm.contentsList.value!!.size > before && vm.operationInProgress.value == false }
                }
                await {
                    var bound = false
                    scenario.onActivity { bound = recycler(current(it), media).adapter?.itemCount == total }
                    bound
                }
                scenario.onActivity { recycler(current(it), media).scrollToPosition(total - 1) }
                await { vm.contentsList.value!!.last().metadataRequested }
                // Reaching the last row does not mean RecyclerView has finished
                // its previous layout/animations. Disappearing holders can still
                // legitimately own presentations until the UI detaches them.
                // Measure one settled UI snapshot, then require exact ownership
                // as well as the existing bound; never wait for the count to pass.
                val beforeSettling = vm.contentsList.value!!.count { it.metadataRequested }
                await {
                    var settled = false
                    scenario.onActivity { activity ->
                        val list = recycler(current(activity), media)
                        settled = !list.isComputingLayout && !list.hasPendingAdapterUpdates() &&
                            list.itemAnimator?.isRunning != true &&
                            (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() == total - 1
                    }
                    settled
                }
                scenario.onActivity { activity ->
                    val list = recycler(current(activity), media)
                    val attached = (0 until list.childCount).mapNotNull { index ->
                        when (val holder = list.getChildViewHolder(list.getChildAt(index))) {
                            is ConversationsFilesAdapter.MediaFileViewHolder -> holder.binding.model
                            is ConversationsFilesAdapter.DocumentFileViewHolder -> holder.binding.model
                            else -> null
                        }
                    }.toSet()
                    val requested = vm.contentsList.value!!.filter { it.metadataRequested }.toSet()
                    val diagnostic = "media=$media before=$beforeSettling settled=${requested.size} attached=${attached.size}"
                    android.util.Log.i("Mango9ContentFixture", diagnostic)
                    assertTrue("The final page must retain its visible row: $diagnostic", requested.contains(vm.contentsList.value!!.last()))
                    assertEquals("Only attached rows may own previews: $diagnostic", attached, requested)
                    assertTrue("Only visible rows should retain previews: $diagnostic", requested.size < 40)
                }
                if (!media) await { root.listFiles().orEmpty().count { it.name.startsWith("export-") } in 1..39 }
                scenario.moveToState(Lifecycle.State.STARTED)
                await { vm.contentsList.value!!.none { it.metadataRequested } }
                if (!media) await { root.listFiles().orEmpty().none { it.name.startsWith("export-") } }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { vm.contentsList.value!!.last().metadataRequested }
                val beforeRotation = vm.contentsList.value!!.last()
                val reads = ranges.size
                scenario.recreate()
                await {
                    var restored = false
                    scenario.onActivity { activity ->
                        val fragment = current(activity)
                        val after = if (media) ViewModelProvider(fragment)[ConversationMediaListViewModel::class.java] else ViewModelProvider(fragment)[ConversationDocumentsListViewModel::class.java]
                        assertSame("ViewModel must survive activity recreation", vm, after)
                        val list = recycler(fragment, media)
                        restored = list.adapter?.itemCount == total && (list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() == total - 1
                    }
                    restored
                }
                assertEquals("Unchanged source must not start paging over", reads, ranges.size)
                assertSame(beforeRotation, vm.contentsList.value!!.last())
                scenario.onActivity { activity ->
                    val view = current(activity).requireView()
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        File(context.getExternalFilesDir(null), if (media) "native-media-screen.png" else "native-documents-screen.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { bitmap.recycle() }
                    // This fixture entered the content destination directly, without a Conversation
                    // back-stack entry. Dispose its actual parent graph rather than popping a
                    // nonexistent conversation; production row/back handling is unchanged.
                    activity.findNavController().navigate(
                        R.id.historyListFragment, null,
                        NavOptions.Builder().setPopUpTo(R.id.conversationsListFragment, true).build(),
                    )
                }
                await { vm.contentsList.value!!.isEmpty() }
            } finally { release.countDown() }
        }
        core { assertTrue("Fragment disposal must remove its room listener", listeners.isEmpty()) }
        await { root.listFiles().orEmpty().none { it.name.startsWith("export-") } }
    }

    @Test fun actualSdkRoomCoreIdentityAndEmptyContentListsUseTheProductionLookup() = fixture { _, account ->
        var room: ChatRoom? = null
        val store = ViewModelStore()
        try {
            core { sdk ->
                val params = sdk.createConferenceParams(null).apply {
                    isChatEnabled = true; isGroupEnabled = false; this.account = account
                    securityLevel = Conference.SecurityLevel.None
                    chatParams!!.backend = ChatRoom.Backend.Basic
                }
                room = requireNotNull(sdk.createChatRoom(params, arrayOf(Factory.instance().createAddress("sip:content-${UUID.randomUUID()}@fixture.example.invalid")!!)))
                assertSame("JNI room Core must satisfy the ownership fence", sdk, room!!.core)
            }
            lateinit var documents: ConversationDocumentsListViewModel
            lateinit var media: ConversationMediaListViewModel
            var identifier = ""
            core { identifier = room!!.identifier!! }
            main {
                documents = ConversationDocumentsListViewModel(); store.put("documents", documents)
                media = ConversationMediaListViewModel(); store.put("media", media)
                documents.findChatRoom(room, identifier); media.findChatRoom(null, identifier)
            }
            await { documents.chatRoomFoundEvent.value != null && media.chatRoomFoundEvent.value != null }
            core { assertSame(room, documents.chatRoom); assertSame(room, media.chatRoom) }
            await { documents.operationInProgress.value == false && media.operationInProgress.value == false }
            assertTrue(documents.contentsList.value!!.isEmpty()); assertTrue(media.contentsList.value!!.isEmpty())
            assertFalse(documents.loadFailed.value!!); assertFalse(media.loadFailed.value!!)
        } finally { main { store.clear() }; core { sdk -> room?.let(sdk::deleteChatRoom) } }
    }

    private fun childHost(activity: MainActivity): NavHostFragment {
        val mainHost = activity.supportFragmentManager.findFragmentById(R.id.main_nav_container) as NavHostFragment
        val list = requireNotNull(mainHost.childFragmentManager.primaryNavigationFragment)
        return list.childFragmentManager.findFragmentById(R.id.chat_nav_container) as NavHostFragment
    }

    private fun current(activity: MainActivity): Fragment = requireNotNull(childHost(activity).childFragmentManager.primaryNavigationFragment)

    private fun recycler(fragment: Fragment, media: Boolean): RecyclerView = fragment.requireView().findViewById(if (media) R.id.mediaList else R.id.documentsList)

    private fun fixture(test: (File, Account) -> Unit) {
        for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA)) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val root = File.createTempFile("native-content-screen-", ".tmp", context.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val sessions = Mango9SessionStore(context); val previousIdentity = sessions.activeIdentity
        var previous: Account? = null; var account: Account? = null
        try {
            await { var ready = false; core { ready = it.globalState == GlobalState.On }; ready }
            core { sdk ->
                previous = sdk.defaultAccount
                account = sdk.createAccount(
                    sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:content-screens@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                )
                assertEquals(0, sdk.addAccount(account!!)); sdk.defaultAccount = account
            }
            test(root, account!!)
        } finally {
            core { sdk ->
                sdk.defaultAccount = previous
                account?.let { created -> if (sdk.accountList.any { it === created }) sdk.removeAccount(created) }
                sdk.config.sync()
            }
            if (sessions.activeIdentity != previousIdentity) sessions.activate(previousIdentity)
            Mango9SdkTestCleanup.flush()
            root.deleteRecursively()
        }
    }

    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
        when (method.name) { "equals" -> self === args?.get(0); "hashCode" -> System.identityHashCode(self); "toString" -> "Synthetic content screen"; else -> call(method.name, args.orEmpty()) }
    } as T

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { sdk -> result = runCatching { action(sdk) }; done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS)); result!!.getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) { instrumentation.waitForIdleSync(); return }; SystemClock.sleep(25)
        }
        fail("Native content screen did not settle")
    }
}
