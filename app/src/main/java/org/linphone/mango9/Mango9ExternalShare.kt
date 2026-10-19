// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.mango9

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.annotation.MainThread
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Mango9NativeConversationRoute(val conversationId: String, val shareRequestId: String? = null)

internal data class Mango9ExternalShareState(
    val id: String? = null,
    val text: String = "",
    val count: Int = 0,
    val preparing: Boolean = false,
    val delivery: String? = null,
    val destination: String? = null,
    val error: String? = null,
)

/** Claim transfers ownership, not raw paths. The receiving draft must retain or close every file. */
internal class Mango9SharedDraft(
    val text: String,
    val files: List<Mango9PreparedAttachment>,
    val isCurrent: () -> Boolean,
    val error: String? = null,
) : Closeable {
    override fun close() { files.forEach { it.close() } }
}

/** Activity-ViewModel lifetime. All transitions are serialized on the main thread. */
@MainThread
internal class Mango9ExternalShare(
    private val scope: CoroutineScope,
    private val stamp: () -> Mango9SelectionStamp,
    private val prepare: suspend (Uri, String?) -> Mango9PreparedAttachment,
) {
    private val mutable = MutableStateFlow(Mango9ExternalShareState())
    val state = mutable.asStateFlow()
    private var owner: Mango9SelectionStamp? = null
    private var job: Job? = null
    private val files = mutableListOf<Mango9PreparedAttachment>()
    private var disposed = false

    fun begin(text: String, uris: List<Uri>, mimeHint: String? = null): String? {
        if (disposed) return null
        clear()
        if (text.isEmpty() && uris.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        owner = stamp()
        val selected = uris.distinct().take(MAX_FILES)
        mutable.value = Mango9ExternalShareState(
            id, text, preparing = selected.isNotEmpty(),
            error = if (uris.size > MAX_FILES) "You can share up to 12 files at a time." else null,
        )
        job = scope.launch {
            for (uri in selected) {
                var prepared: Mango9PreparedAttachment? = null
                try {
                    prepared = prepare(uri, mimeHint)
                    ensureActive()
                    if (!current(id)) return@launch
                    files.add(requireNotNull(prepared))
                    prepared = null
                    mutable.value = mutable.value.copy(count = files.size)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (!current(id)) return@launch
                    mutable.value = mutable.value.copy(
                        error = Mango9AttachmentFailure.message(error),
                    )
                } finally { prepared?.close() }
            }
            if (current(id)) mutable.value = mutable.value.copy(preparing = false)
        }
        return id
    }

    fun pendingRequestId(): String? = mutable.value.id?.takeIf(::current)

    /** A navigation action carries its original request, never a later share that replaced it. */
    fun bind(id: String?, destination: String): String? {
        if (id == null || !current(id)) return null
        val delivery = UUID.randomUUID().toString()
        mutable.value = mutable.value.copy(delivery = delivery, destination = destination)
        return delivery
    }

    fun claim(delivery: String?, destination: String): Mango9SharedDraft? {
        val value = mutable.value
        if (delivery == null || value.id == null || !current(value.id) || value.preparing ||
            value.delivery != delivery || value.destination != destination
        ) {
            return null
        }
        val original = requireNotNull(owner)
        val result = Mango9SharedDraft(value.text, files.toList(), { stamp() == original }, value.error)
        files.clear() // ownership has moved to the destination
        clear()
        return result
    }

    fun accountChanged() { if (owner != null && owner != stamp()) clear() }

    private fun current(id: String): Boolean {
        accountChanged()
        return !disposed && mutable.value.id == id
    }

    fun clear() {
        job?.cancel()
        job = null
        files.forEach { it.close() }
        files.clear()
        owner = null
        mutable.value = Mango9ExternalShareState()
    }

    fun dispose() { disposed = true; clear() }

    companion object {
        const val ARG_DELIVERY = "mango9ShareDelivery"
        const val ARG_REQUEST = "mango9ShareRequest"
        const val MAX_FILES = 12

        fun nativeDestination(id: String) = "sip:$id"

        fun crmDestination(type: String, userId: Int, roomId: String?, phone: String?): String =
            if (type == "sms") {
                "sms:${Mango9PhoneNumber.smsDestination(phone.orEmpty())}"
            } else if (type == "social") {
                "social-thread:$roomId"
            } else if (!roomId.isNullOrBlank()) {
                "team-room:$roomId"
            } else {
                "team-user:$userId"
            }

        /** Bounded before provider work; the thirteenth entry is only an overflow indicator. */
        @Suppress("DEPRECATION")
        fun streams(intent: Intent): List<Uri> {
            val result = linkedSetOf<Uri>()
            val extras = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty()
            } else {
                listOfNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM))
            }
            for (value in extras) {
                // External senders must grant a provider URI. A file:// path could name this
                // app's private data; only internal pickers may pass files to the preparer.
                if (value is Uri && value.scheme == "content") result.add(value)
                if (result.size > MAX_FILES) break
            }
            val clips = intent.clipData
            if (result.isEmpty() && clips != null) {
                for (index in 0 until minOf(clips.itemCount, MAX_FILES + 1)) {
                    clips.getItemAt(index).uri?.takeIf { it.scheme == "content" }?.let(result::add)
                }
            }
            return result.toList()
        }
    }
}
