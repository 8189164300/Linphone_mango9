/*
 * Copyright (c) 2026 Mango9.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.mango9

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.Image
import coil3.BitmapImage
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachment
import org.linphone.ui.main.crm.adapter.Mango9ConversationAttachmentsView
import org.linphone.ui.main.crm.adapter.Mango9MessageAdapter
import org.linphone.ui.main.crm.adapter.Mango9MessageListItem
import org.linphone.ui.main.crm.adapter.Mango9ThumbnailLoader
import org.linphone.ui.main.crm.adapter.Mango9ThumbnailView

/** Production views, real Coil decoders/cache and original login leases; requests intercepted before DNS. */
@RunWith(AndroidJUnit4::class)
class Mango9ThumbnailsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun identicalVisibleImagesShareOneAcquisitionAndDecodeAtBoundedSize() = fixture { f ->
        f.delayMillis = 100
        f.submit(listOf(sms("one", "shared.png"), team("two", "shared.png")))
        await { f.images().size == 2 && f.images().all { it.drawable is BitmapDrawable } }
        ui {
            assertEquals(1, f.acquisitions)
            assertEquals(1, f.requests.get())
            val bitmaps = f.images().map { (it.drawable as BitmapDrawable).bitmap }
            assertSame(bitmaps[0], bitmaps[1])
            bitmaps.forEach {
                assertTrue(it.width in 1..Mango9ThumbnailLoader.MAX_PIXELS)
                assertTrue(it.height in 1..Mango9ThumbnailLoader.MAX_PIXELS)
                assertEquals(Color.RED, it.getPixel(it.width / 2, it.height / 2))
            }
            assertTrue(f.openFiles().isEmpty())
        }
    }

    @Test fun realAccountSwitchClearsVisibleAndCachedImagesForTheOldLogin() = fixture { f ->
        f.submit(listOf(sms("same", "shared.png")))
        await { f.images().singleOrNull()?.drawable is BitmapDrawable }
        lateinit var oldView: Mango9ThumbnailView
        ui {
            oldView = f.images().single()
            f.body = png(Color.BLUE)
            f.sessions.save(f.b, persist = false, makeActive = true)
        }
        await { oldView.drawable == null && f.imageLoader.memoryCache?.keys?.isEmpty() == true }
        ui { f.replaceLoader() }
        f.submit(listOf(sms("same", "shared.png")))
        await { f.images().singleOrNull()?.drawable is BitmapDrawable }
        ui { assertEquals(Color.BLUE, f.color()); assertEquals(2, f.requests.get()) }
        ui { f.sessions.remove(requireNotNull(f.b.sipIdentity)) }
        await { f.images().all { it.drawable == null } && f.imageLoader.memoryCache?.keys?.isEmpty() == true }
    }

    @Test fun invalidatedSlowResultCannotRepaintARecycledViewOrPopulateMemoryCache() = fixture { f ->
        var oldAsset: Mango9MediaAsset? = null
        var releasedOnMain: Boolean? = null
        f.customAcquire = {
            withContext(NonCancellable) { delay(180) }
            val file = File.createTempFile("late-", ".png", f.root)
            file.writeBytes(png(Color.RED))
            Mango9MediaAsset(file) {
                releasedOnMain = Looper.myLooper() == Looper.getMainLooper()
                file.delete()
            }.also { oldAsset = it }
        }
        f.submit(listOf(sms("slow", "same.png")))
        await { f.acquisitions == 1 }
        ui { f.sessions.save(f.b, persist = false, makeActive = true) }
        await { oldAsset != null && oldAsset?.file?.exists() == false }
        ui {
            assertEquals(false, releasedOnMain)
            assertTrue(f.images().all { it.drawable == null })
            assertTrue(f.imageLoader.memoryCache?.keys?.isEmpty() == true)
            f.customAcquire = null
            f.body = png(Color.BLUE)
            f.replaceLoader()
        }
        f.submit(listOf(sms("fresh", "same.png")))
        await { f.images().singleOrNull()?.drawable is BitmapDrawable }
        ui { assertEquals(Color.BLUE, f.color()) }
    }

    @Test fun actualVideoFrameLoadsWithoutAFilenameOverlayOrRetainedSourceFile() = fixture { f ->
        val file = File(f.root, "generated.mp4")
        Mango9MediaTestFixtures.createVideo(file)
        f.body = file.readBytes()
        file.delete()
        f.submit(listOf(sms("video", "generated.mp4")))
        await { f.images().singleOrNull()?.drawable is BitmapDrawable }
        ui {
            val image = f.images().single()
            assertTrue((image.drawable as BitmapDrawable).bitmap.width > 0)
            assertTrue(f.openFiles().isEmpty())
            val parent = image.parent as ViewGroup
            val labels = (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<android.widget.TextView>()
            assertEquals(listOf("▶"), labels.map { it.text.toString() })
            snapshot(f.recycler, "mango9-thumbnail-video.png")
        }
    }

    @Test fun thousandImageHistoryOnlyLoadsVisibleRowsAndBackgroundReturnsEveryPin() = fixture { f ->
        f.delayMillis = 80
        f.submit((0 until 1_000).map { sms("$it", "$it.png") })
        await { f.images().any { it.drawable is BitmapDrawable } }
        ui {
            assertTrue(f.requests.get() < 20)
            assertTrue(f.recycler.childCount < 20)
            f.recycler.scrollToPosition(999)
        }
        await { f.recycler.findViewHolderForAdapterPosition(999) != null && f.images().any { it.drawable is BitmapDrawable } }
        ui { f.loader.setForeground(false) }
        await { f.openFiles().isEmpty() && f.images().all { it.drawable == null } }
        assertTrue(f.maximumConcurrent.get() <= 4)
        ui { f.loader.setForeground(true) }
        await { f.images().any { it.drawable is BitmapDrawable } }
    }

    @Test fun attachmentBrowserUsesTheSamePreviewPipelineAndDisposeReleasesItsViews() = fixture { f ->
        lateinit var browser: Mango9ConversationAttachmentsView
        ui {
            browser = Mango9ConversationAttachmentsView(f.activity, false, f.loader) {}
            f.dialog.setContentView(browser)
            browser.submit(Mango9ConversationAttachment.collect((0 until 500).map { sms("$it", "same.png") }, false))
        }
        await {
            val visibleImages = images(browser)
            visibleImages.isNotEmpty() && visibleImages.all { it.drawable is BitmapDrawable } && f.loader.pendingRequests == 0
        }
        ui {
            assertEquals(500, browser.recycler.adapter?.itemCount)
            assertTrue(browser.recycler.childCount < 30)
            assertEquals(1, f.requests.get())
            snapshot(browser, "mango9-thumbnail-browser.png")
            browser.dispose()
        }
        await { f.openFiles().isEmpty() && images(browser).isEmpty() }
    }

    @Test fun corruptThumbnailIsNotACacheHitWhenTheViewRetries() = fixture { f ->
        f.body = "not a picture".toByteArray()
        f.submit(listOf(sms("bad", "retry.png")))
        await { f.requests.get() == 1 && f.loader.pendingRequests == 0 && f.openFiles().isEmpty() && f.acquisitions == 1 }
        ui { f.loader.setForeground(false); f.body = png(Color.BLUE); f.loader.setForeground(true) }
        await { f.images().singleOrNull()?.drawable is BitmapDrawable }
        ui { assertEquals(Color.BLUE, f.color()); assertEquals(2, f.requests.get()) }
    }

    @Test fun vectorPreviewsAreStaticShareableBitmapsInBothVisibleRows() = fixture { f ->
        f.body = """<svg xmlns="http://www.w3.org/2000/svg" width="2400" height="1600"><rect width="2400" height="1600" fill="#0000ff"/></svg>""".toByteArray()
        f.submit(listOf(sms("one", "vector.svg?ffName=vector.svg&ffType=image%2Fsvg%2Bxml"), sms("two", "vector.svg?ffName=vector.svg&ffType=image%2Fsvg%2Bxml")))
        await { f.images().size == 2 && f.images().all { it.drawable is BitmapDrawable } }
        ui {
            val bitmaps = f.images().map { (it.drawable as BitmapDrawable).bitmap }
            assertSame(bitmaps[0], bitmaps[1])
            assertEquals(Color.BLUE, bitmaps[0].getPixel(bitmaps[0].width / 2, bitmaps[0].height / 2))
            assertTrue(bitmaps[0].width <= Mango9ThumbnailLoader.MAX_PIXELS)
            assertTrue(bitmaps[0].height <= Mango9ThumbnailLoader.MAX_PIXELS)
            assertEquals(1, f.requests.get())
            assertTrue(f.imageLoader.memoryCache!!.keys.all { f.imageLoader.memoryCache!![it]!!.image.shareable })
        }
    }

    @Test fun animatedSourceProducesOneStaticFirstFrameForBothChatTypes() = fixture { f ->
        // Two authored 1x1 frames, red then blue (no external fixture/license).
        f.body = animatedGif()
        val source = File(f.root, "two-frames.gif").apply { writeBytes(f.body) }
        val original = runBlocking {
            f.imageLoader.execute(ImageRequest.Builder(context).data(source).memoryCachePolicy(CachePolicy.DISABLED).build())
        }
        assertTrue(original is SuccessResult)
        assertFalse("The control decode must exercise a non-shareable animated drawable", (original as SuccessResult).image.shareable)
        source.delete()
        f.submit(listOf(sms("one", "two-frames.gif"), team("two", "two-frames.gif")))
        await { f.images().size == 2 && f.images().all { it.drawable is BitmapDrawable } }
        ui {
            val bitmaps = f.images().map { (it.drawable as BitmapDrawable).bitmap }
            assertSame(bitmaps[0], bitmaps[1])
            assertEquals(Color.RED, bitmaps[0].getPixel(0, 0))
            assertEquals(1, f.requests.get())
            assertTrue(f.openFiles().isEmpty())
        }
    }

    @Test fun tinyPreviewCountIsBoundedWithoutRemovingAnUnrelatedAvatar() = fixture { f ->
        f.body = png(Color.BLUE, 1, 1)
        val avatarKey = MemoryCache.Key("unrelated-avatar")
        ui {
            f.imageLoader.memoryCache!![avatarKey] = MemoryCache.Value(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage())
        }
        repeat(Mango9ThumbnailLoader.MAX_ENTRIES + 25) { index ->
            var delivered: Image? = null
            var subscription: AutoCloseable? = null
            ui {
                subscription = f.loader.observe(Mango9ChatMedia.parse("https://media.example.invalid/tiny-$index.png").single(), 1, 1) {
                    delivered = it
                }
            }
            await { delivered != null && f.loader.pendingRequests == 0 }
            ui {
                subscription!!.close()
                assertTrue(f.imageLoader.memoryCache!!.keys.size <= Mango9ThumbnailLoader.MAX_ENTRIES + 1)
                assertNotNull(f.imageLoader.memoryCache!![avatarKey])
            }
        }
        assertEquals(Mango9ThumbnailLoader.MAX_ENTRIES + 25, f.requests.get())
        assertTrue(f.openFiles().isEmpty())
    }

    @Test fun differentSizesShareSourceBytesButNotDecodedImagesAndExpiryRefetches() = fixture { f ->
        val media = Mango9ChatMedia.parse("https://media.example.invalid/sized.png").single()
        var small: Image? = null
        var large: Image? = null
        val subscriptions = mutableListOf<AutoCloseable>()
        ui {
            subscriptions.add(f.loader.observe(media, 40, 40) { small = it })
            subscriptions.add(f.loader.observe(media, 120, 120) { large = it })
        }
        await { small is BitmapImage && large is BitmapImage && f.loader.pendingRequests == 0 }
        ui {
            assertTrue(small!!.width <= 40 && small!!.height <= 40)
            assertTrue(large!!.width <= 120 && large!!.height <= 120)
            assertTrue(large!!.width > small!!.width)
            assertEquals(2, f.acquisitions)
            assertEquals(1, f.requests.get())
            var cached: Image? = null
            f.loader.observe(media, 40, 40) { cached = it }.close()
            assertSame(small, cached)
            assertEquals(2, f.acquisitions)
            subscriptions.forEach { it.close() }
            f.body = png(Color.BLUE)
            f.nowMillis += 24L * 60 * 60 * 1000
            small = null
            subscriptions.add(f.loader.observe(media, 40, 40) { small = it })
        }
        await { small is BitmapImage && f.loader.pendingRequests == 0 }
        ui {
            val bitmap = (small as BitmapImage).bitmap
            assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            assertEquals(2, f.requests.get())
            subscriptions.forEach { it.close() }
            assertTrue(f.openFiles().isEmpty())
        }
    }

    private inner class Fixture(val activity: MainActivity, val root: File, bytes: ByteArray) : AutoCloseable {
        val sessions = Mango9SessionStore(context)
        private val oldIdentity = sessions.activeIdentity
        val a = session("thumbnail-a")
        val b = session("thumbnail-b")

        @Volatile var body = bytes

        @Volatile var delayMillis = 0L

        @Volatile var nowMillis = 1_800_000_000_000L
        var acquisitions = 0
        val requests = AtomicInteger()
        private val concurrent = AtomicInteger()
        val maximumConcurrent = AtomicInteger()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val imageLoader = ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()); add(SvgDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(8L * 1024 * 1024).build() }
            .diskCache(null).build()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val captured = body
            requests.incrementAndGet()
            val active = concurrent.incrementAndGet()
            maximumConcurrent.updateAndGet { maxOf(it, active) }
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(captured.toResponseBody()).build()
            } finally { concurrent.decrementAndGet() }
        }.build()
        private val cache = Mango9MediaDiskCache(File(root, "disk"), File(root, "open"), client, clock = { nowMillis })
        var customAcquire: (suspend (Mango9ChatMedia) -> Mango9MediaAsset)? = null
        lateinit var loader: Mango9ThumbnailLoader
        private val loaders = mutableListOf<Mango9ThumbnailLoader>()
        val recycler = RecyclerView(activity).apply { layoutManager = LinearLayoutManager(activity) }
        private lateinit var adapter: Mango9MessageAdapter
        val dialog = Dialog(activity).apply {
            setContentView(recycler)
            show()
            window?.setLayout(dp(360), dp(600))
        }
        private var closed = false

        init { sessions.save(a, persist = false, makeActive = true); replaceLoader() }

        fun replaceLoader() {
            val lease = requireNotNull(sessions.captureSession())
            loader = Mango9ThumbnailLoader(
                context, scope, imageLoader, Mango9MediaCache.ownerKey(lease),
                { sessions.currentSession(lease) != null },
                { media -> acquisitions++; customAcquire?.invoke(media) ?: Mango9MediaCache.acquire(cache, sessions, media, lease) },
                clock = { nowMillis },
            )
            loaders.add(loader)
            loader.setForeground(true)
            adapter = Mango9MessageAdapter({}, {}).apply { setThumbnailLoader(loader) }
            recycler.adapter = adapter
        }

        fun submit(items: List<Mango9MessageListItem>) {
            ui { adapter.submitList(items) }
            await { adapter.currentList == items && recycler.childCount > 0 }
            instrumentation.waitForIdleSync()
        }

        fun images() = images(recycler)

        fun openFiles() = File(root, "open").listFiles().orEmpty().toList()

        fun color(): Int = (images().single().drawable as BitmapDrawable).bitmap.let {
            it.getPixel(it.width / 2, it.height / 2)
        }

        override fun close() {
            if (closed) return
            closed = true
            loaders.forEach { it.dispose() }
            dialog.dismiss(); recycler.adapter = null; scope.cancel()
            sessions.remove(requireNotNull(a.sipIdentity)); sessions.remove(requireNotNull(b.sipIdentity)); sessions.activate(oldIdentity)
        }

        fun awaitClosed() {
            runBlocking { scope.coroutineContext[Job]?.join() }
            imageLoader.shutdown(); cache.shutdown()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "thumbnail-regression-").toFile()
        val data = png(Color.RED, 2400, 1600)
        var oldDefault: Account? = null
        var account: Account? = null
        var f: Fixture? = null
        try {
            core { c ->
                oldDefault = c.defaultAccount
                account = c.createAccount(
                    c.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress("sip:thumbnail@fixture.example.invalid")
                    serverAddress = Factory.instance().createAddress("sip:fixture.example.invalid")
                    isRegisterEnabled = false; pushNotificationAllowed = false
                }
                )
                c.addAccount(account); c.defaultAccount = account
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                scenario.onActivity { f = Fixture(it, root, data) }
                try { block(requireNotNull(f)) } finally { ui { f?.close() } }
            }
        } finally {
            ui { f?.close() }
            f?.awaitClosed()
            core { c ->
                c.defaultAccount = oldDefault; account?.let {
                if (c.accountList.any { found -> found === it }) c.removeAccount(it)
            }; c.config.sync()
            }
            root.deleteRecursively()
        }
    }

    private fun images(view: View): List<Mango9ThumbnailView> = buildList {
        if (view is Mango9ThumbnailView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(images(view.getChildAt(index)))
    }

    private fun snapshot(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun session(name: String) = Mango9Session("fixture", "https://crm.example.invalid", "https://crm.example.invalid/api/v2", "fixture", "fixture", "agent", "$name@example.invalid", "Fixture", "fixture-access", "fixture-refresh", "", "", 1_900_000_000, "sip:$name@crm.example.invalid")

    private fun sms(id: String, file: String) = Mango9MessageListItem.Sms(Mango9SmsMessage(id, "2025550100", "", "2026-09-13T10:30:00Z", "", 2, true, "https://media.example.invalid/$file"))

    private fun team(id: String, file: String) = Mango9MessageListItem.Team(Mango9ChatMessage(id, 1, "fixture", "", "2026-09-13T10:30:00Z", 2, "https://media.example.invalid/$file"), false, "Teammate")

    private fun animatedGif(): ByteArray = ByteArrayOutputStream().use { output ->
        fun bytes(vararg values: Int) { values.forEach(output::write) }
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        bytes(1, 0, 1, 0, 0x80, 0, 0) // Logical screen/global two-color palette.
        bytes(255, 0, 0, 0, 0, 255)
        repeat(2) { index ->
            bytes(0x21, 0xf9, 4, 0, 10, 0, 0, 0) // 100ms graphic control.
            bytes(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0)
            bytes(2, 2, 0x44 + index * 8, 1, 0) // LZW clear, palette index, end.
        }
        bytes(0x3b)
        output.toByteArray()
    }

    private fun png(color: Int, width: Int = 60, height: Int = 40): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
    }

    private fun ui(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12_000
        do {
            var ready = false
            ui { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < end)
        fail("Thumbnail state did not settle")
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { c ->
            try {
            action(c)
        } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS)); failure?.let { throw it }
    }
}
