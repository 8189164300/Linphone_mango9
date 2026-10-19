// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.R
import org.linphone.databinding.Mango9MessagingConversationFragmentBinding

/** Device-safe: synthetic cache files only. Never replaces accounts, sends, or changes permissions. */
@RunWith(AndroidJUnit4::class)
class Mango9SocialComposerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun singleAttachmentLimitAllowsReplacementAndPreservesTheOriginalSources() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "social-composer-test-").toFile()
        val first = File(root, "one.txt").apply { writeText("synthetic one") }
        val second = File(root, "two.txt").apply { writeText("synthetic two") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val composer =
            Mango9MessageComposer(context, scope, { true }, Mango9AttachmentPreparer(context, File(root, "prepared"))::prepare)
        try {
            withContext(Dispatchers.Main) {
                composer.setAttachmentLimit(1)
                composer.import(listOf(Uri.fromFile(first), Uri.fromFile(second)))
            }
            withTimeout(10_000) { while (composer.state.value.preparing > 0) delay(20) }
            assertEquals(1, composer.state.value.attachments.size)
            assertEquals("one.txt", composer.state.value.attachments.single().name)
            assertTrue(composer.state.value.error.orEmpty().contains("one attachment"))
            withContext(Dispatchers.Main) {
                composer.remove(composer.state.value.attachments.single().uri)
                composer.import(listOf(Uri.fromFile(second)))
            }
            withTimeout(10_000) { while (composer.state.value.preparing > 0) delay(20) }
            assertEquals("two.txt", composer.state.value.attachments.single().name)
            assertNull(composer.state.value.error)
            assertTrue(first.exists()); assertTrue(second.exists())
        } finally {
            withContext(Dispatchers.Main) { composer.dispose(); scope.cancel() }
            root.deleteRecursively()
        }
    }

    @Test fun composerControlsHaveFullTouchTargetsAtNarrowWidthAndEarlierMessagesAreReachable() {
        instrumentation.runOnMainSync {
            val themed = ContextThemeWrapper(context, R.style.Theme_Linphone)
            val binding = Mango9MessagingConversationFragmentBinding.inflate(LayoutInflater.from(themed))
            val density = context.resources.displayMetrics.density
            binding.loadEarlier.visibility = View.VISIBLE
            binding.root.measure(View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY))
            binding.root.layout(0, 0, binding.root.measuredWidth, binding.root.measuredHeight)
            for (control in listOf(binding.attach, binding.microphone, binding.send)) {
                assertTrue(control.width >= (48 * density).toInt())
                assertTrue(control.height >= (48 * density).toInt())
            }
            assertTrue(binding.message.width >= (140 * density).toInt())
            assertTrue(binding.loadEarlier.bottom <= binding.messages.top)
            assertTrue(binding.messages.bottom <= binding.composerPanel.top)
            var taps = 0
            binding.attach.setOnClickListener { taps++ }
            assertTrue(binding.attach.performClick())
            assertEquals(1, taps)
        }
    }

    @Test fun lateReceiptDoesNotEraseANewerDraftOrReplacementAttachment() = runBlocking {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "social-receipt-test-").toFile()
        val original = File(root, "original.txt").apply { writeText("first fixture") }
        val replacement = File(root, "replacement.txt").apply { writeText("second fixture") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val composer =
            Mango9MessageComposer(context, scope, { true }, Mango9AttachmentPreparer(context, File(root, "prepared"))::prepare)
        try {
            withContext(Dispatchers.Main) {
                composer.setAttachmentLimit(1); composer.changeText("original text")
                composer.import(listOf(Uri.fromFile(original)))
            }
            withTimeout(10_000) { while (composer.state.value.preparing > 0) delay(20) }
            withContext(Dispatchers.Main) {
                composer.send { _, _ -> false } // lost/unknown result, no actual network send
                composer.remove(composer.state.value.attachments.single().uri)
                composer.changeText("new text")
                composer.import(listOf(Uri.fromFile(replacement)))
            }
            withTimeout(10_000) { while (composer.state.value.preparing > 0) delay(20) }
            withContext(Dispatchers.Main) { composer.confirmSavedSend("original text") }
            assertEquals("new text", composer.state.value.text)
            assertEquals("replacement.txt", composer.state.value.attachments.single().name)
            assertTrue(original.exists()); assertTrue(replacement.exists())
        } finally {
            withContext(Dispatchers.Main) { composer.dispose(); scope.cancel() }
            root.deleteRecursively()
        }
    }
}
