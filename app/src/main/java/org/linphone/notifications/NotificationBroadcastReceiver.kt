/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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
package org.linphone.notifications

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.ConferenceParams
import org.linphone.core.Core
import org.linphone.core.CoreTaskDispatcher
import org.linphone.core.tools.Log
import org.linphone.utils.AudioUtils

class NotificationBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "[Notification Broadcast Receiver]"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(NotificationsManager.INTENT_NOTIF_ID, 0)
        val action = intent.action
        Log.i("$TAG Got notification broadcast for ID [$notificationId] with action [$action]")

        val isCall =
            action == NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION ||
            action == NotificationsManager.INTENT_TOGGLE_SPEAKER_CALL_NOTIF_ACTION
        val isChat =
            action == NotificationsManager.INTENT_REPLY_MESSAGE_NOTIF_ACTION ||
            action == NotificationsManager.INTENT_MARK_MESSAGE_AS_READ_NOTIF_ACTION
        if ((!isCall && !isChat) || action == null) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val capturedIntent = Intent(intent)
        val mainHandler = Handler(Looper.getMainLooper())
        val timeoutToken = Any()
        val lifetime = NotificationActionLifetime(SystemClock::elapsedRealtime, 8_000L) {
            mainHandler.removeCallbacksAndMessages(timeoutToken)
            pendingResult.finish()
        }
        // This variable and the timeout callback are confined to the main looper.
        var ticket: CoreTaskDispatcher.Ticket? = null
        mainHandler.postAtTime({
            ticket?.cancel()
            lifetime.finish()
            Log.w("$TAG Notification action exceeded its execution deadline")
        }, timeoutToken, SystemClock.uptimeMillis() + 8_000L)
        ticket = coreContext.postOnCoreThreadCancellable { core ->
            lifetime.run {
                try {
                    if (isCall) {
                        handleCallIntent(capturedIntent, notificationId, action)
                    } else {
                        handleChatIntent(core, appContext, capturedIntent, notificationId, action)
                    }
                } catch (error: Exception) {
                    Log.e("$TAG Notification action failed: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private fun handleCallIntent(intent: Intent, notificationId: Int, action: String) {
        val target = CallNotificationIntent.read(intent) ?: return
        val expectedOperation = if (action == NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION) {
            CallNotificationIntent.HANGUP
        } else {
            CallNotificationIntent.SPEAKER
        }
        if (target.operation != expectedOperation) return
        // No remote-address/default-call fallback: it can affect another account's call.
        val call = coreContext.notificationsManager.findCallForNotification(target.token)
        if (call == null) {
            Log.i("$TAG Ignoring expired call notification [$notificationId]")
            return
        }
        when (action) {
            NotificationsManager.INTENT_HANGUP_CALL_NOTIF_ACTION -> coreContext.terminateCall(call)
            NotificationsManager.INTENT_TOGGLE_SPEAKER_CALL_NOTIF_ACTION -> {
                if (call.outputAudioDevice?.type == AudioDevice.Type.Speaker) {
                    AudioUtils.routeAudioToEarpiece(call)
                } else {
                    AudioUtils.routeAudioToSpeaker(call)
                }
            }
        }
    }

    private fun handleChatIntent(core: Core, context: Context, intent: Intent, notificationId: Int, action: String) {
        val remoteSipAddress = intent.getStringExtra(NotificationsManager.INTENT_REMOTE_SIP_URI)
        if (remoteSipAddress == null) {
            Log.e("$TAG Remote SIP address is null for notification ID [$notificationId]")
            return
        }
        val localIdentity = intent.getStringExtra(NotificationsManager.INTENT_LOCAL_IDENTITY)
        if (localIdentity == null) {
            Log.e("$TAG Local identity is null for notification ID [$notificationId]")
            return
        }

        val reply = getMessageText(intent)?.toString()
        if (action == NotificationsManager.INTENT_REPLY_MESSAGE_NOTIF_ACTION) {
            if (reply == null) {
                Log.e("$TAG Couldn't get reply text")
                return
            }
        }

        run {
            val remoteAddress = core.interpretUrl(remoteSipAddress, false)
            if (remoteAddress == null) {
                Log.e(
                    "$TAG Couldn't interpret remote address [$remoteSipAddress]"
                )
                return
            }

            val localAddress = core.interpretUrl(localIdentity, false)
            if (localAddress == null) {
                Log.e(
                    "$TAG Couldn't interpret local address [$localIdentity]"
                )
                return
            }

            val params: ConferenceParams? = null
            val room = core.searchChatRoom(
                params,
                localAddress,
                remoteAddress,
                arrayOfNulls<Address>(
                    0
                )
            )
            if (room == null) {
                Log.e(
                    "$TAG Couldn't find conversation for remote address [$remoteSipAddress] and local address [$localIdentity]"
                )
                return
            }

            if (action == NotificationsManager.INTENT_REPLY_MESSAGE_NOTIF_ACTION) {
                val msg = room.createMessageFromUtf8(reply)
                msg.userData = notificationId
                msg.addListener(coreContext.notificationsManager.chatMessageListener)
                msg.send()
                Log.i("$TAG Reply sent for notif id [$notificationId]")
            } else if (action == NotificationsManager.INTENT_MARK_MESSAGE_AS_READ_NOTIF_ACTION) {
                Log.i("$TAG Marking chat room from notification id [$notificationId] as read")
                room.markAsRead()
                if (!coreContext.notificationsManager.dismissChatNotification(room)) {
                    Log.w(
                        "$TAG Notifications Manager failed to cancel notification"
                    )
                    val notificationManager = context.getSystemService(
                        NotificationManager::class.java
                    )
                    notificationManager.cancel(NotificationsManager.CHAT_TAG, notificationId)
                }
            }
        }
    }

    private fun getMessageText(intent: Intent): CharSequence? {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        return remoteInput?.getCharSequence(NotificationsManager.KEY_TEXT_REPLY)
    }
}
