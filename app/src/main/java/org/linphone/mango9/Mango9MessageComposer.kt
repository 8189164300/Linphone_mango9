// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Context
import android.net.Uri
import java.io.File
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class Mango9ComposerState(
    val text: String = "",
    val attachments: List<Mango9PendingAttachment> = emptyList(),
    val preparing: Int = 0,
    val sending: Boolean = false,
    val completedSends: Long = 0,
    val error: String? = null,
)

/** Main-thread, original-login draft; lifetime is the navigation ViewModel, not its current View. */
@MainThread
internal class Mango9MessageComposer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val prepare: suspend (Uri) -> Mango9PreparedAttachment = Mango9AttachmentPreparer(context)::prepare,
) {
    private val mutable = MutableStateFlow(Mango9ComposerState())
    val state = mutable.asStateFlow()
    private val files = linkedMapOf<Uri, Mango9PreparedAttachment>()
    private val jobs = mutableSetOf<Job>()
    private val requests = mutableSetOf<String>()
    private var capture: Mango9PreparedAttachment? = null
    private var revision = 0L
    private var disposed = false
    private var attachmentLimit = 12

    private data class SentDraft(val text: String, val revision: Long, val attachments: List<Uri>)

    private var unconfirmedDraft: SentDraft? = null

    fun setAttachmentLimit(limit: Int) { attachmentLimit = limit.coerceIn(1, 12) }

    init {
        // No network or provider access. Locks protect other conversations in this process and
        // any other live app process. Camera reservations retain their external-writer grace.
        scope.launch(Dispatchers.IO) {
            for (name in listOf("mango9-outgoing", "mango9-capture")) {
                runCatching { Mango9AttachmentDirectory.collectOrphans(File(context.cacheDir, name)) }
            }
        }
    }

    private fun current() = !disposed && isCurrent()

    fun changeText(text: String) {
        if (!current() || text == mutable.value.text) return
        revision++
        mutable.value = mutable.value.copy(text = text)
    }

    fun beginRequest(kind: String): Boolean = current() && requests.add(kind)

    fun finishRequest(kind: String): Boolean = requests.remove(kind) && current()

    fun cancelRequest(kind: String) { requests.remove(kind) }

    fun import(uris: List<Uri>, ownedSource: Mango9PreparedAttachment? = null) {
        if (!current() || uris.isEmpty()) { ownedSource?.close(); return }
        val remaining = (attachmentLimit - files.size - mutable.value.preparing).coerceAtLeast(0)
        val selected = uris.take(remaining)
        mutable.value = mutable.value.copy(
            preparing = mutable.value.preparing + selected.size,
            error = if (uris.size > remaining) attachmentLimitMessage() else null,
        )
        if (selected.isEmpty()) { ownedSource?.close(); return }
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (uri in selected) {
                    var prepared: Mango9PreparedAttachment? = null
                    try {
                        prepared = prepare(uri)
                        ensureActive()
                        if (!current()) break
                        val added = requireNotNull(prepared)
                        files[added.attachment.uri] = added
                        prepared = null // transferred to the draft
                        publishFiles()
                    mutable.value = mutable.value.copy(completedSends = mutable.value.completedSends + 1)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        if (current()) {
                            mutable.value = mutable.value.copy(
                            error = Mango9AttachmentFailure.message(error),
                        )
                        }
                    } finally { prepared?.close() }
                }
            } finally {
                ownedSource?.close()
                mutable.value = mutable.value.copy(preparing = (mutable.value.preparing - selected.size).coerceAtLeast(0))
                jobs.remove(coroutineContext.job)
            }
        }
        if (job.isActive) jobs.add(job)
    }

    /** Adopt immutable, already-prepared share files without querying their provider a second time. */
    fun adopt(share: Mango9SharedDraft) {
        try {
            if (!current() || !share.isCurrent()) return
            val remaining = (attachmentLimit - files.size - mutable.value.preparing).coerceAtLeast(0)
            share.files.take(remaining).forEach { file ->
                if (file.attachment.uri !in files) files[file.attachment.uri] = file.retain()
            }
            changeText(org.linphone.mango9.Mango9PendingShare.mergeText(mutable.value.text, share.text))
            mutable.value = mutable.value.copy(
                error = if (share.files.size > remaining) attachmentLimitMessage() else share.error,
            )
            publishFiles()
        } finally { share.close() }
    }

    fun remove(uri: Uri) {
        files.remove(uri)?.close()
        publishFiles()
    }

    /** Acknowledgment removes only the exact sent attachments and unchanged text revision. */
    fun send(action: suspend (String, List<Mango9PendingAttachment>) -> Boolean) {
        val initial = mutable.value
        if (!current() || initial.sending || initial.preparing > 0 ||
            (initial.text.isBlank() && files.isEmpty())
        ) {
                return
            }
        val sentRevision = revision
        val pins = files.values.map { it.retain() }
        unconfirmedDraft = SentDraft(initial.text, sentRevision, pins.map { it.attachment.uri })
        mutable.value = initial.copy(sending = true, error = null)
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (current() && action(initial.text, pins.map { it.attachment }) && current()) {
                    unconfirmedDraft = null
                    pins.forEach { files.remove(it.attachment.uri)?.close() }
                    if (revision == sentRevision) mutable.value = mutable.value.copy(text = "")
                    publishFiles()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (current()) mutable.value = mutable.value.copy(error = "The message could not be sent. Please try again.")
            } finally {
                pins.forEach { it.close() }
                mutable.value = mutable.value.copy(sending = false)
                jobs.remove(coroutineContext.job)
            }
        }
        if (job.isActive) jobs.add(job)
    }

    /** A late saved receipt may acknowledge only its original draft, never newer attachments. */
    fun confirmSavedSend(text: String) {
        val sent = unconfirmedDraft ?: return
        if (!current() || sent.text != text) return
        unconfirmedDraft = null
        sent.attachments.forEach { files.remove(it)?.close() }
        if (revision == sent.revision) mutable.value = mutable.value.copy(text = "")
        publishFiles()
        mutable.value = mutable.value.copy(completedSends = mutable.value.completedSends + 1)
    }

    /** Kept through rotation while the external camera owns the result. */
    fun createCapture(ready: (File) -> Boolean) {
        if (!current()) return
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var owned: Mango9PreparedAttachment? = null
            try {
                owned = Mango9PreparedAttachment.createCapture(context, "photo.jpg", "image/jpeg")
                if (!current()) return@launch
                capture?.close()
                capture = owned
                owned = null
                if (!ready(File(requireNotNull(capture).attachment.uri.path!!))) cancelCapture()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                cancelRequest("capture")
                cancelCapture()
                if (current()) mutable.value = mutable.value.copy(error = "The camera could not be opened.")
            } finally { owned?.close(); jobs.remove(coroutineContext.job) }
        }
        if (job.isActive) jobs.add(job)
    }

    fun finishCapture(success: Boolean) {
        val source = capture ?: return
        capture = null
        if (success && current()) import(listOf(source.attachment.uri), source) else source.close()
    }

    fun cancelCapture() { capture?.close(); capture = null }

    fun dispose() {
        if (disposed) return
        disposed = true
        unconfirmedDraft = null
        requests.clear()
        jobs.toList().forEach { it.cancel() }
        files.values.forEach { it.close() }
        files.clear()
        cancelCapture()
        mutable.value = Mango9ComposerState()
    }

    private fun publishFiles() { mutable.value = mutable.value.copy(attachments = files.values.map { it.attachment }) }

    private fun attachmentLimitMessage() = if (attachmentLimit == 1) "Social supports one attachment per message." else "You can attach up to $attachmentLimit files."
}
