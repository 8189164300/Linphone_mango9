// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mango9ExternalShareInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun intentsAdmitTwelveAndOneOverflowMarkerBeforeProviderWork() {
        val many = ArrayList((1..10000).map { Uri.parse("content://example.invalid/$it") })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)
        assertEquals(many.take(13), Mango9ExternalShare.streams(intent))
        val clips = ClipData.newRawUri("attachment", many[0]).apply { addItem(ClipData.Item(many[1])) }
        assertEquals(many.take(2), Mango9ExternalShare.streams(Intent(Intent.ACTION_SEND).apply { clipData = clips }))
        assertTrue(
            Mango9ExternalShare.streams(
                Intent(Intent.ACTION_SEND).putExtra(
            Intent.EXTRA_STREAM, Uri.parse("file:///data/user/0/com.mango9.phone/private.txt"),
        )
            ).isEmpty()
        )
    }

    @Test fun bindsOnlyExactRecipientAndDeliveryAndClaimsOnlyOnce() = fixture { f ->
        val original = f.source()
        lateinit var id: String
        lateinit var delivery: String
        withContext(Dispatchers.Main) {
            id = f.share.begin("caption", listOf(Uri.fromFile(original)))!!
            delivery = f.share.bind(id, "team-user:42")!!
            assertNull(f.share.claim(delivery, "team-user:42")) // still preparing
        }
        f.ready()
        withContext(Dispatchers.Main) {
            assertNull(f.share.claim(delivery, "sms:15555550100"))
            assertNull(f.share.claim("previous-screen", "team-user:42"))
            val result = f.share.claim(delivery, "team-user:42")!!
            assertEquals("caption", result.text)
            assertEquals(1, result.files.size)
            assertTrue(result.isCurrent())
            assertNull(f.share.claim(delivery, "team-user:42"))
            result.close()
        }
        assertTrue(original.exists())
    }

    @Test fun replacedRequestCannotBindNewShareAndPreviousDeliveryCannotClaimRetargetedShare() = fixture { f ->
        withContext(Dispatchers.Main) {
            val previous = f.share.begin("old", emptyList())!!
            val current = f.share.begin("new", emptyList())!!
            assertNull(f.share.bind(previous, "sip:old-room"))
            val first = f.share.bind(current, "sip:first-room")!!
            val second = f.share.bind(current, "sip:second-room")!!
            assertNull(f.share.claim(first, "sip:first-room"))
            val result = f.share.claim(second, "sip:second-room")!!
            assertEquals("new", result.text)
            result.close()
        }
    }

    @Test fun accountRoundTripAndSameIdentityReloginInvalidatePendingShares() = fixture { f ->
        withContext(Dispatchers.Main) {
            val id = f.share.begin("old account", emptyList())!!
            f.stamp = f.stamp.copy(selection = f.stamp.selection + 2)
            assertNull(f.share.bind(id, "sms:15555550100"))
            assertNull(f.share.state.value.id)
            val newId = f.share.begin("old login", emptyList())!!
            val delivery = f.share.bind(newId, "team-user:42")!!
            f.stamp = f.stamp.copy(login = f.stamp.login + 1)
            assertNull(f.share.claim(delivery, "team-user:42"))
        }
    }

    @Test fun cancellationReleasesLateProviderResultWithoutTouchingReplacement() = fixture { f ->
        val produced = CompletableDeferred<File>()
        val release = CompletableDeferred<Unit>()
        val source = f.source()
        val share = Mango9ExternalShare(f.scope, { f.stamp }) { uri, hint ->
            val result = f.preparer.prepare(uri, hint)
            produced.complete(File(result.attachment.uri.path!!))
            withContext(NonCancellable) { release.await() }
            result
        }
        try {
            withContext(Dispatchers.Main) { share.begin("original", listOf(Uri.fromFile(source))) }
            val file = produced.await()
            withContext(Dispatchers.Main) { share.begin("replacement", emptyList()) }
            release.complete(Unit)
            await { !file.exists() }
            assertEquals("replacement", share.state.value.text)
            assertTrue(source.isFile)
        } finally { release.complete(Unit); withContext(Dispatchers.Main) { share.dispose() } }
    }

    @Test fun onlyTwelveFilesArePreparedAndPartialFailuresRemainVisible() = fixture { f ->
        val source = f.source()
        var calls = 0
        val share = Mango9ExternalShare(f.scope, { f.stamp }) { _, _ ->
            calls++
            if (calls == 2) throw java.io.IOException("synthetic denied provider")
            f.preparer.prepare(Uri.fromFile(source))
        }
        try {
            withContext(Dispatchers.Main) {
                share.begin("caption", (1..1000).map { Uri.parse("content://example.invalid/$it") })
            }
            await { !share.state.value.preparing }
            assertEquals(12, calls)
            assertEquals(11, share.state.value.count)
            assertNotNull(share.state.value.error)
        } finally { withContext(Dispatchers.Main) { share.dispose() } }
    }

    @Test fun composerAdoptsPreparedBytesWithoutAnotherProviderReadAndKeepsExistingDraft() = fixture { f ->
        val source = f.source()
        val composer =
            Mango9MessageComposer(context, f.scope, { true }) { error("Must not prepare shared files twice") }
        try {
            withContext(Dispatchers.Main) { f.share.begin("shared", listOf(Uri.fromFile(source))) }
            f.ready()
            val prepared = withContext(Dispatchers.Main) {
                val id = f.share.pendingRequestId()!!
                val delivery = f.share.bind(id, "team-user:42")!!
                val transfer = f.share.claim(delivery, "team-user:42")!!
                val file = File(transfer.files.single().attachment.uri.path!!)
                composer.changeText("already typed")
                composer.adopt(transfer)
                file
            }
            source.delete() // original provider is no longer needed
            assertEquals("already typed\nshared", composer.state.value.text)
            assertEquals("source.txt", composer.state.value.attachments.single().name)
            assertEquals("owned test payload", prepared.readText())
            withContext(Dispatchers.Main) { composer.remove(composer.state.value.attachments.single().uri) }
            await { !prepared.exists() }
        } finally { withContext(Dispatchers.Main) { composer.dispose() } }
    }

    @Test fun claimedFilesCannotBeAdoptedAfterAccountReplacement() = fixture { f ->
        withContext(Dispatchers.Main) { f.share.begin("private", listOf(Uri.fromFile(f.source()))) }
        f.ready()
        val composer = Mango9MessageComposer(context, f.scope, { true }) { error("unexpected import") }
        try {
            val file = withContext(Dispatchers.Main) {
                val delivery = f.share.bind(f.share.pendingRequestId(), "team-user:42")!!
                val transfer = f.share.claim(delivery, "team-user:42")!!
                val file = File(transfer.files.single().attachment.uri.path!!)
                f.stamp = f.stamp.copy(login = f.stamp.login + 1)
                composer.adopt(transfer)
                assertEquals("", composer.state.value.text)
                assertTrue(composer.state.value.attachments.isEmpty())
                file
            }
            await { !file.exists() }
        } finally { withContext(Dispatchers.Main) { composer.dispose() } }
    }

    private inner class Fixture {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "external-share-fixture-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var stamp = Mango9SelectionStamp("sip:synthetic@example.invalid", 1, 1)
        val preparer = Mango9AttachmentPreparer(context, File(root, "prepared"))
        val share = Mango9ExternalShare(scope, { stamp }, preparer::prepare)

        fun source() = File(root, "source.txt").apply { writeText("owned test payload") }

        suspend fun ready() = await { !share.state.value.preparing }
    }

    private fun fixture(test: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(20000) { test(f) } } finally {
            withContext(Dispatchers.Main) { f.share.dispose(); f.scope.cancel() }
            await {
                File(f.root, "prepared").listFiles().orEmpty().none { it.name != Mango9AttachmentDirectory.ROOT_LOCK }
            }
            check(f.root.deleteRecursively())
        }
    }

    private suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(20) } }
}
