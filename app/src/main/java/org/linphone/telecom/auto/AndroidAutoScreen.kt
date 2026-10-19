/*
 * Copyright (c) 2010-2024 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.telecom.auto

import android.graphics.ImageDecoder
import androidx.annotation.AnyThread
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.contacts.AvatarGenerator
import org.linphone.contacts.ContactSearch
import org.linphone.contacts.ContactsManager
import org.linphone.contacts.getNativeContactPictureUri
import org.linphone.contacts.sortName
import org.linphone.core.Account
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Friend
import org.linphone.core.GlobalState
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

class AndroidAutoScreen(context: CarContext) : Screen(context) {
    private var favoritesList = emptyList<GridItem>()
    private var candidates = emptyList<ContactSearch.Candidate>()
    private var loading = true
    private var pageStart = 0
    private var searchToken = -1L
    private val renderRevision = AtomicLong()

    @Volatile private var active = false

    @Volatile private var destroyed = false
    private val pageSize: Int by lazy {
        if (carContext.carAppApiLevel >= 2) {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID).coerceAtLeast(1)
        } else {
            6
        }
    }
    private val search: ContactSearch = ContactSearch(lifecycleScope) { token, results, _ ->
        coreContext.postOnMainThread {
            if (active && current(token)) {
                candidates = results
                searchToken = token
                pageStart = minOf(pageStart, ((results.size - 1).coerceAtLeast(0) / pageSize) * pageSize)
                renderPage()
            }
        }
    }

    private fun current(token: Long): Boolean = !destroyed && search.isCurrent(token)

    private val contactsListener = object : ContactsManager.ContactsListener {
        override fun onContactsLoaded() { refresh() }

        override fun onContactFoundInRemoteDirectory(friend: Friend) {}
    }
    private val coreListener = object : CoreListenerStub() {
        override fun onDefaultAccountChanged(core: Core, defaultAccount: Account?) { refresh() }

        override fun onGlobalStateChanged(core: Core, state: GlobalState?, message: String) {
            if (state == GlobalState.On) refresh()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                active = true
                refresh()
            }

            override fun onStop(owner: LifecycleOwner) {
                active = false
                renderRevision.incrementAndGet()
                search.cancel()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                destroyed = true
                search.cancel()
                coreContext.postOnCoreThread { core ->
                    core.removeListener(coreListener)
                    coreContext.contactsManager.removeListener(contactsListener)
                }
            }
        })
        coreContext.postOnCoreThread { core ->
            if (!destroyed) {
                core.addListener(coreListener)
                coreContext.contactsManager.addListener(contactsListener)
            }
        }
    }

    @AnyThread
    private fun refresh() {
        if (!active || destroyed) return
        renderRevision.incrementAndGet()
        search.cancel()
        coreContext.postOnCoreThread { core ->
            if (active && !destroyed && core.globalState == GlobalState.On) {
                // Device phone numbers have no SIP domain; retain them alongside
                // application/remote favorites instead of filtering them out.
                search.search("", "", favoritesOnly = true)
            }
        }
    }

    private fun renderPage() {
        if (!active || !current(searchToken)) return
        val token = searchToken
        val revision = renderRevision.incrementAndGet()
        val page = candidates.drop(pageStart).take(pageSize)
        loading = true
        invalidate()
        coreContext.postOnCoreThread {
            if (!active || !current(token) || revision != renderRevision.get()) return@postOnCoreThread
            // Read SDK metadata only for this host-sized page, on the core queue.
            val values = page.map { row ->
                val title = row.native?.let { it.displayName.ifBlank { it.sortName(true) } } ?: row.name
                val photo = row.native?.photoUri ?: row.friend?.let { it.photo ?: it.getNativeContactPictureUri()?.toString() }
                Triple(row, title, photo)
            }
            lifecycleScope.launch {
                val items = withContext(Dispatchers.IO) {
                    values.map { (row, title, photo) ->
                        val bitmap = try {
                            photo?.takeIf { it.isNotBlank() }?.let {
                                ImageDecoder.decodeBitmap(ImageDecoder.createSource(coreContext.context.contentResolver, it.toUri())) { decoder, info, _ ->
                                    val scale = 128f / maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                                    decoder.setTargetSize(
                                        (info.size.width * scale).toInt().coerceAtLeast(1),
                                        (info.size.height * scale).toInt().coerceAtLeast(1),
                                    )
                                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                }
                            }
                        } catch (_: Exception) { null }
                        val icon = bitmap ?: AvatarGenerator(coreContext.context)
                            .setInitials(AppUtils.getInitials(title)).buildBitmap(false)
                        GridItem.Builder().setTitle(title.ifBlank { AppUtils.getString(R.string.mango9_contact_label) })
                            .setImage(CarIcon.Builder(IconCompat.createWithBitmap(icon)).build(), GridItem.IMAGE_TYPE_LARGE)
                            .setOnClickListener {
                                coreContext.postOnCoreThread {
                                    if (!active || !current(token)) return@postOnCoreThread
                                    val native = row.native
                                    val friend = if (native != null) {
                                        val present = coreContext.contactsManager.nativeContactsSnapshot().find(native.id)
                                        if (present == null || !coreContext.contactsManager.nativeFavorite(present)) return@postOnCoreThread
                                        coreContext.contactsManager.resolveNativeFriend(native.id)
                                    } else {
                                        row.friend?.takeIf { it.inList() && it.starred }
                                    }
                                    friend?.let(LinphoneUtils::getFirstAvailableAddressForFriend)?.let { coreContext.startAudioCall(it) }
                                }
                            }.build()
                    }
                }
                if (active && current(token) && revision == renderRevision.get()) {
                    favoritesList = items
                    loading = false
                    invalidate()
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setTitle(carContext.getString(R.string.car_favorites_contacts_title))
            .setStartHeaderAction(Action.APP_ICON)

        fun pageAction(icon: Int, description: Int, start: Int): Action =
            Action.Builder().setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, icon))
                    .build(),
            ).apply {
                // Grid headers allow only one text action; the back arrow
                // remains icon-only when both paging controls are present.
                if (icon == R.drawable.caret_right) setTitle(carContext.getString(description))
            }.setOnClickListener { pageStart = start; renderPage() }.build()
        if (!loading && pageStart > 0) {
            header.addEndHeaderAction(pageAction(R.drawable.caret_left, R.string.mango9_previous_favorites, (pageStart - pageSize).coerceAtLeast(0)))
        }
        if (!loading && pageStart + pageSize < candidates.size) {
            header.addEndHeaderAction(pageAction(R.drawable.caret_right, R.string.mango9_next_favorites, pageStart + pageSize))
        }
        val grid = GridTemplate.Builder().setHeader(header.build()).setLoading(loading)
        if (!loading) {
            val list = ItemList.Builder().setNoItemsMessage(carContext.getString(R.string.car_favorites_contacts_list_empty))
            favoritesList.forEach(list::addItem)
            grid.setSingleList(list.build())
        }
        return grid.build()
    }
}
