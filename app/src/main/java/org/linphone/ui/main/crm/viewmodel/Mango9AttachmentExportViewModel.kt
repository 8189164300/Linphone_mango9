// Copyright (c) 2026 Mango9. SPDX-License-Identifier: GPL-3.0-or-later
package org.linphone.ui.main.crm.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.mango9.Mango9AttachmentExports
import org.linphone.mango9.Mango9ChatMedia
import org.linphone.mango9.Mango9MediaCache
import org.linphone.mango9.Mango9SessionLease
import org.linphone.mango9.Mango9SessionStore
import org.linphone.utils.Event

/** Picker requests and transfers survive rotation; each transfer remains tied to its original login. */
internal class Mango9AttachmentExportViewModel(application: Application) : AndroidViewModel(application) {
    data class Request(val media: List<Mango9ChatMedia>, val lease: Mango9SessionLease)

    data class Open(val intent: Intent, val lease: Mango9SessionLease)

    private val context = application.applicationContext
    private val sessions = Mango9SessionStore(context)
    private var pending: Request? = null
    val busy = MutableLiveData(false)
    val notices = MutableLiveData<Event<String>>()
    val open = MutableLiveData<Event<Open>>()

    fun begin(media: List<Mango9ChatMedia>, lease: Mango9SessionLease): Boolean {
        if (busy.value == true || media.isEmpty() || sessions.currentSession(lease) == null) return false
        pending = Request(media, lease)
        busy.value = true
        return true
    }

    fun cancelPicker() { pending = null; busy.value = false }

    fun save(uri: Uri?, folder: Boolean = false, gallery: Boolean = false) {
        val request = pending
        pending = null
        if (request == null) {
            busy.value = false
            if (uri != null) notices.value = Event("Export was interrupted. Select the attachment again.")
            return
        }
        if (!gallery && uri == null) { busy.value = false; return }
        viewModelScope.launch {
            var saved = 0
            try {
                for (media in request.media) {
                    check(sessions.currentSession(request.lease) != null)
                    val asset = Mango9MediaCache.acquire(context, media, request.lease)
                    try {
                        val current = { sessions.currentSession(request.lease) != null }
                        if (gallery) {
                            Mango9AttachmentExports.saveGallery(context, asset.file, media, current)
                        } else {
                            val target = if (folder) {
                                withContext(Dispatchers.IO) {
                                check(current())
                                val parent = DocumentsContract.buildDocumentUriUsingTree(requireNotNull(uri), DocumentsContract.getTreeDocumentId(uri))
                                requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, media.mimeType, Mango9AttachmentExports.fileName(media)))
                            }
                            } else {
                                requireNotNull(uri)
                            }
                            try {
                                Mango9AttachmentExports.saveDocument(context, asset.file, target, current)
                            } catch (failure: Throwable) {
                                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                                }
                                throw failure
                            }
                        }
                        saved++
                    } finally { asset.close() }
                }
                notices.value = Event(if (saved == 1) "Attachment saved." else "$saved attachments saved.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notices.value = Event(if (saved == 0) "Could not export the attachment. Please try again." else "Saved $saved of ${request.media.size} attachments. Please retry the remaining files.")
            } finally { busy.value = false }
        }
    }

    fun openWith() {
        val request = pending ?: return
        pending = null
        viewModelScope.launch {
            try {
                val media = request.media.single()
                val asset = Mango9MediaCache.acquire(context, media, request.lease)
                try {
                    val file = Mango9AttachmentExports.externalCopy(context, asset.file, media) {
                        sessions.currentSession(request.lease) != null
                    }
                    check(sessions.currentSession(request.lease) != null)
                    open.value = Event(Open(Mango9AttachmentExports.openIntent(context, file, media), request.lease))
                } finally { asset.close() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notices.value = Event("Could not open the attachment. Please try again.")
            } finally { busy.value = false }
        }
    }
}
