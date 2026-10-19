// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.Conference
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.ui.main.chat.viewmodel.SendMessageInConversationViewModel

@RunWith(AndroidJUnit4::class)
class Mango9NativeDraftInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @org.junit.After fun persistSdkFixtureCleanup() = Mango9SdkTestCleanup.flush()

    @Test fun sharedSnapshotIsPromotedWithMimeAndSurvivesSdkHandoff() = fixture { f ->
        val source = File(f.root, "shared.m4a")
        Mango9MediaTestFixtures.createAudio(source)
        val prepared = kotlinx.coroutines.runBlocking {
            Mango9AttachmentPreparer(context).prepare(android.net.Uri.fromFile(source))
        }
        val snapshot = File(prepared.attachment.uri.path!!)
        ui {
            f.vm.textToSend.value = "existing"
            f.vm.adoptSharedDraft(Mango9SharedDraft("shared caption", listOf(prepared), { true }))
        }
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var files = emptyList<FileModel>()
        while (files.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
            ui { files = f.vm.attachments.value.orEmpty() }
            if (files.isEmpty()) Thread.sleep(20)
        }
        assertEquals(1, files.size)
        val durable = File(files.single().path)
        f.durableCopies.add(durable)
        assertNotEquals(snapshot.path, durable.path)
        assertEquals("shared.m4a", files.single().fileName)
        assertEquals("audio/mp4", files.single().mimeTypeString)
        assertArrayEquals(source.readBytes(), durable.readBytes())
        awaitAbsent(snapshot)
        ui { assertEquals("existing\nshared caption", f.vm.textToSend.value); f.vm.sendMessage() }
        drain()
        assertEquals(durable.path, f.sent.last().paths.single())
        assertEquals(listOf("audio/mp4"), f.sent.last().types)
        assertTrue(durable.isFile)
    }

    @Test fun accountSwitchBeforeSharePromotionRejectsAndReleasesTheSnapshot() = fixture { f ->
        val source = f.file("rejected-share.txt")
        val prepared = kotlinx.coroutines.runBlocking {
            Mango9AttachmentPreparer(context).prepare(android.net.Uri.fromFile(source))
        }
        val snapshot = File(prepared.attachment.uri.path!!)
        val owner = f.sessions.selectionStamp()
        pauseCore {
            ui { f.vm.adoptSharedDraft(Mango9SharedDraft("private", listOf(prepared), { f.sessions.selectionStamp() == owner })) }
            f.sessions.activate(null)
        }
        awaitAbsent(snapshot)
        ui { assertTrue(f.vm.attachments.value.orEmpty().isEmpty()); assertTrue(f.vm.textToSend.value.isNullOrEmpty()) }
        assertTrue(source.isFile)
    }

    @Test fun nativeShareCannotBeAdoptedByADifferentConversationInTheSameAccount() = fixture { f ->
        val source = f.file("wrong-room.txt")
        val prepared = kotlinx.coroutines.runBlocking {
            Mango9AttachmentPreparer(context).prepare(android.net.Uri.fromFile(source))
        }
        val snapshot = File(prepared.attachment.uri.path!!)
        ui { f.vm.adoptSharedDraft(Mango9SharedDraft("private", listOf(prepared), { true }), "different-conversation") }
        awaitAbsent(snapshot)
        ui { assertTrue(f.vm.attachments.value.orEmpty().isEmpty()); assertTrue(f.vm.textToSend.value.isNullOrEmpty()) }
        assertTrue(source.isFile)
    }

    @Test fun activityRecreationKeepsTheSameInFlightExternalShare() = fixture { _ ->
        val uri = android.net.Uri.parse("content://com.mango9.phone.test.outgoing/file?mode=stream&bytes=500000")
        val intent = android.content.Intent(context, org.linphone.ui.main.MainActivity::class.java).apply {
            action = android.content.Intent.ACTION_SEND
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TEXT, "rotation caption")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        androidx.test.core.app.ActivityScenario.launch<org.linphone.ui.main.MainActivity>(intent).use { scenario ->
            lateinit var original: Mango9ExternalShare
            var request: String? = null
            scenario.onActivity { activity ->
                original = ViewModelProvider(activity)[org.linphone.ui.main.viewmodel.SharedMainViewModel::class.java].externalShare
                request = original.pendingRequestId()
                assertNotNull(request)
                assertTrue(original.state.value.preparing)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val restored = ViewModelProvider(activity)[org.linphone.ui.main.viewmodel.SharedMainViewModel::class.java].externalShare
                assertSame(original, restored)
                assertEquals(request, restored.pendingRequestId())
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (original.state.value.preparing && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
            scenario.onActivity {
                assertFalse(original.state.value.preparing)
                assertEquals(1, original.state.value.count)
                assertEquals("rotation caption", original.state.value.text)
                original.clear()
            }
        }
    }

    @Test fun queuedSendKeepsOriginalFilesTextAndDestinationAndPreservesNewDraft() = fixture { f ->
        val original = f.file("original.txt")
        val next = f.file("next.txt")
        ui { f.vm.addAttachments(arrayListOf(original.path)); f.vm.textToSend.value = "original text" }
        pauseCore {
            ui {
                f.vm.sendMessage()
                f.vm.removeAttachment(original.path)
                f.vm.addAttachments(arrayListOf(next.path))
                f.vm.textToSend.value = "newer draft"
                f.vm.sendMessage() // A second tap while handoff is pending must not duplicate it.
                assertTrue(original.exists())
            }
        }
        assertEquals(2, f.sent.size) // A basic SIP room supports one content per message.
        assertEquals("original text", f.sent[0].text)
        assertEquals(original.path, f.sent[1].paths.single())
        assertTrue(f.sent.all { it.destination.endsWith("@example.invalid") })
        ui {
            assertEquals("newer draft", f.vm.textToSend.value)
            assertEquals(listOf(next.path), f.vm.attachments.value!!.map { it.path })
            f.store.clear()
        }
        awaitAbsent(next)
        assertTrue("SDK upload/history must still own the removed draft file", original.isFile)
    }

    @Test fun unchangedDraftIsAcknowledgedButSameTextEditedAwayAndBackIsNotLost() = fixture { f ->
        ui { f.vm.textToSend.value = "first"; f.vm.sendMessage() }
        drain()
        ui { assertEquals("", f.vm.textToSend.value); f.vm.textToSend.value = "again" }
        pauseCore {
            ui {
                f.vm.sendMessage()
                f.vm.textToSend.value = "replacement"
                f.vm.textToSend.value = "again"
            }
        }
        ui { assertEquals("again", f.vm.textToSend.value) }
        assertEquals(listOf("first", "again"), f.sent.map { it.text })
    }

    @Test fun destroyedViewModelCancelsQueuedHandoffAndReleasesPins() = fixture { f ->
        val file = f.file("cancelled.txt")
        ui { f.vm.addAttachments(arrayListOf(file.path)) }
        pauseCore { ui { f.vm.sendMessage(); f.store.clear(); assertTrue(file.exists()) } }
        assertTrue(f.sent.isEmpty())
        awaitAbsent(file)
    }

    @Test fun replacedNativeAccountCannotUseOldRoomEvenWithSameIdentity() = fixture { f ->
        val file = f.file("old-login.txt")
        ui { f.vm.addAttachments(arrayListOf(file.path)); f.vm.textToSend.value = "keep me" }
        core { sdk ->
            val params = f.accounts.first().params.clone()
            sdk.removeAccount(f.accounts.first())
            sdk.createAccount(params).also { f.accounts.add(it); sdk.addAccount(it); sdk.defaultAccount = it }
        }
        ui { f.vm.sendMessage() }
        drain()
        assertTrue(f.sent.isEmpty())
        ui { assertEquals("keep me", f.vm.textToSend.value); assertEquals(1, f.vm.attachments.value!!.size) }
        assertTrue(file.isFile)
    }

    @Test fun changedSelectionInvalidatesQueuedNativeSend() = fixture { f ->
        ui { f.vm.textToSend.value = "original account" }
        pauseCore {
            ui { f.vm.sendMessage() }
            f.sessions.activate(null)
        }
        assertTrue(f.sent.isEmpty())
        ui { assertEquals("original account", f.vm.textToSend.value) }
    }

    @Test fun overflowKeepsAcceptedPrefixDeletesAllRejectedAndDoesNotDeleteDuplicates() = fixture { f ->
        val files = (1..15).map { f.file("file-$it.txt") }
        ui {
            f.vm.addAttachments(ArrayList(files.map { it.path } + files.first().path))
            assertEquals(files.take(12).map { it.path }, f.vm.attachments.value!!.map { it.path })
            assertTrue(f.vm.maxNumberOfAttachmentsReached.value == true)
        }
        files.drop(12).forEach(::awaitAbsent)
        assertTrue(files.first().isFile)
        ui { f.vm.closeFileAttachmentsList(); assertTrue(f.vm.attachments.value!!.isEmpty()) }
        files.take(12).forEach(::awaitAbsent)
    }

    @Test fun m4aIsAudioMp4AtTheActualSdkBoundaryAndTextSharesAppend() = fixture { f ->
        val audio = File(f.root, "voice.m4a")
        Mango9MediaTestFixtures.createAudio(audio)
        ui {
            f.vm.textToSend.value = "existing draft"
            f.vm.appendSharedText("shared text")
            assertEquals("existing draft\nshared text", f.vm.textToSend.value)
            f.vm.addAttachments(arrayListOf(audio.path))
            assertEquals("audio/mp4", f.vm.attachments.value!!.single().mimeTypeString)
            f.vm.sendMessage()
        }
        drain()
        assertEquals(listOf("audio/mp4"), f.sent.last().types)
        assertEquals(listOf(audio.path), f.sent.last().paths)
        ui { assertTrue(f.vm.attachments.value!!.isEmpty()); assertEquals("", f.vm.textToSend.value) }
        assertTrue(audio.isFile)
    }

    @Test fun failureDoesNotClearDraftAndAcknowledgesOnlyAlreadySubmittedBasicMessages() = fixture { f ->
        val file = f.file("retry.txt")
        f.failAt = 2
        ui { f.vm.textToSend.value = "text accepted"; f.vm.addAttachments(arrayListOf(file.path)); f.vm.sendMessage() }
        drain()
        assertEquals(1, f.sent.size)
        ui {
            assertEquals("", f.vm.textToSend.value)
            assertEquals(listOf(file.path), f.vm.attachments.value!!.map { it.path })
            assertNotNull(f.vm.showRedToastEvent.value)
        }
        assertTrue(file.isFile)
        ui { f.vm.closeFileAttachmentsList() }
        awaitAbsent(file)
    }

    @Test fun explicitMimeOverridesExtensionInNativeFileModel() = fixture { f ->
        val file = f.file("audio.jpg")
        ui {
            val model = FileModel(
                file.path, file.name, file.length(), 1, false, file.path, false,
                loadMetadata = false, mimeTypeOverride = " Audio/MP4 ; codecs=mp4a.40.2 "
            )
            assertTrue(model.isAudio)
            assertFalse(model.isImage)
            assertEquals("audio/mp4", model.mimeTypeString)
            model.destroy()
        }
    }

    @Test fun sdkMessageContentTypeReachesTheReceivedAttachmentModel() = fixture { f ->
        val file = File(f.root, "received.jpg")
        Mango9MediaTestFixtures.createAudio(file)
        var model: org.linphone.ui.main.chat.model.MessageModel? = null
        try {
            core {
                val content = Factory.instance().createContent().apply {
                    type = "audio"
                    subtype = "mp4"
                    name = "Recording.jpg"
                    filePath = file.path
                    size = file.length().toInt()
                }
                assertEquals("audio/mp4", content.attachmentMimeType())
                val message = f.room.createFileTransferMessage(content)
                model = org.linphone.ui.main.chat.model.MessageModel(message, false, false, false)
            }
            drain()
            ui {
                val attachment = requireNotNull(model).filesList.value!!.single()
                assertEquals("audio/mp4", attachment.mimeTypeString)
                assertTrue(attachment.isAudio)
                assertFalse(attachment.isImage)
            }
        } finally { core { model?.destroy() }; drain() }
    }

    @Test fun sdkTransferEnvelopeNeverOverridesThePayloadName() = fixture { _ ->
        core {
            val envelope = Factory.instance().createContent().apply {
                type = "application"
                subtype = "vnd.gsma.rcs-ft-http+xml"
                name = "Recording.m4a"
            }
            assertNull(envelope.attachmentMimeType())
            assertEquals("audio/mp4", Mango9NativeAttachment.mimeType("opaque", envelope.name, envelope.attachmentMimeType()))
        }
    }

    @Test fun missingAttachmentDoesNotSubmitOnlyTheTextAndSilentlyLoseTheFile() = fixture { f ->
        val missing = File(f.root, "missing.txt")
        ui {
            f.vm.textToSend.value = "unsent caption"
            f.vm.addAttachments(arrayListOf(missing.path))
            f.vm.sendMessage()
        }
        drain()
        assertTrue(f.sent.isEmpty())
        ui {
            assertEquals("unsent caption", f.vm.textToSend.value)
            assertEquals(listOf(missing.path), f.vm.attachments.value!!.map { it.path })
            assertNotNull(f.vm.showRedToastEvent.value)
        }
    }

    private data class Sent(val text: String, val paths: List<String>, val types: List<String>, val destination: String)

    @Test fun actualSdkHistoryPagesAroundAnOldMessageAndAcrossTextOnlyGaps() = fixture { f ->
        core {
            // Real storage/ordering, but no retry timers or transport operations in the app's Core.
            val config = Factory.instance().createConfig(File(f.root, "history.linphonerc").path)
            config.setString("storage", "uri", File(f.root, "history.db").path)
            config.setString("storage", "call_logs_db_uri", "null")
            config.setString("storage", "friends_db_uri", "null")
            config.setString("storage", "zrtp_secrets_db_uri", "null")
            config.setString("lime", "x3dh_db_path", File(f.root, "history-x3dh.db").path)
            val sdk = Factory.instance().createCoreWithConfig(config, context)
            sdk.isAutoIterateEnabled = false
            try {
                assertEquals(0, sdk.start())
                sdk.isNetworkReachable = false
                val account = sdk.createAccount(
                    sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress(f.identity)
                    serverAddress = Factory.instance().createAddress("sip:example.invalid")
                    isRegisterEnabled = false
                    pushNotificationAllowed = false
                    instantMessagingEncryptionMandatory = false
                }
                )
                assertEquals(0, sdk.addAccount(account))
                sdk.defaultAccount = account
                val params = sdk.createConferenceParams(null).apply {
                    this.account = account; isChatEnabled = true; isGroupEnabled = false
                    securityLevel = Conference.SecurityLevel.None
                    chatParams!!.backend = ChatRoom.Backend.Basic
                }
                val room =
                    requireNotNull(sdk.createChatRoom(params, arrayOf(Factory.instance().createAddress("sip:history-peer@example.invalid")!!)))
                sdk.fileTransferServer = "http://127.0.0.1:9"
                val messages = (0 until 130).map { index ->
                    val message = if (index in setOf(0, 60, 129)) {
                        val file = f.file("history-$index.jpg")
                        val content = Factory.instance().createContent().apply {
                            type = "image"; subtype = "jpeg"; name = file.name; filePath = file.path
                        }
                        room.createFileTransferMessage(content)
                    } else {
                        room.createMessageFromUtf8("Synthetic history $index")
                    }
                    message.toBeStored = true
                    message.send()
                    message
                }
                assertEquals(130, room.historySize)
                val source = Mango9NativeMediaHistory(room)
                val anchorEvent = room.getHistoryRange(69, 70, ChatRoom.HistoryFilter.ChatMessage.toInt()).single()
                val anchor = requireNotNull(source.anchor(anchorEvent, File(f.root, "history-60.jpg").path))
                for ((older, expected) in listOf(true to "history-0.jpg", false to "history-129.jpg")) {
                    var cursor = anchor.cursor
                    val found = arrayListOf<String>()
                    var exhausted = false
                    repeat(10) {
                        if (!exhausted) {
                            val page = source.slice(cursor, older, 8)
                            found.addAll(page.entries.map { it.content.name.orEmpty() })
                            exhausted = page.exhausted
                            page.next?.let { cursor = it }
                        }
                    }
                    assertTrue("Native history traversal must end", exhausted)
                    assertEquals(listOf(expected), found)
                }
                // Exercise the actual SDK content-range ordering, not a mocked history array.
                assertEquals(3, room.mediaContentsSize)
                val expectedMedia = room.getMediaContentsRange(0, 3).map { it.name.orEmpty() }
                assertEquals(setOf("history-0.jpg", "history-60.jpg", "history-129.jpg"), expectedMedia.toSet())
                val turns = ArrayDeque<() -> Unit>()
                var mediaState: Mango9ContentPager.Snapshot<String>? = null
                val mediaPager = Mango9ContentPager<Content, String>(
                    1, { turns.add(it) }, { true }, {}, { mediaState = it },
                )
                try {
                    mediaPager.reset(3, { begin, end -> room.getMediaContentsRange(begin, end).toList() }) {
                        it.name?.takeUnless { name -> name == "history-60.jpg" }
                    }
                    while (turns.isNotEmpty()) turns.removeFirst()()
                    assertEquals(expectedMedia.filterNot { it == "history-60.jpg" }.take(1), mediaState!!.items)
                    mediaPager.more()
                    while (turns.isNotEmpty()) turns.removeFirst()()
                    assertEquals(expectedMedia.filterNot { it == "history-60.jpg" }, mediaState!!.items)
                    assertTrue(mediaState!!.exhausted)
                } finally { mediaPager.close() }

                repeat(3) { index ->
                    val file = f.file("history-$index.pdf")
                    val content = Factory.instance().createContent().apply {
                        type = "application"; subtype = "pdf"; name = file.name; filePath = file.path
                    }
                    room.createFileTransferMessage(content).apply { toBeStored = true; send() }
                }
                assertEquals(3, room.documentContentsSize)
                val expectedDocuments = room.getDocumentContentsRange(0, 3).map { it.name.orEmpty() }
                assertEquals(setOf("history-0.pdf", "history-1.pdf", "history-2.pdf"), expectedDocuments.toSet())
                var documentState: Mango9ContentPager.Snapshot<String>? = null
                val documentPager = Mango9ContentPager<Content, String>(
                    2, { turns.add(it) }, { true }, {}, { documentState = it },
                )
                try {
                    documentPager.reset(3, { begin, end -> room.getDocumentContentsRange(begin, end).toList() }) { it.name }
                    while (turns.isNotEmpty()) turns.removeFirst()()
                    assertEquals(expectedDocuments.take(2), documentState!!.items)
                    documentPager.more()
                    while (turns.isNotEmpty()) turns.removeFirst()()
                    assertEquals(expectedDocuments, documentState!!.items)
                    assertTrue(documentState!!.exhausted)
                } finally { documentPager.close() }
            } finally {
                // Stop the owning Core before releasing rooms: pending sends own SDK retry timers.
                sdk.stop()
            }
        }
    }

    private inner class Fixture {
        val root = File(context.cacheDir, "native-draft-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        val sessions = Mango9SessionStore(context)
        val originalIdentity = sessions.activeIdentity
        val identity = "sip:native-draft-${UUID.randomUUID()}@example.invalid"
        val accounts = mutableListOf<Account>()
        val durableCopies = mutableListOf<File>()
        val sent = CopyOnWriteArrayList<Sent>()
        val store = ViewModelStore()
        var failAt = -1
        var attempts = 0
        var originalAccount: Account? = null
        lateinit var room: ChatRoom
        val hasRoom get() = ::room.isInitialized
        lateinit var vm: SendMessageInConversationViewModel

        fun file(name: String) = File(root, name).apply { writeText("Synthetic native draft fixture") }

        fun capture(message: ChatMessage) {
            if (++attempts == failAt) throw IllegalStateException("Synthetic pre-handoff failure")
            val files = message.contents.filter { it.filePath != null }
            check(files.all { File(it.filePath!!).isFile })
            sent.add(
                Sent(
                    message.utf8Text.orEmpty(),
                    files.map {
                it.filePath!!
            },
                files.map { "${it.type}/${it.subtype}" }, message.chatRoom.peerAddress.asStringUriOnly()
                )
            )
            // Intentionally do not call ChatMessage.send(): no network, account credentials or recipient.
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try {
            // The application's core queue becomes available before the delayed startCore().
            // Chat database operations require GlobalState.On, not just a constructed Core.
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000
            var started = false
            while (!started && android.os.SystemClock.uptimeMillis() < deadline) {
                core { started = it.globalState == org.linphone.core.GlobalState.On }
                if (!started) Thread.sleep(25)
            }
            assertTrue("SDK message database did not become ready", started)
            core { sdk ->
                f.originalAccount = sdk.defaultAccount
                val account = sdk.createAccount(
                    sdk.createAccountParams().apply {
                    identityAddress = Factory.instance().createAddress(f.identity)
                    serverAddress = Factory.instance().createAddress("sip:example.invalid")
                    isRegisterEnabled = false
                    pushNotificationAllowed = false
                    instantMessagingEncryptionMandatory = false
                }
                )
                f.accounts.add(account)
                assertEquals(0, sdk.addAccount(account))
                sdk.defaultAccount = account
                val params = sdk.createConferenceParams(null).apply {
                    isChatEnabled = true
                    isGroupEnabled = false
                    this.account = account
                    securityLevel = Conference.SecurityLevel.None
                    chatParams!!.backend = ChatRoom.Backend.Basic
                }
                f.room = requireNotNull(sdk.createChatRoom(params, arrayOf(Factory.instance().createAddress("sip:recipient-${UUID.randomUUID()}@example.invalid")!!)))
            }
            f.sessions.activate(f.identity)
            ui {
                f.vm = ViewModelProvider(
                    f.store,
                    object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = SendMessageInConversationViewModel(f::capture) as T
                }
                )[SendMessageInConversationViewModel::class.java]
                f.vm.configureChatRoom(f.room)
            }
            drain()
            test(f)
        } finally {
            ui { f.store.clear() }
            drain()
            core { sdk ->
                if (f.hasRoom) sdk.deleteChatRoom(f.room)
                sdk.defaultAccount = f.originalAccount
                f.accounts.forEach { account ->
                    if (sdk.accountList.any {
                    it.nativePointer == account.nativePointer
                }
                    ) {
                    sdk.removeAccount(account)
                }
                }
            }
            f.sessions.activate(f.originalIdentity)
            f.durableCopies.forEach { assertTrue(it.delete() || !it.exists()) }
            // Draft release is asynchronous. Retained sent-history files belong to this exact
            // synthetic directory and are removed only after the fixture's SDK room is deleted.
            f.root.listFiles().orEmpty().forEach { file -> assertTrue(file.delete() || !file.exists()) }
            assertTrue(f.root.delete())
        }
    }

    private fun pauseCore(action: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        coreContext.postOnCoreThread { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try { action() } finally { release.countDown() }
        drain()
    }

    private fun drain() { core {}; ui {}; core {}; ui {} }

    private fun ui(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun core(action: (Core) -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        coreContext.postOnCoreThread { sdk ->
            try { action(sdk) } catch (error: Throwable) { failure = error } finally { done.countDown() }
        }
        assertTrue("Core queue did not drain", done.await(15, TimeUnit.SECONDS))
        failure?.let { throw AssertionError(it) }
    }

    private fun awaitAbsent(file: File) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (file.exists() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(10)
        assertFalse("Draft file was not released", file.exists())
    }
}
