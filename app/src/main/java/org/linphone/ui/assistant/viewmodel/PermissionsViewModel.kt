/*
 * Copyright (c) 2010-2025 Belledonne Communications SARL.
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
package org.linphone.ui.assistant.viewmodel

import android.Manifest
import android.os.Build
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel

class PermissionsViewModel
@UiThread
constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Permissions ViewModel]"
    }

    val cameraPermissionGranted = MutableLiveData<Boolean>()

    val recordAudioPermissionGranted = MutableLiveData<Boolean>()

    val readContactsPermissionGranted = MutableLiveData<Boolean>()

    val postNotificationsPermissionGranted = MutableLiveData<Boolean>()

    val accessLocalNetworkPermissionGranted = MutableLiveData<Boolean>()

    /** Refresh every row, including revocations; a denial must not short-circuit later rows. */
    fun refreshPermissions(
        permissions: Array<String> = Compatibility.getAllRequiredPermissionsArray(),
        isGranted: (String) -> Boolean
    ): Boolean {
        val grants = permissions.associateWith(isGranted)
        setPermissionGranted(Manifest.permission.CAMERA, grants[Manifest.permission.CAMERA] ?: true)
        setPermissionGranted(Manifest.permission.RECORD_AUDIO, grants[Manifest.permission.RECORD_AUDIO] ?: true)
        setPermissionGranted(Manifest.permission.READ_CONTACTS, grants[Manifest.permission.READ_CONTACTS] ?: true)
        postNotificationsPermissionGranted.value = if (Build.VERSION.SDK_INT >= 33) grants[Manifest.permission.POST_NOTIFICATIONS] ?: true else true
        accessLocalNetworkPermissionGranted.value = if (Build.VERSION.SDK_INT >= 37) grants[Manifest.permission.ACCESS_LOCAL_NETWORK] ?: true else true
        return grants.values.all { it }
    }

    fun setPermissionGranted(permission: String, granted: Boolean) {
        Log.i("$TAG Permission [$permission] is ${if (granted) "granted" else "not granted yet/denied"}")
        when (permission) {
            Manifest.permission.READ_CONTACTS -> readContactsPermissionGranted.value = granted
            Manifest.permission.RECORD_AUDIO -> recordAudioPermissionGranted.value = granted
            Manifest.permission.CAMERA -> cameraPermissionGranted.value = granted
            Manifest.permission.POST_NOTIFICATIONS -> postNotificationsPermissionGranted.value = granted
            Manifest.permission.ACCESS_LOCAL_NETWORK -> accessLocalNetworkPermissionGranted.value = granted
        }
    }
}
