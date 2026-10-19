// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.ChatRoom
import org.linphone.core.ChatRoomListener
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.GlobalState
import org.linphone.ui.main.chat.adapter.ConversationsFilesAdapter
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.ui.main.chat.viewmodel.ConversationDocumentsListViewModel

/** Real ViewModels, Core queue, SDK Content and native decoders; controlled room range/exports. */
@RunWith(AndroidJUnit4::class)
class Mango9ContentListsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun documentsAdvanceSourceCursorAcrossSkippedContentsWithoutDuplicateRows() = fixture { root, store ->
        val ranges = CopyOnWriteArrayList<Pair<Int, Int>>()
        lateinit var vm: ConversationDocumentsListViewModel
        val contents = arrayListOf<Content>()
        core {
            repeat(137) { index ->
                contents.add(
                    Factory.instance().createContent().apply {
                type = "application"; subtype = "pdf"; filePath = File(root, "$index.pdf").path
                name = if (index % 3 == 0) "$index.pdf" else ""
            }
                )
            }
        }
        val room = room(contents, ranges)
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core { vm.chatRoom = room; vm.afterNotifyingChatRoomFound(false) }
        await { vm.documentsList.value?.size == 20 && vm.operationInProgress.value == false }
        assertTrue(vm.documentsList.value!!.none { it.metadataRequested })
        main { vm.loadMoreData(); vm.loadMoreData() }
        await { vm.documentsList.value?.size == 40 && vm.operationInProgress.value == false }
        main { vm.loadMoreData() }
        await { vm.documentsList.value?.size == 46 && vm.operationInProgress.value == false }
        assertEquals((0 until 137 step 3).map { "$it.pdf" }, vm.documentsList.value!!.map { it.fileName })
        assertEquals((0 until 137 step 20).toList(), ranges.map { it.first })
        val original = vm.documentsList.value!!.first()
        core { vm.afterNotifyingChatRoomFound(true) }
        main { assertSame(original, vm.documentsList.value!!.first()); store.clear(); original.onClick() }
        assertNull(vm.openDocumentEvent.value)
    }

    @Test fun namelessEncryptedContentNeverExportsAndAnEmptyRenderedPageSettles() = fixture { root, store ->
        var exports = 0
        val ranges = CopyOnWriteArrayList<Pair<Int, Int>>()
        val content = content(File(root, "encrypted"), "", true) { exports++; error("Must not export unnamed content") }
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core { vm.chatRoom = room(List(70) { content }, ranges); vm.afterNotifyingChatRoomFound(false) }
        await { ranges.lastOrNull()?.second == 70 && vm.operationInProgress.value == false }
        assertEquals(listOf(0, 20, 40, 60), ranges.map { it.first })
        assertEquals(0, exports); assertTrue(vm.documentsList.value!!.isEmpty()); assertEquals(false, vm.loadFailed.value)
    }

    @Test fun sourceGrowthRestartsTheCursorAndRoomEventsRefreshSameSizeEdits() = fixture { root, store ->
        val ranges = CopyOnWriteArrayList<Pair<Int, Int>>()
        val listeners = CopyOnWriteArrayList<ChatRoomListener>()
        val contents = (0..44).map { index ->
            content(File(root, "$index.pdf"), "$index.pdf", false) { error("Plain file does not export") }
        }.toMutableList()
        val room = room(contents, ranges, listeners)
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core { vm.chatRoom = room; vm.afterNotifyingChatRoomFound(false) }
        await { vm.documentsList.value?.size == 20 && vm.operationInProgress.value == false }
        core { contents.add(0, content(File(root, "new.pdf"), "new.pdf", false) { error("No export") }) }
        main { vm.loadMoreData() }
        await { vm.documentsList.value?.firstOrNull()?.fileName == "new.pdf" && vm.operationInProgress.value == false }
        assertEquals(listOf("new.pdf") + (0..18).map { "$it.pdf" }, vm.documentsList.value!!.map { it.fileName })
        main { vm.loadMoreData() }
        await { vm.documentsList.value?.size == 40 && vm.operationInProgress.value == false }
        assertEquals(40, vm.documentsList.value!!.map { it.originalPath }.distinct().size)
        core {
            contents[0] = content(File(root, "edited.pdf"), "edited.pdf", false) { error("No export") }
            assertEquals(1, listeners.size)
            repeat(5) { listeners.single().onChatMessagesReceived(room, emptyArray()) }
        }
        await { vm.documentsList.value?.firstOrNull()?.fileName == "edited.pdf" && vm.operationInProgress.value == false }
        assertEquals(20, vm.documentsList.value!!.size)
        assertEquals(3, ranges.count { it.first == 0 }) // initial, growth, one coalesced edit refresh
        main { store.clear() }; core { assertTrue(listeners.isEmpty()) }
    }

    @Test fun disposeDuringExportDeletesTheLatePlaintextAndNeverPublishesIt() = fixture { root, store ->
        val plain = File(root, "plain.pdf").apply { writeText("synthetic") }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val content = content(File(root, "encrypted"), "document.pdf", true) {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); plain.path
        }
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core { vm.chatRoom = room(listOf(content)); vm.afterNotifyingChatRoomFound(false) }
        await { vm.documentsList.value?.size == 1 }
        assertEquals("Paging must not export", 1L, entered.count)
        var presentation: Closeable? = null
        main { presentation = vm.documentsList.value!!.single().retainPresentation() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            main { store.clear() }
        } finally { release.countDown(); presentation?.close() }
        await { !plain.exists() }
        assertTrue(vm.documentsList.value!!.isEmpty())
    }

    @Test fun reloadReleasesReplacedExportsButKeepsAViewersRetainedLease() = fixture { root, store ->
        val old = (0..2).map { File(root, "old-$it.pdf").apply { writeText("synthetic") } }
        val next = File(root, "next.pdf").apply { writeText("synthetic") }
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core {
            vm.chatRoom = room(old.map { file -> content(File(root, "encrypted-${file.name}"), file.name, true) { file.path } })
            vm.afterNotifyingChatRoomFound(false)
        }
        await { vm.documentsList.value?.size == 3 }
        val presentations = arrayListOf<Closeable>()
        main { vm.documentsList.value!!.forEach { presentations.add(it.retainPresentation()) } }
        await { vm.documentsList.value!!.all { it.path in old.map(File::getPath) && it.preparing.value == false } }
        var viewer: Mango9NativeFileLease? = null
        main { viewer = vm.documentsList.value!!.first().retainFile() }
        try {
            core {
                vm.chatRoom = room(
                    listOf(
                        content(File(root, "encrypted-new"), "new.pdf", true) {
                    next.path
                }
                    )
                ); vm.afterNotifyingChatRoomFound(false)
            }
            await { vm.documentsList.value?.singleOrNull()?.fileName == "new.pdf" && !old[1].exists() && !old[2].exists() }
            main { presentations.add(vm.documentsList.value!!.single().retainPresentation()) }
            await {
                vm.documentsList.value!!.single().path == next.path && vm.documentsList.value!!.single().preparing.value == false
            }
            assertTrue(old[0].exists())
        } finally { viewer?.close(); presentations.forEach { it.close() } }
        await { !old[0].exists() }
        main { store.clear() }
        await { !next.exists() }
    }

    @Test fun boundRowStartsBoundedPdfPreviewAndIndependentFilesAreReleased() = fixture { root, _ ->
        val source = File(root, "large.pdf")
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(10000, 8000, 1).create())
            page.canvas.drawRect(0f, 0f, 5000f, 4000f, android.graphics.Paint().apply { color = android.graphics.Color.BLUE })
            pdf.finishPage(page)
            source.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        lateinit var first: FileModel; lateinit var second: FileModel
        lateinit var adapter: ConversationsFilesAdapter
        lateinit var holder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        main {
            first = FileModel(source.path, "same.pdf", source.length(), 0, false, source.path, false, loadMetadata = false)
            second = FileModel(source.path, "same.pdf", source.length(), 1, false, source.path, false, loadMetadata = false)
            adapter = ConversationsFilesAdapter(); adapter.submitList(listOf(first, second))
        }
        try {
            assertFalse(first.metadataRequested); assertFalse(second.metadataRequested)
            main {
                val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Linphone))
                holder = adapter.onCreateViewHolder(parent, ConversationsFilesAdapter.DOCUMENT_FILE)
                adapter.onBindViewHolder(holder, 0)
                assertFalse("A prefetched row must not decode until attached", first.metadataRequested)
                adapter.onViewAttachedToWindow(holder)
            }
            await { first.mediaPreviewAvailable.value == true }
            assertFalse(second.metadataRequested)
            val firstPreview = File(first.mediaPreview.value!!)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(firstPreview.path, options)
            assertTrue(options.outWidth in 1..512); assertTrue(options.outHeight in 1..512)
            val decoded = requireNotNull(BitmapFactory.decodeFile(firstPreview.path))
            try {
                val paper = decoded.getPixel(decoded.width - 2, decoded.height - 2)
                assertTrue("Unpainted PDF paper must stay white after JPEG encoding", android.graphics.Color.red(paper) > 245)
            } finally { decoded.recycle() }
            main { second.requestMetadata() }
            await { second.mediaPreviewAvailable.value == true }
            val secondPreview = File(second.mediaPreview.value!!)
            assertNotEquals(firstPreview.path, secondPreview.path)
            main { first.destroy() }; await { !firstPreview.exists() }
            assertTrue(secondPreview.exists()); assertTrue(source.exists())
            main { second.destroy() }; await { !secondPreview.exists() }
        } finally {
            main {
                adapter.onViewDetachedFromWindow(holder); adapter.onViewRecycled(holder); first.destroy(); second.destroy(); adapter.submitList(emptyList())
            }
        }
    }

    @Test fun replacementWithSameMetadataRebindsToTheNewCallbackAndLease() = fixture { root, _ ->
        val source = File(root, "same.txt").apply { writeText("synthetic") }
        var oldClicks = 0; var newClicks = 0
        lateinit var old: FileModel; lateinit var next: FileModel
        lateinit var adapter: ConversationsFilesAdapter
        val changed = CountDownLatch(1)
        main {
            old = FileModel(source.path, source.name, source.length(), 0, false, source.path, false, loadMetadata = false) {
                oldClicks++
            }
            next = FileModel(source.path, source.name, source.length(), 0, false, source.path, false, loadMetadata = false) {
                newClicks++
            }
            adapter = ConversationsFilesAdapter(); adapter.submitList(listOf(old))
            adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { changed.countDown() }
            })
            old.destroy(); adapter.submitList(listOf(next))
        }
        try {
            assertTrue("Replacement must rebind even when its MIME/path/name are unchanged", changed.await(10, TimeUnit.SECONDS))
            main {
                val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Linphone))
                val holder = adapter.onCreateViewHolder(parent, ConversationsFilesAdapter.DOCUMENT_FILE) as ConversationsFilesAdapter.DocumentFileViewHolder
                adapter.onBindViewHolder(holder, 0)
                assertSame(next, holder.binding.model)
                holder.binding.model!!.onClick()
            }
            assertEquals(0, oldClicks); assertEquals(1, newClicks)
        } finally { main { old.destroy(); next.destroy(); adapter.submitList(emptyList()) } }
    }

    @Test fun themedFileRowsUseTheirViewLifecycleWithoutCastingContextToActivity() = fixture { _, _ ->
        main {
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
            }
            val parent = FrameLayout(ContextThemeWrapper(context, R.style.Theme_Linphone))
            val adapter = ConversationsFilesAdapter()
            val holder = adapter.onCreateViewHolder(parent, ConversationsFilesAdapter.DOCUMENT_FILE) as ConversationsFilesAdapter.DocumentFileViewHolder
            holder.itemView.setViewTreeLifecycleOwner(owner)
            org.linphone.utils.setInflatedViewStubLifecycleOwner(holder.itemView, true)
            assertSame(owner, holder.binding.lifecycleOwner)
            org.linphone.utils.setInflatedViewStubLifecycleOwner(holder.itemView, false)
            assertNull(holder.binding.lifecycleOwner)
            (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test fun longHistoryDoesNotExportUntilVisibleAndRecyclingReleasesEveryOldFile() = fixture { root, store ->
        val exports = java.util.concurrent.atomic.AtomicInteger()
        val contents = (0 until 120).map { index ->
            content(File(root, "encrypted-$index"), "$index.txt", true) {
                exports.incrementAndGet()
                File.createTempFile("export-$index-", ".txt", root).apply { writeText("synthetic") }.path
            }
        }
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core { vm.chatRoom = room(contents); vm.afterNotifyingChatRoomFound(false) }
        for (size in 20..120 step 20) {
            await { vm.documentsList.value?.size == size && vm.operationInProgress.value == false }
            if (size < 120) main { vm.loadMoreData() }
        }
        assertEquals(0, exports.get())
        lateinit var adapter: ConversationsFilesAdapter
        lateinit var holder: androidx.recyclerview.widget.RecyclerView.ViewHolder
        main {
            adapter = ConversationsFilesAdapter(); adapter.submitList(vm.documentsList.value)
            holder = adapter.onCreateViewHolder(FrameLayout(ContextThemeWrapper(context, R.style.Theme_Linphone)), ConversationsFilesAdapter.DOCUMENT_FILE)
            adapter.onBindViewHolder(holder, 0)
            assertEquals(0, exports.get())
            adapter.onViewAttachedToWindow(holder)
        }
        try {
            for (index in 0 until 40) {
                main { adapter.onBindViewHolder(holder, index) }
                await {
                    val row = vm.documentsList.value!![index]
                    exports.get() == index + 1 && row.preparing.value == false && row.path != row.originalPath &&
                        root.listFiles().orEmpty().count { it.name.startsWith("export-") } == 1
                }
            }
            main { adapter.setPresentationEnabled(false) }
            await { root.listFiles().orEmpty().isEmpty() }
            assertTrue(vm.documentsList.value!!.none { it.metadataRequested })
            main { adapter.setPresentationEnabled(true) }
            await { exports.get() == 41 && root.listFiles().orEmpty().size == 1 }
        } finally {
            main { adapter.onViewDetachedFromWindow(holder); adapter.onViewRecycled(holder); adapter.submitList(emptyList()) }
        }
        await { root.listFiles().orEmpty().isEmpty() }
    }

    @Test fun openedFileOwnsASeparateLeaseAfterTheRowScrollsAway() = fixture { root, store ->
        val plain = File(root, "open.txt")
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core {
            vm.chatRoom = room(
                listOf(
                    content(File(root, "encrypted"), "open.txt", true) {
                plain.apply { writeText("synthetic") }.path
            }
                )
            )
            vm.afterNotifyingChatRoomFound(false)
        }
        await { vm.documentsList.value?.size == 1 }
        val row = vm.documentsList.value!!.single()
        var presentation: Closeable? = null
        main { presentation = row.retainPresentation(); row.onClick() }
        await { vm.openDocumentEvent.value != null }
        lateinit var opened: FileModel
        main { vm.openDocumentEvent.value!!.consume { opened = it } }
        assertNotSame(row, opened)
        var viewer: Mango9NativeFileLease? = null
        main { presentation!!.close(); viewer = opened.retainFile() }
        assertTrue(plain.exists()); assertEquals(plain.path, opened.path)
        main { store.clear() }
        assertTrue(plain.exists())
        viewer!!.close(); await { !plain.exists() }
    }

    @Test fun cancelledOffscreenExportIsRemovedBeforeTheCoreQueueReachesIt() = fixture { root, store ->
        val exports = java.util.concurrent.atomic.AtomicInteger()
        lateinit var vm: ConversationDocumentsListViewModel
        main { vm = ConversationDocumentsListViewModel(); store.put("list", vm) }
        core {
            vm.chatRoom = room(
                listOf(
                    content(File(root, "encrypted"), "queued.txt", true) {
                exports.incrementAndGet(); error("Cancelled export ran")
            }
                )
            )
            vm.afterNotifyingChatRoomFound(false)
        }
        await { vm.documentsList.value?.size == 1 }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        coreContext.postOnCoreThread { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            main { vm.documentsList.value!!.single().retainPresentation().close() }
        } finally { release.countDown() }
        core {}; instrumentation.waitForIdleSync()
        assertEquals(0, exports.get()); assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun fixture(test: (File, ViewModelStore) -> Unit) {
        val root = File.createTempFile("native-content-list-", ".tmp", context.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val store = ViewModelStore()
        try {
            var ready = false
            await { core { ready = it.globalState == GlobalState.On }; ready }
            test(root, store)
        } finally {
            main { store.clear() }; core { /* drain cancellation */ }
            root.deleteRecursively()
        }
    }

    private fun room(
        contents: List<Content>,
        ranges: MutableList<Pair<Int, Int>> = mutableListOf(),
        listeners: MutableList<ChatRoomListener> = mutableListOf(),
    ): ChatRoom = fake { method, args ->
        when (method) {
            "getCore" -> coreContext.core
            "addListener" -> { listeners.add(args[0] as ChatRoomListener); null }
            "removeListener" -> { listeners.remove(args[0] as ChatRoomListener); null }
            "getDocumentContentsSize", "getMediaContentsSize" -> contents.size
            "getDocumentContentsRange", "getMediaContentsRange" -> {
                val start = args[0] as Int; val end = args[1] as Int; ranges.add(start to end)
                contents.subList(start, end.coerceAtMost(contents.size)).toTypedArray()
            }
            "isEphemeralEnabled" -> false
            "findMessage" -> null
            else -> error("Unexpected room operation: $method")
        }
    }

    private fun content(original: File, name: String, encrypted: Boolean, export: () -> String): Content = fake { method, _ ->
        when (method) {
            "getName" -> name; "getFilePath" -> original.path; "isFileEncrypted" -> encrypted
            "isFileTransfer" -> false
            "exportPlainFile" -> export(); "getFileSize", "getSize" -> 1; "getCreationTimestamp" -> 0L
            "getRelatedChatMessageId" -> null; "getType" -> if (name.endsWith(".txt")) "text" else "application"
            "getSubtype" -> if (name.endsWith(".txt")) "plain" else "pdf"
            else -> error("Unexpected content operation: $method")
        }
    }

    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) { "equals" -> self === args?.get(0); "hashCode" -> System.identityHashCode(self); "toString" -> "Content fixture"; else -> call(method.name, args.orEmpty()) }
        } as T

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun core(action: (Core) -> Unit) {
        val latch = CountDownLatch(1); var result: Result<Unit>? = null
        coreContext.postOnCoreThread { sdk -> result = runCatching { action(sdk) }; latch.countDown() }
        assertTrue(latch.await(15, TimeUnit.SECONDS)); result!!.getOrThrow()
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) { instrumentation.waitForIdleSync(); return }; SystemClock.sleep(20)
        }
        fail("Native content list did not settle")
    }
}
