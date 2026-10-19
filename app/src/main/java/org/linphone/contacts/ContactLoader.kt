/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 * Copyright (c) 2026 Mango9.
 *
 * This file is part of linphone-android (see https://www.linphone.org).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.linphone.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.ContactsContract
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.loader.content.Loader
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.mango9.Mango9ContactImportGeneration
import org.linphone.utils.Event

/** The Loader owns its query/cursor completely. Only a complete value snapshot leaves its worker. */
class ContactLoader : LoaderManager.LoaderCallbacks<NativeContactSnapshot> {
    companion object {
        private const val TAG = "[Contacts Loader]"
        const val NATIVE_ADDRESS_BOOK_FRIEND_LIST = "Native address-book"
        const val LINPHONE_ADDRESS_BOOK_FRIEND_LIST = "Linphone address-book"
        // Also fences a retired Activity/Loader after restartLoader installs a replacement.
        private val imports = Mango9ContactImportGeneration()
        val projection get() = NativeContactSnapshotReader.projection
    }

    private var generation = imports.begin()

    @MainThread
    override fun onCreateLoader(id: Int, args: Bundle?): Loader<NativeContactSnapshot> = SnapshotLoader(
        coreContext.context, args?.getBoolean("defaultDirectory", true) != false,
    )

    @MainThread
    override fun onLoadFinished(loader: Loader<NativeContactSnapshot>, snapshot: NativeContactSnapshot?) {
        if (!imports.isCurrent(generation)) return
        generation = imports.begin()
        val token = generation
        if (snapshot == null || snapshot == NativeContactSnapshot.Unavailable) {
            reportFailure()
            return
        }
        val directory = (snapshot as? NativeContactSnapshot.Complete)?.directory ?: NativeContactDirectory.EMPTY
        val revoked = snapshot == NativeContactSnapshot.PermissionDenied

        fun publishWhenReady() {
            coreContext.postOnCoreThread { core ->
                if (!imports.isCurrent(token) || canReadContacts(coreContext.context) == revoked) return@postOnCoreThread
                if (core.globalState != GlobalState.On) {
                    coreContext.postOnCoreThreadDelayed({ publishWhenReady() }, 250)
                } else {
                    coreContext.contactsManager.installNativeContacts(directory)
                }
            }
        }
        publishWhenReady()
    }

    @MainThread
    override fun onLoaderReset(loader: Loader<NativeContactSnapshot>) { imports.cancel(generation) }

    private fun reportFailure() {
        Log.w("$TAG Native address book could not be completely read; keeping the last complete snapshot")
        coreContext.showRedToastEvent.postValue(Event(Pair(R.string.mango9_contacts_reload_failed, R.drawable.warning_circle)))
    }
}

private fun canReadContacts(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.READ_CONTACTS,
) == PackageManager.PERMISSION_GRANTED

private class SnapshotLoader(context: Context, private val defaultDirectory: Boolean) : AsyncTaskLoader<NativeContactSnapshot>(context) {
    private val observer = ForceLoadContentObserver()
    private var observing = false
    private val cancellationLock = Any()
    private var cancellation: CancellationSignal? = null

    init { setUpdateThrottle(600) }

    override fun onStartLoading() {
        if (!observing) {
            try {
                context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
                observing = true
            } catch (_: SecurityException) { /* The read below reports permission revocation safely. */ }
        }
        forceLoad()
    }

    override fun loadInBackground(): NativeContactSnapshot {
        if (!canReadContacts(context)) return NativeContactSnapshot.PermissionDenied
        val signal = synchronized(cancellationLock) {
            if (isLoadInBackgroundCanceled) throw androidx.core.os.OperationCanceledException()
            CancellationSignal().also { cancellation = it }
        }
        return try {
            val mime = ContactsContract.Data.MIMETYPE
            val mimeSelection = "($mime = ? OR $mime = ? OR $mime = ? OR $mime = ?)"
            val selection = if (defaultDirectory) "${ContactsContract.Data.IN_DEFAULT_DIRECTORY} = 1 AND $mimeSelection" else mimeSelection
            val args = arrayOf(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            )
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI, NativeContactSnapshotReader.projection,
                selection, args, "${ContactsContract.Data.CONTACT_ID} ASC", signal
            )?.use { cursor ->
                NativeContactSnapshot.Complete(
                    NativeContactSnapshotReader.read(cursor) {
                    signal.isCanceled || isLoadInBackgroundCanceled
                }
                )
            } ?: NativeContactSnapshot.Unavailable
        } catch (_: OperationCanceledException) {
            // AsyncTaskLoader recognizes AndroidX's cancellation exception, not the framework one.
            throw androidx.core.os.OperationCanceledException()
        } catch (_: SecurityException) {
            if (canReadContacts(context)) NativeContactSnapshot.Unavailable else NativeContactSnapshot.PermissionDenied
        } catch (_: Exception) {
            NativeContactSnapshot.Unavailable
        } finally {
            synchronized(cancellationLock) { if (cancellation === signal) cancellation = null }
        }
    }

    override fun cancelLoadInBackground() {
        super.cancelLoadInBackground()
        synchronized(cancellationLock) { cancellation?.cancel() }
    }

    override fun onStopLoading() { cancelLoad() }

    override fun onReset() {
        super.onReset()
        cancelLoad()
        if (observing) { context.contentResolver.unregisterContentObserver(observer); observing = false }
    }
}
